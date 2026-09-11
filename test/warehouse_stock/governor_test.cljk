(ns warehouse-stock.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [warehouse-stock.store :as store]
            [warehouse-stock.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-sku! st {:sku-id "sku-1" :name "widget" :expected-qty 100})
    (store/register-bin! st {:bin-id "bin-1" :zone "A"})
    st))

(deftest proceeds-on-clean-receive
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :receive :sku-id "sku-1" :bin-id "bin-1" :qty 10
                   :safety-class :low :effect :propose :confidence 0.9}]
    (is (= :proceed (:decision (governor/assess env proposal))))))

(deftest holds-on-unregistered-sku
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :pick :sku-id "no-such-sku" :bin-id "bin-1" :qty 5
                   :safety-class :low :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-sku-or-bin (:rule %)) (:violations result)))))

(deftest holds-on-no-actuation-violation
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :pick :sku-id "sku-1" :bin-id "bin-1" :qty 5
                   :safety-class :low :effect :direct-write :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-actuation (:rule %)) (:violations result)))))

(deftest holds-on-large-discrepancy-without-high-safety-class
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :count :sku-id "sku-1" :bin-id "bin-1" :qty 50
                   :safety-class :medium :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :discrepancy-escalation (:rule %)) (:violations result)))))

(deftest human-approval-on-large-discrepancy-with-high-safety-class
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :count :sku-id "sku-1" :bin-id "bin-1" :qty 50
                   :safety-class :high :effect :propose :confidence 0.9}]
    (is (= :human-approval (:decision (governor/assess env proposal))))))

(deftest proceeds-on-small-discrepancy-count
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :count :sku-id "sku-1" :bin-id "bin-1" :qty 98
                   :safety-class :low :effect :propose :confidence 0.9}]
    (is (= :proceed (:decision (governor/assess env proposal))))))

(deftest human-approval-on-low-confidence
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :receive :sku-id "sku-1" :bin-id "bin-1" :qty 10
                   :safety-class :none :effect :propose :confidence 0.2}
        result (governor/assess env proposal)]
    (is (= :human-approval (:decision result)))
    (is (= :low-confidence (:reason result)))))

(deftest store-records-append-only
  (let [st (fresh-store)]
    (store/record-event! st {:event-id "e1" :sku-id "sku-1" :bin-id "bin-1" :kind :receive :qty 10})
    (store/record-event! st {:event-id "e2" :sku-id "sku-1" :bin-id "bin-1" :kind :pick :qty -3})
    (is (= 2 (count (store/events-of st "sku-1"))))))

(deftest a-proposal-without-confidence-does-not-proceed
  (testing "確信度を言っていない提案は、確信していると言っていないので auto-proceed
            させない。この既定は 2026-07-30 まで 1.0 で、:confidence を持たない提案が
            :proceed していた（ADR-2607309100）。fleet の boolean 方言 346 件はすべて
            0.0 既定で、うち isco-5419 はそれを明示的にテストしている。"
    (let [st (fresh-store)
          env (governor/env-for-store st)
          proposal {:kind :receive :sku-id "sku-1" :bin-id "bin-1" :qty 10 :safety-class :low :effect :propose}
          result (governor/assess env proposal)]
      (is (= 0.0 (:confidence result))
          "欠落した :confidence は 0.0 であって 1.0 ではない")
      (is (not= :proceed (:decision result))
          "確信度不明の提案が自動で通ってはならない"))))
