# cloud-itonami-isco-4321

Open Occupation Blueprint for **ISCO-08 4321**: Stock Clerks.

This repository designs a forkable OSS business for a sole-operator warehouse stock clerk: a pallet-mover and inventory-scanning robot performs the physical receiving, shelving, picking and counting work under a governor-gated actor.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a pallet-mover and inventory-scanning robot performs receiving, shelving, picking and cycle counts under an actor that proposes
actions and an independent **Warehouse Stock Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
operating near forklifts, loading docks or in aisles with staff present) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
purchase order + storage plan + pick list
        |
        v
Stock Advisor -> Warehouse Stock Governor -> pick/count, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `4321`). Required capabilities:

- :robotics
- :forms
- :telemetry
- :optimization
- :audit-ledger
- :bpmn

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section): a REAL, compiled
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
GENUINE human-in-the-loop interrupt/resume via checkpointing. Before
this, the repo had a real, substantive `warehouse-stock.governor`
(discrepancy-threshold + sku/bin-provenance checks) and
`warehouse-stock.store` (`record-event!`), but NO `actor.cljc` at
all — no StateGraph wiring whatsoever — `deps.edn` had an empty
top-level `:deps {}` (langgraph wasn't even declared as a dependency),
and NO `advisor.cljc` (no mock/LLM injection boundary existed). That
gap is now closed (`test/warehouse_stock/actor_test.kotoba`).

```text
:intake -> :advise -> :govern -> :decide -+-> :commit                       (:proceed)
                                           +-> :request-approval -> :commit   (:human-approval, interrupt-before)
                                           +-> :hold                         (:hold)
```

- `src/warehouse_stock/store.kotoba` — `Store` protocol + `MemStore` +
  `DatomicStore` (via [`kotoba-lang/langchain-store`](https://github.com/kotoba-lang/langchain-store),
  no hand-rolled EDN-blob codec): skus, bins, and the append-only
  audit ledger (`record-event!`/`events`/`events-of`). An event can
  only be recorded against a registered sku and a registered bin
  (sku/bin provenance). Both backends pass the same contract
  (`test/warehouse_stock/store_contract_test.kotoba`).
- `src/warehouse_stock/advisor.kotoba` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a receive/pick/count operation from
  a request; `llm-advisor` wraps a `langchain.model/ChatModel` — either
  way the advisor only ever produces a `:propose`-effect proposal,
  never a direct store write, and LLM parse failures always yield
  `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/warehouse_stock/governor.kotoba` — `WarehouseStockGovernor`:
  `assess` gates a proposal against the sku/bin env, wired as its own
  `:govern` node. Hard invariants force `:hold` (no sku/bin,
  direct-write instead of `:propose`, or a `:count` event whose
  discrepancy from the sku's `expected-qty` exceeds
  `discrepancy-threshold` at below `:high` safety-class); a large
  discrepancy always requires `:high`+ safety-class and thus
  `:human-approval` — a genuine `interrupt-before` node the compiled
  graph pauses at (checkpointed) and only resumes past on explicit
  human approval (`actor/approve!`, which re-enters the SAME compiled
  graph via its own `:request-approval -> :commit` edge); it can never
  be auto-corrected, only recorded and escalated. Low-confidence
  proposals also require human sign-off.
- `src/warehouse_stock/actor.kotoba` — `build-graph`, `run-request!`,
  `approve!`: the REAL `langgraph.graph/state-graph` wiring
  (`state-graph`/`add-node`/`add-edge`/`add-conditional-edges`/
  `compile-graph`). BOTH `:commit` and `:hold` durably append to the
  real audit ledger (`store/record-event!`) — previously
  `record-event!` was only ever called from `governor_test.clj`, never
  by a running actor (there was no actor).

```bash
clojure -M:lint       # clj-kondo, 0 errors
clojure -M:dev:test    # 14 tests / 63 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation) — the
4th `cloud-itonami-isco-*` occupation to reach that tier, after
`cloud-itonami-isco-6112`, `-2221` and `-7126` (ADR-2607012000).

## License

AGPL-3.0-or-later.
