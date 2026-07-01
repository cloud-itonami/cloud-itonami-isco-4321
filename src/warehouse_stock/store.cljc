(ns warehouse-stock.store
  "SSoT for the ISCO-08 4321 independent warehouse-stock sole-proprietor
  actor, behind a `Store` protocol so the backend is a swap (MemStore
  default ‖ a real Datomic/kotoba-server backend, per the itonami actor
  pattern).

  Domain = independent warehouse stock operations:

    sku    — a stocked product (skuId, name, expectedQty)
    bin    — a storage location (binId, zone)
    event  — a stock movement (eventId, skuId, binId, kind
              #{:receive :pick :count}, qty)

  The append-only records are the operating ledger: a stock event must
  reference a registered sku and a registered bin, and events are never
  mutated in place, only appended.")

(defprotocol Store
  (sku [st sku-id])
  (bin [st bin-id])
  (events-of [st sku-id])
  (register-sku! [st sku])
  (register-bin! [st bin])
  (record-event! [st event]))

(defrecord MemStore [state]
  Store
  (sku [_ sku-id]
    (get-in @state [:skus sku-id]))
  (bin [_ bin-id]
    (get-in @state [:bins bin-id]))
  (events-of [_ sku-id]
    (filter #(= sku-id (:sku-id %)) (:events @state)))
  (register-sku! [_ sku]
    (swap! state assoc-in [:skus (:sku-id sku)] sku))
  (register-bin! [_ bin]
    (swap! state assoc-in [:bins (:bin-id bin)] bin))
  (record-event! [_ event]
    (swap! state update :events (fnil conj []) event)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:skus {} :bins {} :events []} seed)))))
