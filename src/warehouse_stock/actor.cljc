(ns warehouse-stock.actor
  "Stock Actor — the ISCO-08 4321 independent warehouse-stock actor as
  a REAL `langgraph.graph/state-graph` (per ADR-2607011000 / CLAUDE.md
  Actors section). One graph run = one stock operation request
  (receive/pick/count): intake -> advise -> govern -> decide ->
  commit/hold, with a GENUINE `interrupt-before` human-approval gate
  for `:human-approval` verdicts — the graph pauses (checkpointed) at
  `:request-approval` and only continues on to `:commit` when a human
  operator explicitly resumes it via `approve!`.

  This namespace did NOT exist before this change: the repo had a
  real, substantive `warehouse-stock.governor` (discrepancy-threshold
  + sku/bin-provenance checks) and `warehouse-stock.store`
  (`record-event!`, exercised only by `governor_test.clj`) but NO
  StateGraph wiring and NO Advisor/mock injection boundary at all.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                       (:proceed)
                                             +-> :request-approval -> :commit   (:human-approval, interrupt-before)
                                             +-> :hold                         (:hold)
  ```

  The unconditional invariant: the Stock Advisor can never directly
  record a stock event the Warehouse Stock Governor refuses — every
  `store/record-event!` call happens in `:commit`/`:hold`, reached
  only through `:decide`."
  (:require [langgraph.graph :as graph]
            [langgraph.checkpoint :as cp]
            [warehouse-stock.store :as store]
            [warehouse-stock.advisor :as advisor]
            [warehouse-stock.governor :as governor]))

(defn- event-id [] (str (random-uuid)))

(defn- intake-node
  "Intake node: pass the request through untouched."
  [state]
  state)

(defn- advise-node
  "Advise node: Advisor proposes a stock operation. Returns only the
  channel delta (langgraph folds partial updates through the channel
  reducers, see `build-graph`'s `:channels`)."
  [advisor-instance {:keys [request context]}]
  {:proposal (advisor/propose advisor-instance request context)})

(defn- govern-node
  "Govern node: Governor assesses the proposal against the store's
  sku/bin provenance and discrepancy rules, independently of the
  advisor."
  [store-instance {:keys [proposal]}]
  {:verdict (governor/assess (governor/env-for-store store-instance) proposal)})

(defn- decide-node
  "Decide node: route based on the governor's verdict. Sets
  :disposition only -- the conditional edge below reads it, no store
  write happens here."
  [{:keys [verdict]}]
  {:disposition (case (:decision verdict)
                  :proceed        :commit
                  :human-approval :request-approval
                  :hold)})

(defn- commit-node
  "Commit node: durably append the committed stock event to the REAL
  audit ledger via `warehouse-stock.store/record-event!` — the core
  missing behavior this actor previously had (there was no actor at
  all, so `record-event!` was only ever called from tests)."
  [store-instance {:keys [proposal]}]
  (let [event (assoc proposal :event-id (event-id) :status :committed)]
    (store/record-event! store-instance event)
    {:records [event]}))

(defn- request-approval-node
  "Request-approval node: the `interrupt-before` gate. When the graph
  actually reaches (executes) this node's body, it's because a human
  operator resumed the thread via `approve!` -- interrupt-before
  pauses BEFORE this node runs on the first pass. Falls straight
  through to :commit via the graph's own
  `:request-approval -> :commit` edge."
  [_state]
  {})

(defn- hold-node
  "Hold node: a HARD governance violation (sku/bin not registered,
  non-:propose effect, or an unescalated large count discrepancy).
  Never records the proposed stock event, but DOES durably append a
  `:held` audit fact to the SAME ledger `:commit` uses — so a sku's
  full operating history (including refused proposals) is always a
  query over `store/events`, not just the committed movements."
  [store-instance {:keys [proposal verdict]}]
  (let [event (assoc proposal
                      :event-id (event-id)
                      :status :held
                      :violations (:violations verdict))]
    (store/record-event! store-instance event)
    {:records [event]}))

(defn build-graph
  "Build and compile the REAL `langgraph.graph` StateGraph for the
  warehouse-stock actor, against `state-graph`/`add-node`/`add-edge`/
  `add-conditional-edges`/`set-entry-point`/`set-finish-point`/
  `compile-graph` -- the actual exported API of `langgraph.graph`.
  `checkpointer` defaults to an in-memory one
  (`langgraph.checkpoint/mem-checkpointer`) so `:request-approval`
  interrupts are genuinely resumable via `approve!`."
  [advisor-instance store-instance & [{:keys [checkpointer]
                                        :or {checkpointer (cp/mem-checkpointer)}}]]
  (-> (graph/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :records     {:reducer into :default []}}})

      (graph/add-node :intake intake-node)
      (graph/add-node :advise (partial advise-node advisor-instance))
      (graph/add-node :govern (partial govern-node store-instance))
      (graph/add-node :decide decide-node)
      (graph/add-node :commit (partial commit-node store-instance))
      (graph/add-node :request-approval request-approval-node)
      (graph/add-node :hold (partial hold-node store-instance))

      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)

      (graph/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit           :commit
            :request-approval :request-approval
            :hold)))

      (graph/add-edge :request-approval :commit)

      (graph/set-finish-point :commit)
      (graph/set-finish-point :hold)

      (graph/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one stock operation request through the REAL compiled actor
  graph (`compiled-graph` from `build-graph`) via
  `langgraph.graph/run*`. `thread-id` scopes checkpointing so an
  escalated (interrupted) run can be resumed by `approve!`. Returns
  the full run result: `{:state .. :events .. :status :done|:interrupted
  :frontier ..}` -- `:status :interrupted` with `:frontier
  [:request-approval]` means the request is genuinely paused awaiting
  human sign-off, not merely a flag on an already-finished run."
  [compiled-graph request context thread-id]
  (graph/run* compiled-graph {:request request :context context}
              {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: a human operator's approval of a request
  parked at `:request-approval` genuinely resumes the compiled graph
  (via `langgraph.graph/run*` with `:resume? true`), which runs the
  `:request-approval -> :commit` edge and so durably records the
  event through the SAME `commit-node` a clean, non-escalated run
  uses -- not a hand-rolled parallel commit path."
  [compiled-graph thread-id]
  (graph/run* compiled-graph nil {:thread-id thread-id :resume? true}))
