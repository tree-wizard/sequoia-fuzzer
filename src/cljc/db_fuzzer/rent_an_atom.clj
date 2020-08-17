(ns db-fuzzer.rent-an-atom
  (:require
   [clojure.test :refer [deftest is run-tests] :as test]))

;; -------------------------------------------------------------------------------------------
;; Clojure doesn't support atoms inside a list that gets eval'd:
;; > (eval {:foo (atom 3)})
;; Syntax error compiling fn* at (*cider-repl code/db-fuzzer:localhost:39379(clj)*:5506:21).
;; Can't embed object in code, maybe print-dup not defined: clojure.lang.Atom@5c77e396
;;
;; Therefore, if we want shared mutable state inside the context, we'll need to keep the atom
;; outside of the context and do a lookup.
;; STATE-DB serves this purpose.
;; -------------------------------------------------------------------------------------------
(defonce STATE-DB (atom {:token-counter 0}))
(defn provision-shared-state! []
  (:token-counter
   (swap! STATE-DB
          (fn [state-db]
            (assoc state-db :token-counter (inc (:token-counter state-db)))))))

(defn read-shared-state [token]
  (get @STATE-DB token))

(defn reset-shared-state! [token v]
  (swap! STATE-DB
         (fn [state-db]
           (assoc state-db token v))))

(defn swap-shared-state! [token f]
  (swap! STATE-DB
          (fn [state-db]
            (assoc state-db token (f (get state-db token))))))

(defn swap-vals-shared-state! [token f]
  (vec (map #(get % token)
            (swap-vals! STATE-DB
                        (fn [state-db]
                          (assoc state-db token (f (get state-db token))))))))

(defn unprovision-shared-state! [token]
  (swap! STATE-DB #(dissoc % token)))

;;----------------------------------------
;; App-specific functions for rent-an-atom
;;----------------------------------------

(defn get-next-alias! [token]
  (keyword
   (str "a"
        (get-in (swap-shared-state!
                 token
                 (fn [state]
                   (let [cur-count (or (:alias-counter state) 0)]
                     (assoc state :alias-counter (inc cur-count)))))
                [token :alias-counter]))))

(defn try-get-munge-permission! [token]
  (let [[before after]
        (swap-vals-shared-state!
         token
         (fn [node]
           (if (> (or (:munge node) 0)
                  0)
             (assoc node :munge (dec (:munge node)))
             node)))]
    (not (= (:munge before) (:munge after)))))

(deftest test-try-get-munge-permission
  (let [token (provision-shared-state!)]
    (reset-shared-state! token {:munge 1})
    (is (= (try-get-munge-permission! token) true))
    (is (= (try-get-munge-permission! token) false))
    (unprovision-shared-state! token)))
