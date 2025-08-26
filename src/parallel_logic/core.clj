(ns parallel-logic.core
  (:require [clojure.core.async :as async :refer [chan close! >!! <!! alts!! put!]]))

(defmacro v [sym]
  `(symbol "v" ~(name sym)))

(defn var? [x]
  (and (symbol? x) (= "v" (namespace x))))

(defn walk [term subst]
  (if (var? term)
    (if-let [val (get subst term)]
      (walk val subst)
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
    (let [result-ch (chan)]
      (Thread/startVirtualThread
       (fn []
         (let [delta (delta-unify s u v)]
           (when delta
             (>!! result-ch delta))
           (close! result-ch))))
      result-ch)))

(defn disjoin
  ([]
   (fn [_s]
     (doto (chan)
       close!)))
  ([goal]
   goal)
  ([goal1 goal2]
   (fn [s]
     (let [result-ch (chan)]
       (Thread/startVirtualThread
        (fn []
          (loop [active-chs [(goal1 s) (goal2 s)]]
            (when (seq active-chs)
              (let [[val port] (async/alts!! active-chs)]
                (if (nil? val)
                  (recur (remove #(= % port) active-chs))
                  (do (>!! result-ch val)
                      (recur active-chs))))))
          (close! result-ch)))
       result-ch)))
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
                                           (mapv key))]
                  (when (seq remaining-chans)
                    (let [[val port] (async/alts!! remaining-chans)]
                      (if (nil? val)
                        (recur (assoc-in state [port :open?] false))

                        (do
                          (doseq [v (get-in state [(get-in state [port :other]) :acc])]
                            (let [u (unify s val v)]
                              (when u
                                (>!! result-ch u))))
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

