(ns parallel-logic.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!!]]
            [parallel-logic.core :as sut :refer [v]]))

(defn channel->set [ch]
  (loop [results #{}]
    (let [val (<!! ch)]
      (println "Channel value:" val)
      (if (nil? val)
        (when (seq results) results)
        (recur (conj results val))))))

(deftest delta-unify-basic-test
  (testing "basic unification cases"
    (is (= {(v x) 5} (sut/delta-unify {} (v x) 5)))
    (is (= {} (sut/delta-unify {} 5 5)))
    (is (= nil (sut/delta-unify {} 5 7)))
    (is (= {(v y) (v x)} (sut/delta-unify {} (v x) (v y))))))

(deftest delta-unify-with-substitutions-test
  (testing "with existing substitutions"
    (is (= {} (sut/delta-unify {(v x) 5} (v x) 5)))
    (is (= nil (sut/delta-unify {(v x) 5} (v x) 7)))
    (is (= {(v y) 5} (sut/delta-unify {(v x) (v y)} (v y) 5)))
    (is (= {(v z) (v y)} (sut/delta-unify {(v x) (v y)} (v z) (v y))))))

(deftest delta-unify-collections-test
  (testing "collection unification"
    (is (= {(v x) 1 (v y) 2} (sut/delta-unify {} [(v x) (v y)] [1 2])))
    (is (= nil (sut/delta-unify {} [(v x) (v x)] [1 2])))
    (is (= {} (sut/delta-unify {} [1 2] [1 2])))
    (is (= nil (sut/delta-unify {} [1 2] [1 2 3])))))

(deftest delta-unify-commutative-test
  (testing "commutativity of delta-unify"
    (is (= (sut/delta-unify {} (v x) 5) (sut/delta-unify {} 5 (v x))))
    (is (= (sut/delta-unify {} (v x) (v y)) (sut/delta-unify {} (v y) (v x))))))

(deftest unify-test
  (testing "merging two deltas"
    (is (= {(v x) 1 (v y) 2} (sut/unify {} {(v x) 1} {(v y) 2})))
    (is (= {(v x) 1} (sut/unify {} {(v x) 1} {(v x) 1})))
    (is (= nil (sut/unify {} {(v x) 1} {(v x) 2})))
    (is (= {(v x) 5 (v y) 5}
           (sut/unify {(v z) 5} {(v x) (v z)} {(v y) (v z)})))))

(deftest unify-commutative-test
  (testing "commutativity of unify"
    (is (= (sut/unify {} {(v x) 1} {(v y) 2})
           (sut/unify {} {(v y) 2} {(v x) 1})))
    (is (= (sut/unify {} {(v x) (v z)} {(v y) 5})
           (sut/unify {} {(v y) 5} {(v x) (v z)})))
    (is (= (sut/unify {(v z) 3} {(v x) (v z)} {(v y) (v z)})
           (sut/unify {(v z) 3} {(v y) (v z)} {(v x) (v z)})))))

(deftest v-macro-test
  (testing "variable constructor macro"
    (is (= 'v/x (v x)))
    (is (= 'v/y (v y)))
    (is (= 'v/foo (v foo)))
    (is (symbol? (v x)))
    (is (= "v" (namespace (v x))))
    (is (= "x" (name (v x))))))

(deftest var?-test
  (testing "variable predicate"
    (is (true? (sut/var? (v x))))
    (is (true? (sut/var? (v foo))))
    (is (false? (sut/var? 'x)))
    (is (false? (sut/var? 5)))
    (is (false? (sut/var? "string")))
    (is (false? (sut/var? :keyword)))
    (is (false? (sut/var? [1 2 3])))
    (is (false? (sut/var? 'other/namespaced)))))

(deftest ==-basic-test
  (testing "basic == goal functionality"
    (is (= #{{}} (channel->set ((sut/== 5 5) {}))))
    (is (= nil (channel->set ((sut/== 5 7) {}))))
    (is (= #{{(v x) 5}} (channel->set ((sut/== (v x) 5) {}))))
    (is (= #{{(v y) (v x)}} (channel->set ((sut/== (v x) (v y)) {}))))
    (is (= #{{}} (channel->set ((sut/== (v x) 5) {(v x) 5}))))
    (is (= nil (channel->set ((sut/== (v x) 7) {(v x) 5}))))))

(deftest ==-commutative-test
  (testing "commutativity of == goal"
    (is (= (channel->set ((sut/== 5 (v x)) {}))
           (channel->set ((sut/== (v x) 5) {}))))
    (is (= (channel->set ((sut/== (v x) (v y)) {}))
           (channel->set ((sut/== (v y) (v x)) {}))))
    (is (= (channel->set ((sut/== [1 (v x)] [1 2]) {}))
           (channel->set ((sut/== [1 2] [1 (v x)]) {}))))))

(deftest disj-test
  (testing "disjunction goal"
    (testing "zero arguments - empty stream"
      (is (= nil (channel->set ((sut/disjoin) {})))))

    (testing "one argument - return goal stream directly"
      (is (= #{{(v x) 5}} (channel->set ((sut/disjoin (sut/== (v x) 5)) {}))))
      (is (= nil (channel->set ((sut/disjoin (sut/== 5 7)) {})))))

    (testing "two arguments - merge streams"
      (is (= #{{(v x) 1} {(v x) 2}} (channel->set ((sut/disjoin (sut/== (v x) 1) (sut/== (v x) 2)) {}))))
      (is (= #{{(v x) 1}} (channel->set ((sut/disjoin (sut/== (v x) 1) (sut/== 5 7)) {}))))
      (is (= #{{(v x) 2}} (channel->set ((sut/disjoin (sut/== 5 7) (sut/== (v x) 2)) {}))))
      (is (= nil (channel->set ((sut/disjoin (sut/== 5 7) (sut/== 3 4)) {})))))

    (testing "three arguments - recursive merge"
      (is (= #{{(v x) 1} {(v x) 2} {(v x) 3}}
             (channel->set ((sut/disjoin (sut/== (v x) 1) (sut/== (v x) 2) (sut/== (v x) 3)) {})))))))

(deftest conj-test
  (testing "conjunction goal"
    (testing "zero arguments - succeeds with empty delta"
      (is (= #{{}} (channel->set ((sut/conjoin) {})))))

    (testing "one argument - return goal stream directly"
      (is (= #{{(v x) 5}} (channel->set ((sut/conjoin (sut/== (v x) 5)) {}))))
      (is (= nil (channel->set ((sut/conjoin (sut/== 5 7)) {})))))

    (testing "two arguments - unify deltas"
      (is (= #{{(v x) 1 (v y) 2}}
             (channel->set ((sut/conjoin (sut/== (v x) 1)
                                         (sut/== (v y) 2))
                            {}))))
      (is (= #{{(v x) 5}} (channel->set ((sut/conjoin (sut/== (v x) 5) (sut/== (v x) 5)) {}))))
      (is (= nil (channel->set ((sut/conjoin (sut/== (v x) 1) (sut/== (v x) 2)) {}))))
      (is (= nil (channel->set ((sut/conjoin (sut/== 5 7) (sut/== (v x) 2)) {})))))

    (testing "three arguments - recursive unification"
      (is (= #{{(v x) 1 (v y) 2 (v z) 3}}
             (channel->set ((sut/conjoin (sut/== (v x) 1) (sut/== (v y) 2) (sut/== (v z) 3)) {}))))
      (is (= #{{(v x) 5}}
             (channel->set ((sut/conjoin (sut/== (v x) 5) (sut/== (v x) 5) (sut/== (v x) 5)) {}))))
      (is (= nil
             (channel->set ((sut/conjoin (sut/== (v x) 1) (sut/== (v x) 2) (sut/== (v x) 3)) {})))))))

;; todo: test that there is fairness

;; todo: test disj is commutative
;; todo: test conj is commutative


(comment
  (channel->set ((sut/conjoin (sut/conjoin) (sut/conjoin)) {}))
  (channel->set ((sut/conjoin (sut/disjoin) (sut/conjoin)) {}))
  (channel->set ((sut/disjoin (sut/disjoin) (sut/conjoin)) {}))
  (channel->set ((sut/disjoin (sut/disjoin) (sut/disjoin)) {})))