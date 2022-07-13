(ns nextjournal.clojure-mode.demo
  (:require ["@codemirror/language" :refer [foldGutter syntaxHighlighting defaultHighlightStyle LanguageSupport syntaxTree]]
            ["@codemirror/lang-markdown" :as MD :refer [markdown markdownLanguage]]
            ["@codemirror/commands" :refer [history historyKeymap]]
            ["@codemirror/state" :refer [EditorState StateField StateEffect Transaction]]
            ["@codemirror/view" :as view :refer [EditorView ViewPlugin Decoration DecorationSet WidgetType]]
            ["@lezer/markdown" :as lezer-markdown]
            [nextjournal.clerk.sci-viewer :as sv]
            [nextjournal.clerk.viewer :as v]
            [applied-science.js-interop :as j]
            [shadow.cljs.modern :refer (defclass)]
            [clojure.string :as str]
            [nextjournal.markdown :as md]
            [nextjournal.clojure-mode :as cm-clj]
            [nextjournal.clojure-mode.demo.sci :as sci]
            [nextjournal.clojure-mode.node :as n]
            [nextjournal.clojure-mode.keymap :as keymap]
            [nextjournal.clojure-mode.live-grammar :as live-grammar]
            [nextjournal.clojure-mode.test-utils :as test-utils]
            ["react" :as react]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [reagent.dom.server :as rdom.server]))

(def theme
  (.theme EditorView
          (j/lit {".cm-content" {:white-space "pre-wrap"
                                 :padding "10px 0"}
                  "&.cm-focused" {:outline "none"}
                  ".cm-line" {:padding "0 9px"
                              :line-height "1.6"
                              :font-size "16px"
                              :font-family "var(--code-font)"}
                  ".cm-matchingBracket" {:border-bottom "1px solid var(--teal-color)"
                                         :color "inherit"}
                  ".cm-gutters" {:background "transparent"
                                 :border "none"}
                  ".cm-gutterElement" {:margin-left "5px"}
                  ;; only show cursor when focused
                  ".cm-cursor" {:visibility "hidden"}
                  "&.cm-focused .cm-cursor" {:visibility "visible"}})))

(defonce extensions #js[theme
                        (history)
                        (syntaxHighlighting defaultHighlightStyle)
                        (view/drawSelection)
                        (foldGutter)
                        (.. EditorState -allowMultipleSelections (of true))
                        (if false
                          ;; use live-reloading grammar
                          #js[(cm-clj/syntax live-grammar/parser)
                              (.slice cm-clj/default-extensions 1)]
                          cm-clj/default-extensions)
                        (.of view/keymap cm-clj/complete-keymap)
                        (.of view/keymap historyKeymap)])


