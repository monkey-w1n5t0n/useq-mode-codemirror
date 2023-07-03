(ns nextjournal.clojure-mode.extensions.eval-region
  (:require
   ["@codemirror/state" :as state :refer [StateEffect StateField]]
   ["@codemirror/view" :as view :refer [EditorView Decoration keymap]]
   ["w3c-keyname" :refer [keyName]]
   [applied-science.js-interop :as j]
   [nextjournal.clojure-mode.util :as u]
   [nextjournal.clojure-mode.node :as n]
   [clojure.string :as str]))

(defn uppermost-edge-here
  "Returns node or its highest ancestor that starts or ends at the cursor position."
  [pos node]
  (or (->> (iterate n/up node)
           (take-while (every-pred (complement n/top?)
                                   #(or (= pos (n/end %) (n/end node))
                                        (= pos (n/start %) (n/start node)))))
           (last))
      node))

(defn main-selection [state]
  (-> (j/call-in state [:selection :asSingle])
      (j/get-in [:ranges 0])))

(defn node-at-cursor
  ([state] (node-at-cursor state (j/get (main-selection state) :from)))
  ([^js state from]
   (some->> (n/nearest-touching state from -1)
            (#(when (or (n/terminal-type? (n/type %))
                        (<= (n/start %) from)
                        (<= (n/end %) from))
                (cond-> %
                  (or (n/top? %)
                      (and (not (n/terminal-type? (n/type %)))
                           (< (n/start %) from (n/end %))))
                  (-> (n/children from -1) first))))
            (uppermost-edge-here from)
            (n/balanced-range state))))

(defn top-level-node [state]
  (->> (n/nearest-touching state (j/get (main-selection state) :from) -1)
       (iterate n/up)
       (take-while (every-pred identity (complement n/top?)))
       last))

;; Modifier field
(defonce modifier-effect (.define StateEffect))
(defonce modifier-field
         (.define StateField
                  (j/lit {:create (constantly {})
                          :update (fn [value ^js tr]
                                    (or (some-> (first (filter #(.is ^js % modifier-effect) (.-effects tr)))
                                                (j/get :value))
                                        value))})))

(defn get-modifier-field [^js state] (.field state modifier-field))

(j/defn set-modifier-field! [^:js {:as view :keys [dispatch state]} value]
  (dispatch #js{:effects (.of modifier-effect value)
                :userEvent "evalregion"}))

(j/defn mark [spec ^:js {:keys [from to]}]
  (-> (.mark Decoration spec)
      (.range from to)))

(defn single-mark [spec range]
  (.set Decoration #js[(mark spec range)]))


;; TODO - parameterize mark colors
(defonce mark:none (j/lit {:attributes {:style "background-color: transparent;"}}))
(defonce mark:selected (j/lit {:attributes {:style "background-color: rgba(0, 243, 255, 0.14);"}}))

(defn cursor-range [^js state]
  (if (.. state -selection -main -empty)
    (node-at-cursor state)
    (.. state -selection -main)))

(defonce region-field
  (.define StateField
           (j/lit
            {:create (constantly (.-none Decoration))
             :update (j/fn [_value ^:js {:keys [state]}]
                       (let [{:strs [RegionModifier Shift Enter]} (get-modifier-field state)]
                         (if-some [[spec range] (when (or (n/embedded? state) (n/within-program? state))
                                                  (cond (and RegionModifier Shift) [mark:selected (top-level-node state)]
                                                        (and Enter Shift) [mark:selected (top-level-node state)]
                                                        Shift [mark:none (j/lit {:from 0 :to (.. state -doc -length)})]
                                                        RegionModifier (when-let [range (or (u/guard (main-selection state) (complement (j/get :empty)))
                                                                                 (cursor-range state))]
                                                              [mark:selected range])))]
                           (single-mark spec range)
                           (.-none Decoration))))})))


(defn get-region-field [^js state] (.field state region-field))

(defn current-range [^js state]
  (or (some-> (get-region-field state)
              (j/call :iter)
              (u/guard #(j/get % :value)))
      (.. state -selection -main)))

(defn current-str [state]
  (u/range-str state (current-range state)))

(defn modifier-extension
  "Maintains modifier-state-field, containing a map of {<modifier> true}, including Enter."
  [{:keys [modifier
           on-enter]
    :or {modifier "Alt"}}]
  (let [handle-enter (j/fn handle-enter [^:js {:as view :keys [state]}]
                       (when on-enter
                         (let [mods (get-modifier-field state)]
                           (set-modifier-field! view (assoc mods "Enter" true))
                           (on-enter (u/range-str state (current-range state)))
                           (when (seq mods)
                             true))))
        handle-key-event (j/fn [^:js {:as event :keys [altKey shiftKey metaKey controlKey type]}
                                ^:js {:as view :keys [state]}]
                           (let [prev (get-modifier-field state)
                                 next (cond-> {}
                                        altKey (assoc "Alt" true)
                                        shiftKey (assoc "Shift" true)
                                        metaKey (assoc "Meta" true)
                                        controlKey (assoc "Control" true)
                                        (and (= "keydown" type)
                                             (= "Enter" (keyName event)))
                                        (assoc "Enter" true))
                                 next (if (get next modifier)
                                        (assoc next "RegionModifier" true)
                                        next)]
                             (when (not= prev next)
                               (set-modifier-field! view next))
                             false))
        handle-backspace (j/fn [^:js {:as view :keys [state dispatch]}]
                           (j/let [^:js {:keys [from to]} (current-range state)]
                             (when (not= from to)
                               (dispatch (j/lit {:changes {:from from :to to :insert ""}
                                                 :annotations (u/user-event-annotation "delete")})))
                             true))]
    #js[modifier-field
        (.of keymap
             (j/lit [{:key   (str modifier "-Enter")
                      :shift handle-enter
                      :run   handle-enter}
                     {:key   "Enter"
                      :shift handle-enter
                      :run   handle-enter}
                     {:key (str modifier "-Backspace")
                      :run handle-backspace
                      :shift handle-backspace}]))
        (.domEventHandlers view/EditorView
                           #js{:keydown handle-key-event
                               :keyup   handle-key-event})]))

(defn extension [{:keys [modifier]
                  :or   {modifier "Alt"}}]
  #js[(modifier-extension modifier)
      region-field
      (.. EditorView -decorations (from region-field))])

(defn cursor-node-string [^js state]
  (u/guard (some->> (node-at-cursor state)
                    (u/range-str state))
           (complement str/blank?)))

(defn top-level-string [^js state]
  (u/guard (some->> (top-level-node state)
                    (u/range-str state))
           (complement str/blank?)))
