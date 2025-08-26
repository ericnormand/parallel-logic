(ns parallel-logic.core
  (:require [clojure.core.async :as async :refer [alts!!]]))

(def ^:dynamic *var-count* (atom 0))

(defmacro v [sym]
  `(symbol "v" ~(name sym)))

(defn var? [x]
  (and (symbol? x) (= "v" (namespace x))))

(defrecord ChannelPair [value-ch close-ch])

(defn chan []
  (->ChannelPair (async/chan) (async/chan)))

(defn close! [channel-pair]
  (async/close! (.value-ch channel-pair))
  (async/close! (.close-ch channel-pair)))

(defn put! [channel-pair val]
  (async/>!! (.value-ch channel-pair) val))

(defn read [channel-pair]
  (let [[val port] (alts!! [(.value-ch channel-pair)
                            (.close-ch channel-pair)])]
    (if (= port (.close-ch channel-pair))
      ::die
      val)))

(defn walk [term subst]
  (if (var? term)
    (if-let [val (get subst term)]
      (recur val subst)
      term)
    term))

(defn delta-unify [s u v]
  (let [u (walk u s)
        v (walk v s)]
    (cond
      (= u v) {}
      (and (var? u) (var? v))
      (if (neg? (compare (str u) (str v)))
        {v u} ; assign larger var to smaller var
        {u v})
      (var? u) {u v}
      (var? v) {v u}
      (and (sequential? u) (sequential? v))
      (loop [us u vs v delta {}]
        (cond
          (and (empty? us) (empty? vs)) delta
          (or (empty? us) (empty? vs)) nil ; different lengths
          :else
          (let [elem-delta (delta-unify (merge s delta) (first us) (first vs))]
            (if (nil? elem-delta)
              nil
              (recur (rest us) (rest vs) (merge delta elem-delta))))))
      :else nil)))

(defn unify [s d1 d2]
  (let [all-vars (set (concat (keys d1) (keys d2)))]
    (loop [vars all-vars result {}]
      (if (empty? vars)
        result
        (let [var (first vars)
              val1 (get d1 var ::not-found)
              val2 (get d2 var ::not-found)]
          (cond
            (= val2 ::not-found)
            (recur (rest vars) (assoc result var (walk val1 s)))

            (= val1 ::not-found)
            (recur (rest vars) (assoc result var (walk val2 s)))

            :else
            (let [walked-val1 (walk val1 s)
                  walked-val2 (walk val2 s)
                  unified-delta (delta-unify s walked-val1 walked-val2)]
              (if (nil? unified-delta)
                nil
                (recur (rest vars) (merge result (assoc unified-delta var walked-val1)))))))))))

(defn === [u v]
  (fn [s]
    (let [ch (chan)]
      (Thread/startVirtualThread
       (fn []
         (let [delta (delta-unify s u v)]
           (when delta
             (put! ch (merge s delta))))
         (close! ch)))
      ch)))

(defn disjoin
  ([]
   (fn [_s]
     (doto (chan)
       close!)))
  ([goal]
   goal)
  ([goal1 goal2]
   (fn [s]
     (let [ch (chan)]
       (Thread/startVirtualThread
        (fn []
          (let [s1 (goal1 s)
                s2 (goal2 s)]
            (loop [active-chs [(.value-ch s1) (.value-ch s2)]]
              (when (seq active-chs)
                (let [[val port] (async/alts!! (conj active-chs (.close-ch ch)))]
                  (cond
                    (= port (.close-ch ch))
                    nil

                    (nil? val)
                    (recur (remove #(= % port) active-chs))

                    :else
                    (do
                      (put! ch val)
                      (recur active-chs))))))
            (close! ch)
            (close! s1)
            (close! s2))))
       ch)))
  ([goal1 goal2 & more-goals]
   (reduce disjoin (disjoin goal1 goal2) more-goals)))

(defn conjoin
  ([]
   (fn [_s]
     (doto (chan)
       (put! {})
       (close!))))
  ([goal]
   goal)
  ([goal1 goal2]
   (fn [s]
     (let [result-ch (chan)]
       (Thread/startVirtualThread
        (fn []
          (let [s1 (goal1 s)
                s2 (goal2 s)]
            (try
              (loop [state {s1 {:open? true
                                :other s2
                                :acc #{}}
                            s2 {:open? true
                                :other s1
                                :acc #{}}}]
                (let [remaining-chans (->> state
                                           (filter #(:open? (val %)))
                                           (mapv key)
                                           (mapv #(.value-ch %)))]
                  (when (seq remaining-chans)
                    (let [[val port] (async/alts!! (conj remaining-chans
                                                         (.close-ch result-ch)))]
                      (cond
                        (= port (.close-ch result-ch))
                        nil

                        (nil? val)
                        (recur (assoc-in state [port :open?] false))

                        :else
                        (do
                          (doseq [v (get-in state [(get-in state [port :other]) :acc])]
                            (let [u (unify s val v)]
                              (when u
                                (put! result-ch u))))
                          (recur (update-in state [port :acc] conj val))))))))
              (catch Throwable t
                (println "Error:" t))
              (finally
                (close! result-ch)
                (close! s1)
                (close! s2))))))
       result-ch)))
  ([goal1 goal2 & more-goals]
   (reduce conjoin (conjoin goal1 goal2) more-goals)))

(defn fresh [f]
  (fn [s]
    (let [count (swap! *var-count* inc)
          new-var (symbol "v" (str count))]
      ((f new-var) s))))

(defn collect-timeout [t stream]
  (let [result-ch (chan)
        timeout-ch (async/timeout t)]
    (Thread/startVirtualThread
     (fn []
       (loop []
         (let [[val _port] (async/alts!! [(.value-ch stream) (.close-ch result-ch) timeout-ch])]
           (when val
             (put! result-ch val)
             (recur))))
       (close! result-ch)
       (close! stream)))
    result-ch))

(defn channel->set [ch]
  (loop [results #{}]
    (let [val (async/<!! (.value-ch ch))]
      (if (nil? val)
        (when (seq results) results)
        (recur (conj results val))))))

(defn runt [t q goal]
  (binding [*var-count* (atom 0)]
    (->> ((fresh (fn [v]
                   (fn [s]
                     (goal (assoc s q v)))))
          {})
         (collect-timeout t)
         (channel->set)
         (mapv #(walk q %)))))

(defmacro inv [g]
  `(fn [sc#]
     (~g sc#)))

(defmacro disj+
  ([]
   (disjoin))
  ([g]
   `(inv ~g))
  ([g & gs]
   `(disjoin (disj+ ~g) (disj+ ~@gs))))

(defn fives [x]
  (disj+ (=== 5 x)
         (=== x 5)))

(comment

  (runt 3000 (v q) (=== [(v q)] [8]))

  (runt 3000 (v q) (fives (v q))))

(defn conso [a d q]
  (=== (cons a d) q))

(runt 1000 (v q) (conso 1 '(2 3) (v q)))

(defn membero [v lst]
  (fresh
   (fn [head]
     (fresh
      (fn [tail]
        (conjoin
         (conso head tail lst)
         (disjoin
          (=== v head)
          (membero v tail))))))))

(comment
  (runt 1000 (v q) (conso 1 (v q) [1 2 3]))


  (runt 1000 (v q) (membero 4 (list 1 2 3 (v q) 5))))

;; todo: tests for termination
;; todo: test progress