(defn editor [source {:keys [eval?]}]
  (r/with-let [!view (r/atom nil)
               last-result (when eval? (r/atom (sci/eval-string source)))
               mount! (fn [el]
                        (when el
                          (reset! !view (new EditorView
                                             (j/obj :state
                                                    (test-utils/make-state
                                                     (cond-> #js [extensions]
                                                       eval? (.concat #js [(sci/extension {:modifier "Alt"
                                                                                           :on-result (partial reset! last-result)})]))
                                                     source)
                                                    :parent el)))))]
    [:div
     [:div {:class "rounded-md mb-0 text-sm monospace overflow-auto relative border shadow-lg bg-white"
            :ref mount!
            :style {:max-height 410}}]
     (when eval?
       [:div.mt-3.mv-4.pl-6 {:style {:white-space "pre-wrap" :font-family "var(--code-font)"}}
        (when-some [{:keys [error result]} @last-result]
          (cond
            error [:div.red error]
            (react/isValidElement result) result
            'else (sv/inspect-paginated result)))])]
    (finally
     (j/call @!view :destroy))))

(defn doc? [node] (= (.-Document lezer-markdown/parser.nodeTypes) (.. node -type -id)))
(defn fenced-code? [node] (= (.-FencedCode lezer-markdown/parser.nodeTypes) (.. node -type -id)))

(defn markdown-block-ranges [state]
  (let [vblocks (volatile! [])]
    ;; ^:js {:keys [from to]} (.-visibleRanges view)
    ;; TODO: reimplement visible range
    (.. (syntaxTree state)
        (iterate (j/obj :enter
                        (fn [node]
                          (if (doc? node)
                            true ;; only enter into children of the top document
                            (do
                              (when (fenced-code? node)
                                (vswap! vblocks (fn [blocks]
                                                  (let [{:keys [to]} (peek blocks)]
                                                    (cond-> blocks
                                                      (and (not (zero? (n/start node)))
                                                           (or (not to) (not= (inc to) (n/start node))))
                                                      (conj {:from (or to 0)
                                                             :to (n/start node)
                                                             :type :markdown})
                                                      'always
                                                      (conj
                                                       {:from (n/start node)
                                                        :to (n/end node)
                                                        :type :code}))))))
                              false))))))
    (let [blocks @vblocks
          doc-end (.. state -doc -length)
          {:keys [to]} (peek blocks)]
      (cond-> blocks
        (not= doc-end (:to to))
        (conj {:from to :to doc-end :type :markdown})))))

(def dbg (atom nil))

(defn render-markdown [widget text view]
  (let [el (js/document.createElement "div")]
    (js/console.log :render-markdown/view view )
    (reset! dbg view)
    (rdom/render [:div.flex.rounded.border.m-2.p-2
                  [:button.rounded.bg-blue-300.text-white.text-lg.mr-2
                   ;; MAYBE: just on cursor enter
                   {:style {:width "25px" :height "25px"}
                    :on-click (fn [e]
                                (.preventDefault e)
                                (.dispatch view
                                           (j/obj :annotations
                                                  (.. Transaction -userEvent
                                                      (of {:type :edit-widget
                                                           :widget widget})))))} "✐"]


                  [:div.viewer-markdown [sv/inspect-paginated (v/with-viewer :markdown text)]]] el)
    el))

(defclass Widget
  (extends WidgetType)
  (constructor [this {:keys [from to state type]}]
    (j/assoc! this
              :from from :to to
              :toDOM (partial render-markdown this (.. ^js state -doc (sliceString from to)))
              :eq (fn [other]
                    (and (identical? (.-from this) (.-from other))
                         (identical? (.-to this) (.-to other)))))))

(defn widgets [state]
  (.set Decoration
        (into-array
         (mapv (fn [{:as block :keys [from to type]}]
                 (.. Decoration
                     (replace (j/obj :widget (Widget. (assoc block :state state))
                                     :block true))
                     (range from to)))
               (markdown-block-ranges state)))))

(def markdown-preview
  (.define StateField
           (j/obj :provide (fn [state] (.. EditorView -decorations (from state)))
                  :create (fn [state] (widgets state))
                  :update (fn [decos ^js tr]
                            (let [clicked-widget
                                  (when-not (.-docChanged tr)
                                    (->> tr .-annotations
                                         (some #(and (= :edit-widget (:type (.-value %)))
                                                     (:widget (.-value %))))))]
                              (js/console.log :update/tr tr :widget clicked-widget)
                              (cond clicked-widget
                                    (.update decos
                                             (j/obj :filter
                                                    (fn [_ _ ^js value]
                                                      (not (.. value -widget (eq clicked-widget))))))
                                    (.-docChanged tr) (widgets (.-state tr))
                                    'else decos))))))

;; syntax (an LRParser) + support (a set of extensions)
(def clojure-lang (LanguageSupport. (cm-clj/syntax)
                                    (.. cm-clj/default-extensions (slice 1))))

(defn markdown-editor [{:keys [doc editable? extensions] :or {editable? true}}]
  [:div {:ref (fn [^js el]
                (if-not el
                  (some-> el .-editorView .destroy)
                  (j/assoc! el :editorView
                            (EditorView. (j/obj :parent el
                                                :state (.create EditorState
                                                                (j/obj :doc (str/trim doc)
                                                                       :extensions (into-array
                                                                                    (cond-> [(syntaxHighlighting defaultHighlightStyle)
                                                                                             (foldGutter)
                                                                                             (.. EditorView -editable (of editable?))
                                                                                             (.of view/keymap cm-clj/complete-keymap)
                                                                                             theme
                                                                                             (markdown (j/obj :base markdownLanguage
                                                                                                              :defaultCodeLanguage clojure-lang))]
                                                                                      (seq extensions)
                                                                                      (concat extensions))))))))))}])

(defn samples []
  (into [:<>]
        (for [source ["(comment
  (fizz-buzz 1)
  (fizz-buzz 3)
  (fizz-buzz 5)
  (fizz-buzz 15)
  (fizz-buzz 17)
  (fizz-buzz 42))

(defn fizz-buzz [n]
  (condp (fn [a b] (zero? (mod b a))) n
    15 \"fizzbuzz\"
    3  \"fizz\"
    5  \"buzz\"
    n))"]]
          [editor source {:eval? true}])))

(defn linux? []
  (some? (re-find #"(Linux)|(X11)" js/navigator.userAgent)))

(defn mac? []
  (and (not (linux?))
       (some? (re-find #"(Mac)|(iPhone)|(iPad)|(iPod)" js/navigator.platform))))

(defn key-mapping []
  (cond-> {"ArrowUp" "↑"
           "ArrowDown" "↓"
           "ArrowRight" "→"
           "ArrowLeft" "←"
           "Mod" "Ctrl"}
    (mac?)
    (merge {"Alt" "⌥"
            "Shift" "⇧"
            "Enter" "⏎"
            "Ctrl" "⌃"
            "Mod" "⌘"})))

(defn render-key [key]
  (let [keys (into [] (map #(get ((memoize key-mapping)) % %) (str/split key #"-")))]
    (into [:span]
          (map-indexed (fn [i k]
                         [:<>
                          (when-not (zero? i) [:span " + "])
                          [:kbd.kbd k]]) keys))))

(defn key-bindings-table []
  [:table.w-full.text-sm
   [:thead
    [:tr.border-t
     [:th.px-3.py-1.align-top.text-left.text-xs.uppercase.font-normal.black-50 "Command"]
     [:th.px-3.py-1.align-top.text-left.text-xs.uppercase.font-normal.black-50 "Keybinding"]
     [:th.px-3.py-1.align-top.text-left.text-xs.uppercase.font-normal.black-50 "Alternate Binding"]
     [:th.px-3.py-1.align-top.text-left.text-xs.uppercase.font-normal.black-50 {:style {:min-width 290}} "Description"]]]
   (into [:tbody]
         (->> keymap/paredit-keymap*
              (merge (sci/keymap* "Alt"))
              (sort-by first)
              (map (fn [[command [{:keys [key shift doc]} & [{alternate-key :key}]]]]
                     [:<>
                      [:tr.border-t.hover:bg-gray-100
                       [:td.px-3.py-1.align-top.monospace.whitespace-nowrap [:b (name command)]]
                       [:td.px-3.py-1.align-top.text-right.text-sm.whitespace-nowrap (render-key key)]
                       [:td.px-3.py-1.align-top.text-right.text-sm.whitespace-nowrap (some-> alternate-key render-key)]
                       [:td.px-3.py-1.align-top doc]]
                      (when shift
                        [:tr.border-t.hover:bg-gray-100
                         [:td.px-3.py-1.align-top [:b (name shift)]]
                         [:td.px-3.py-1.align-top.text-sm.whitespace-nowrap.text-right
                          (render-key (str "Shift-" key))]
                         [:td.px-3.py-1.align-top.text-sm]
                         [:td.px-3.py-1.align-top]])]))))])

(defn ^:dev/after-load render []
  (rdom/render [samples] (js/document.getElementById "editor"))
  (.. (js/document.querySelectorAll "[clojure-mode]")
      (forEach #(when-not (.-firstElementChild %)
                  (rdom/render [editor (str/trim (.-innerHTML %))] %))))
  (let [mapping (key-mapping)]
    (.. (js/document.querySelectorAll ".mod,.alt,.ctrl")
        (forEach #(when-let [k (get mapping (.-innerHTML %))]
                    (set! (.-innerHTML %) k)))))
  (rdom/render [key-bindings-table] (js/document.getElementById "docs"))
  (rdom/render [:div.rounded-md.mb-0.text-sm.monospace.overflow-auto.relative.border.shadow-lg.bg-white
                [markdown-editor {:doc "# Hello Markdown

Lezer [mounted trees](https://lezer.codemirror.net/docs/ref/#common.MountedTree) allows to
have an editor with ~~mono~~ _mixed language support_.

```clojure
(defn the-answer
  \"to all questions\"
  []
  (inc 41))
```

## Todo
- [x] resolve **inner nodes**
- [ ] fix extra spacing when autoformatting after paredit movements
- [ ] fix errors when entering a newline
- [ ] fix extra space when entering a newline
- [ ] fix errors on Ctrl-K
- [ ] bring Clerk stylesheet in demo
- [ ] etc etc.
"}]] (js/document.getElementById "markdown-editor"))
  (rdom/render [:div.rounded-md.mb-0.text-sm.monospace.overflow-auto.relative.border.shadow-lg.bg-white
                [markdown-editor {:extensions [markdown-preview]
                                  :doc "# Hello Markdown

Lezer [mounted trees](https://lezer.codemirror.net/docs/ref/#common.MountedTree) allows to
have an editor with ~~mono~~ _mixed language support_.

```clojure
(defn the-answer
  \"to all questions\"
  []
  (inc 41))
```

## Todo
- [x] resolve **inner nodes**
- [ ] fix extra spacing when autoformatting
- [ ] fix errors when entering newline
- [ ] fix errors on Ctrl-K
- [ ] bring Clerk stylesheet in demo
- [ ] etc etc.
"}]] (js/document.getElementById "markdown-preview"))

  (when (linux?)
    (js/twemoji.parse (.-body js/document))))
