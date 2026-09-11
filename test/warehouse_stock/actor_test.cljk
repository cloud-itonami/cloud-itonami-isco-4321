(ns warehouse-stock.actor-test
  "Integration tests for `warehouse-stock.actor` — builds the REAL
  compiled `langgraph.graph` and runs `run-request!`/`approve!`
  end-to-end through all three terminal routes (commit /
  escalate-then-approve / hold). This namespace did not exist before:
  neither did `warehouse-stock.actor` itself, or
  `warehouse-stock.advisor` — the repo had a real, substantive
  `warehouse-stock.governor` but NO StateGraph wiring and NO
  Advisor/mock injection boundary at all. These tests close that gap
  and prove the audit ledger (`warehouse-stock.store/record-event!`)
  is genuinely wired into the `:commit`/`:hold` nodes."
  (:require [clojure.test :refer [deftest is testing]]
            [warehouse-stock.actor :as actor]
            [warehouse-stock.advisor :as advisor]
            [warehouse-stock.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-sku! st {:sku-id "sku-1" :name "widget" :expected-qty 100})
    (store/register-bin! st {:bin-id "bin-1" :zone "A"})
    st))

(deftest run-request-commits-clean-proposal
  (testing "a valid, clean receive proposal runs the real compiled
            graph end to end and reaches :done"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:kind :receive :sku-id "sku-1" :bin-id "bin-1"
                                         :qty 10 :safety-class :low}
                                      {} "thread-commit-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :proceed (:decision (:verdict state))))
      (is (= 1 (count (:records state))))
      (is (= :committed (:status (first (:records state)))))
      (testing "the commit is genuinely durable in the store's real
                audit ledger (`warehouse-stock.store/record-event!`),
                not just the transient graph-state :records mirror"
        (let [ledger (store/events st)]
          (is (= 1 (count ledger)))
          (is (= :committed (:status (first ledger))))
          (is (= "sku-1" (:sku-id (first ledger)))))))))

(deftest run-request-holds-unregistered-sku
  (testing "an unregistered sku is a HARD violation -- the real graph
            routes to :hold and terminates, never :commit"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:kind :pick :sku-id "no-such-sku" :bin-id "bin-1" :qty 5}
                                      {} "thread-hold-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:decision (:verdict state))))
      (is (some #(= :no-sku-or-bin (:rule %)) (:violations (:verdict state))))
      (testing "the HARD violation is ALSO durably recorded to the real
                audit ledger by the :hold node -- there was previously
                no actor at all, so nothing wrote here"
        (let [ledger (store/events st)]
          (is (= 1 (count ledger)))
          (is (= :held (:status (first ledger))))
          (is (= "no-such-sku" (:sku-id (first ledger))))
          (is (seq (:violations (first ledger)))))))))

(deftest run-request-escalates-large-discrepancy-then-approve-commits
  (testing "a large count discrepancy at :high safety-class is
            :human-approval -- the real graph GENUINELY interrupts
            (checkpointed) at :request-approval and stops there; a
            human approve! resumes the SAME compiled graph and commits
            the event via the actual :request-approval -> :commit
            edge, not a hand-rolled parallel commit path"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          held (actor/run-request! g {:kind :count :sku-id "sku-1" :bin-id "bin-1"
                                       :qty 50 :safety-class :high}
                                    {} "thread-escalate-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (= :human-approval (:decision (:verdict held-state))))
      (is (empty? (:records held-state)) "not yet committed -- awaiting human sign-off")
      (is (empty? (store/events st)) "the ORIGINAL store has no event yet either")
      (let [approved (actor/approve! g "thread-escalate-1")
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= 1 (count (:records approved-state))))
        (is (= :committed (:status (first (:records approved-state)))))
        (testing "approve! also genuinely persists to the real audit ledger"
          (let [ledger (store/events st)]
            (is (= 1 (count ledger)))
            (is (= :committed (:status (first ledger))))
            (is (= :count (:kind (first ledger))))))))))

(deftest run-request-low-confidence-escalates
  (testing "confidence below the governor's floor (independent of
            discrepancy) also escalates through the real compiled
            graph -- mock-advisor honors an explicit :confidence
            override in the request so this is driven deterministically"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          held (actor/run-request! g {:kind :receive :sku-id "sku-1" :bin-id "bin-1"
                                       :qty 10 :confidence 0.2}
                                    {} "thread-low-conf-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= :human-approval (:decision (:verdict held-state))))
      (is (= :low-confidence (:reason (:verdict held-state))))
      (let [approved (actor/approve! g "thread-low-conf-1")]
        (is (= :done (:status approved)))
        (is (= 1 (count (store/events st))))))))
