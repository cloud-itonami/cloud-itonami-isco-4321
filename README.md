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

## Reference implementation

`src/warehouse_stock/{store,governor}.cljc` is a minimal but real
implementation of the Core Contract above (pure cljc, no external deps):

- `warehouse-stock.store` — `Store` protocol + `MemStore`: skus, bins,
  stock events (receive/pick/count). An event can only be recorded against
  a registered sku and a registered bin (sku/bin provenance).
- `warehouse-stock.governor` — `WarehouseStockGovernor`: `assess` gates a
  proposal against the sku/bin env. Hard invariants force `:hold`
  (no sku/bin, direct-write instead of `:propose`, or a `:count` event
  whose discrepancy from the sku's `expected-qty` exceeds
  `discrepancy-threshold` at below `:high` safety-class); a large
  discrepancy always requires `:high`+ safety-class and thus
  `:human-approval` — it can never be auto-corrected, only recorded and
  escalated; low-confidence proposals also escalate.

```bash
clojure -M:test   # 8 tests, 12 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation) — the
4th `cloud-itonami-isco-*` occupation to reach that tier, after
`cloud-itonami-isco-6112`, `-2221` and `-7126` (ADR-2607012000).

## License

AGPL-3.0-or-later.
