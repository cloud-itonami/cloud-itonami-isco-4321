(ns warehouse-stock.advisor
  "Stock Advisor — the proposal layer for the ISCO-08 4321 independent
  warehouse-stock actor. Proposes stock operations (receive, pick,
  count) from an operator/request, but NEVER writes to the store and
  has no notion of sku/bin provenance or discrepancy risk —
  `warehouse-stock.governor` is the independent system that decides
  whether a proposal may proceed (itonami actor pattern,
  ADR-2607011000).

  This namespace did not exist before this change — the repo had a
  real, substantive `warehouse-stock.governor` (discrepancy-threshold
  + sku/bin provenance checks) but no Advisor/mock injection boundary
  at all.

  A proposal is a map:
    {:kind :receive|:pick|:count
     :sku-id .. :bin-id .. :qty n
     :safety-class :none|:low|:medium|:high|:safety-critical
     :effect :propose   ; the advisor NEVER emits a raw store write
     :confidence 0.0-1.0}
  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold."
  )

(defprotocol Advisor
  (propose [advisor request context]
    "request -> proposal map. `:effect` is ALWAYS `:propose`."))

(defn mock-advisor
  "Default deterministic advisor: reads the request's declared
  :kind/:sku-id/:bin-id/:qty/:safety-class straight through (a
  stand-in for what an LLM would extract from an operator's free-text
  request), with a kind-derived confidence unless the request
  explicitly overrides `:confidence` (tests use this to drive the
  governor's low-confidence escalation path deterministically without
  relying on an unrecognized-kind trick)."
  []
  (reify Advisor
    (propose [_ request _context]
      (let [{:keys [kind sku-id bin-id qty safety-class confidence]} request]
        {:kind (or kind :receive)
         :sku-id sku-id
         :bin-id bin-id
         :qty (or qty 0)
         :safety-class (or safety-class :low)
         :effect :propose
         :confidence (or confidence
                          (case (or kind :receive)
                            :receive 0.9
                            :pick 0.85
                            :count 0.9
                            0.5))}))))

(defn llm-advisor
  "Advisor backed by an LLM (`langchain.model/ChatModel`). Kept
  decoupled from any concrete model — a real implementation calls the
  model and parses its response into a proposal map; on ANY parse
  failure it yields `:confidence 0.0` (forces escalation, never
  fabricated confidence). Placeholder swap point, same seam as
  `nco-admin.advisor/llm-advisor` (cloud-itonami-isco-0210) and
  `officer-admin.advisor/llm-advisor` (cloud-itonami-isco-0110)."
  [_chat-model]
  (reify Advisor
    (propose [_ _request _context]
      {:kind :unknown :effect :propose :confidence 0.0})))
