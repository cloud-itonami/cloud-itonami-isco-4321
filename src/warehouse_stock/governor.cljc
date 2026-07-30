(ns warehouse-stock.governor
  "WarehouseStockGovernor — the independent safety/traceability layer for the
  ISCO-08 4321 independent warehouse-stock actor. The Stock Advisor proposes
  actions (receive, pick, count); it has no notion of sku/bin provenance or
  discrepancy risk, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD — the itonami-actor pattern (independent
  Governor gates a proposing actor) applied to this occupation.

  Charter (mirrors ADR-2607011000 robotics premise + ADR-2607012000
  cloud-itonami-isco): the actor never dispatches a robot action or writes an
  operating record the governor refuses. A count with a large discrepancy
  from the expected quantity ALWAYS requires human sign-off — it can never
  be auto-corrected, only recorded and escalated.

  HARD invariants for :stock/propose:
    1. SKU/bin provenance — a stock event must reference a registered sku
       and a registered bin.
    2. No-actuation        — the proposal must not directly mutate an event
       record outside the record-event! path (effect must be :propose,
       never a raw store write).
    3. Discrepancy escalation — a :count event whose counted qty differs
       from the sku's expected-qty by more than `discrepancy-threshold`
       always requires :high or higher safety-class, forcing human
       sign-off; it is never auto-approved regardless of confidence.
  SOFT:
    4. Confidence floor → escalate."
  (:require [warehouse-stock.store :as store]))

(def confidence-floor 0.6)
(def discrepancy-threshold 5)
(def safety-classes [:none :low :medium :high :safety-critical])

(defn- safety-rank [safety-class]
  (let [idx (.indexOf safety-classes safety-class)]
    (if (neg? idx) 0 idx)))

(defn- abs-diff [n] (if (neg? n) (- n) n))

(defn- discrepant? [expected-qty proposal]
  (and (= :count (:kind proposal))
       (number? expected-qty)
       (number? (:qty proposal))
       (> (abs-diff (- (:qty proposal) expected-qty)) discrepancy-threshold)))

(defn- hard-violations [{:keys [sku-fn bin-fn]} proposal]
  (let [{:keys [sku-id bin-id safety-class effect]} proposal
        found-sku (sku-fn sku-id)
        found-bin (bin-fn bin-id)]
    (cond-> []
      (or (nil? found-sku) (nil? found-bin))
      (conj {:rule :no-sku-or-bin :detail (str "未登録 sku/bin " sku-id "/" bin-id)})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and found-sku
           (discrepant? (:expected-qty found-sku) proposal)
           (< (safety-rank (or safety-class :none)) (safety-rank :high)))
      (conj {:rule :discrepancy-escalation
             :detail (str "expected-qty との乖離が discrepancy-threshold("
                           discrepancy-threshold ") を超過 — :high 以上の safety-class が必須")}))))

(defn assess
  "Assess a proposal against `env` (a map with `:sku-fn`/`:bin-fn` lookups,
  decoupled from any concrete Store so this stays pure). Returns
  `{:decision :proceed|:hold|:human-approval :violations [...] :confidence n}`."
  [env proposal]
  (let [violations (hard-violations env proposal)
        safety-class (or (:safety-class proposal) :none)
        confidence (or (:confidence proposal) 0.0)]
    (cond
      (seq violations)
      {:decision :hold :violations violations :confidence confidence}

      (>= (safety-rank safety-class) (safety-rank :high))
      {:decision :human-approval :violations [] :confidence confidence}

      (< confidence confidence-floor)
      {:decision :human-approval :violations [] :confidence confidence
       :reason :low-confidence}

      :else
      {:decision :proceed :violations [] :confidence confidence})))

(defn env-for-store
  "Build the decoupled env map `assess` needs from a concrete
  `warehouse-stock.store/Store` implementation."
  [store]
  {:sku-fn #(store/sku store %)
   :bin-fn #(store/bin store %)})
