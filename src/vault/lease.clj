(ns vault.lease
  "Storage logic for Vault secrets and their associated leases."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    java.time.Instant))

; Generic secret backend responses look like:
; {
;   "lease_id": "",
;   "renewable": false,
;   "lease_duration": 2592000,
;   "data": { ... },
;   "wrap_info": null,
;   "warnings": null,
;   "auth": null
; }

; Dynamic secret backend responses look like:
; {
;   "request_id": "9b777b8c-20ab-49da-413a-cfc4aa8704c5",
;   "lease_id": "vagrant/service-db/creds/tenant-service/3c21206e-ab6d-1911-c4d8-d6ad439dff03",
;   "renewable": true,
;   "lease_duration": 900,
;   "data": { ... },
;   "wrap_info": null,
;   "warnings": null,
;   "auth": null
; }

; Renewal responses look like:
; {
;   "request_id": "765eeb84-e9fb-31d6-72f3-0c1dc60f7389",
;   "lease_id": "vagrant/service-db/creds/tenant-service/3c21206e-ab6d-1911-c4d8-d6ad439dff03",
;   "renewable": true,
;   "lease_duration": 900,
;   "data": null,
;   "wrap_info": null,
;   "warnings": null,
;   "auth": null
; }



;; ## Lease Management

(defn- now
  "Helper method to get the current time in epoch milliseconds."
  ^java.time.Instant
  []
  (Instant/now))


(defn- secret-lease
  "Adds extra fields and cleans up the secret lease info."
  [info]
  (cond->
    {:path (:path info)
     :lease-id (when-not (str/blank? (:lease-id info))
                 (:lease-id info))
     :lease-duration (:lease-duration info)
     :renewable (boolean (:renewable info))
     ::expiry (.plusSeconds (now) (:lease-duration info 60))}
    (:path info)
      (assoc :path (:path info))
    (:data info)
      (assoc :data (:data info)
             ::issued (now))
    (some? (:renew info))
      (assoc ::renew (boolean (:renew info)))
    (some? (:rotate info))
      (assoc ::rotate (boolean (:rotate info)))))


(defn expires-within?
  "Determines whether the lease expires within the given number of seconds."
  [lease duration]
  (-> (now)
      (.plusSeconds duration)
      (.isAfter (::expiry lease))))


(defn expired?
  "Determines whether the lease has expired."
  [lease]
  (expires-within? lease 0))


(defn renewable?
  "Determines whether a leased lease is renewable."
  [lease]
  (and (:renewable lease) (not (str/blank? (:lease-id lease)))))



;; ## Secret Storage

(defn new-store
  "Creates a new stateful store for leased secrets.

  This takes the form of a reference map of secret paths to lease data,
  including the secret data and any registered callbacks."
  []
  (atom {}))


(defn list-leases
  "Returns a list of lease information currently stored."
  [store]
  (mapv (fn [[k v]] (-> v (dissoc :data) (assoc :path k)))
        @store))


(defn lookup
  "Looks up the given secret path in the store. Returns the lease data, if
  present and not expired."
  [store path]
  (when-let [lease (get @store path)]
    (when-not (expired? lease)
      lease)))


(defn update!
  "Updates secret lease information in the store."
  [store info]
  (when-let [lease-id (:lease-id info)]
    (if-let [path (or (:path info)
                      (some->>
                        @store
                        (filter (comp #{lease-id} :lease-id val))
                        (first)
                        (key)))]
      (get (swap! store update path merge (secret-lease info)) path)
      (log/error "Cannot update lease with no matching store entry:" lease-id))))


(defn remove-path!
  "Removes a lease from the store by path."
  [store path]
  (swap! store dissoc path)
  nil)


(defn remove-lease!
  "Removes a lease from the store by id."
  [store lease-id]
  (swap! store (fn [data] (into {} (remove #(= lease-id (:lease-id (val %))) data))))
  nil)


(defn sweep!
  "Removes expired leases from the store."
  [store]
  (when-let [expired (seq (filter (comp expired? val) @store))]
    (log/warn "Expiring leased secrets:" (str/join \space (map key expired)))
    (apply swap! store dissoc (map key expired))
    store))


(defn renewable-leases
  "Returns a sequence of leases which are within `window` seconds of expiring,
  are renewable, and are marked for renewal."
  [store window]
  (->> (list-leases store)
       (filter ::renew)
       (filter renewable?)
       (filter #(expires-within? % window))))


(defn rotatable-leases
  "Returns a sequence of leases which are within `window` seconds of expiring,
  are not renewable, and are marked for rotation."
  [store window]
  (->> (list-leases store)
       (filter ::rotate)
       (remove renewable?)
       (filter #(expires-within? % window))))


(defn lease-watcher
  "Constructs a watch function which will call the given function with the
  secret info at a given path when the lease changes."
  [path watch-fn]
  (fn watch
    [_ _ old-state new-state]
    (let [old-info (get old-state path)
          new-info (get new-state path)]
      (when (not= (:lease-id old-info)
                  (:lease-id new-info))
        (watch-fn new-info)))))
