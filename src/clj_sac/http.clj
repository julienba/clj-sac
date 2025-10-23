(ns clj-sac.http
  (:require
   [hato.client :as http]
   [cheshire.core :as json]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [clojure.core.async :as a]
   [clojure.string :as string]))

(defn validate-schema! [error-msg schema-or-fn data]
  (if (vector? schema-or-fn)
    (if (m/validate schema-or-fn data)
      data
      (throw (ex-info error-msg
                      {:type :validation-error
                       :data data
                       :message (me/humanize (m/explain schema-or-fn data))})))
    ;; it's a fn
    (if (schema-or-fn data)
      data
      (throw (ex-info error-msg
                      {:type :validation-error
                       :data data
                       :message data})))))

(def default-timeout
  (* 4 60 1000))

(defn- coerce-response-headers
  "Coerce response headers to match the schema by transforming string keys to keywords
   and string values to integers where needed."
  [headers schema]
  (let [string->keyword-transformer (mt/key-transformer {:decode #(keyword (name %))})]
    (m/decode schema
              headers
              (mt/transformer
               string->keyword-transformer
               mt/string-transformer))))

(defn post
  [url body {:keys [headers parse-json? schemas statuses-handlers timeout]
             :or {parse-json? true}}]
  (assert url)
  (let [{:keys [request-schema response-schema response-header-schema]} schemas
        _ (when request-schema
            (validate-schema! "Invalid request schema" request-schema body))
        params (cond-> {:content-type :json
                        :form-params body
                        :throw-exceptions false
                        :timeout (or timeout default-timeout)
                        :connect-timeout (or timeout default-timeout)}
                 headers (assoc :headers headers)
                 parse-json? (assoc :as :json))
;;        _ (tap> {:debug-http-request {:url url
;;                                      :params params
;;                                      :headers headers
;;                                      :body body}})
        raw-response (http/post url params)
        response (cond-> raw-response
                   ;; For non-json or when we need to parse json manually
                   (and (:body raw-response)
                        (not parse-json?))
                   (update :body #(json/parse-string % true)))
        full-response {:url url
                       :headers headers
                       :params params
                       :response response}]
    (if (= 200 (:status response))
      (do
        (when response-schema
          (validate-schema! "Invalid response schema" response-schema (:body response)))

        (cond-> response
          response-header-schema (assoc :headers (validate-schema! "Invalid response header schema"
                                                                   response-header-schema
                                                                   (coerce-response-headers (:headers response) response-header-schema)))))
      (if-let [status-handler (get statuses-handlers (:status response))]
        (status-handler {:url url
                         :headers headers
                         :params params
                         :response response})
        (throw (ex-info (str (:status response) " response status for " url)
                        full-response))))))

(def POST post)

(defn GET
  [url query-params {:keys [headers parse-json? schemas statuses-handlers timeout]
                     :or {parse-json? true}}]
  (assert url)
  (let [{:keys [request-schema response-schema response-header-schema]} schemas
        _ (when request-schema
            (validate-schema! "Invalid request schema" request-schema query-params))
        params (cond-> {:query-params query-params
                        :throw-exceptions false
                        :timeout (or timeout default-timeout)
                        :connect-timeout (or timeout default-timeout)}
                 headers (assoc :headers headers)
                 parse-json? (assoc :as :json))
        raw-response (http/get url params)
        response (cond-> raw-response
                   ;; For non-json or when we need to parse json manually
                   (and (:body raw-response)
                        (not parse-json?))
                   (update :body #(json/parse-string % true)))
        full-response {:url url
                       :headers headers
                       :params params
                       :response response}]
    (if (= 200 (:status response))
      (do
        (when response-schema
          (validate-schema! "Invalid response schema" response-schema (:body response)))

        (cond-> response
          response-header-schema (assoc :headers (validate-schema! "Invalid response header schema"
                                                                   response-header-schema
                                                                   (coerce-response-headers (:headers response) response-header-schema)))))
      (if-let [status-handler (clojure.core/get statuses-handlers (:status response))]
        (status-handler {:url url
                         :headers headers
                         :params params
                         :response response})
        (throw (ex-info (str (:status response) " response status for " url)
                        full-response))))))

(defn- parse-sse-event
  "Parse a Server-Sent Event into a Clojure data structure"
  [raw-event]
  (let [data-idx (string/index-of raw-event "{")
        done-idx (string/index-of raw-event "[DONE]")]
    (if done-idx
      :done
      (when data-idx
        (try
          (-> (subs raw-event data-idx)
              (json/parse-string true))
          (catch Exception _
            nil))))))

(defn- sse-events
  "Returns a core.async channel with SSE events as Clojure data structures"
  [{:keys [url headers body parse-event]
    :or {parse-event parse-sse-event}}]
  (let [events (a/chan (a/buffer 1000) (map parse-event))
        event-mask (re-pattern "(?s).+?\n\n")]
    (a/thread
      (try
        (let [response (http/post url
                                  {:headers headers
                                   :body body
                                   :as :stream
                                   :throw-exceptions false})
              stream (:body response)]
          (loop [byte-coll []]
            (let [byte-arr (byte-array (max 1 (.available stream)))
                  bytes-read (.read stream byte-arr)]
              (if (neg? bytes-read)
                ;; Input stream closed, exiting read-loop
                (a/close! events)
                (let [next-byte-coll (concat byte-coll (seq byte-arr))
                      data (slurp (byte-array next-byte-coll))]
                  (if-let [es (not-empty (re-seq event-mask data))]
                    (if (every? true? (map #(a/>!! events %) es))
                      (recur (drop (apply + (map #(count (.getBytes ^String %)) es))
                                   next-byte-coll))
                      ;; Output stream closed, exiting read-loop
                      (a/close! events))
                    (recur next-byte-coll))))))
          (.close stream))
        (catch Exception e
          (a/>!! events {:error e})
          (a/close! events))))
    events))

;; TODO add schema check if possible
(defn stream-post
  "Make a streaming POST request and return a channel with SSE events"
  [url body {:keys [headers parse-event] :as _opts}]
  (assert url)
  (sse-events {:url url
               :headers headers
               :body body
               :parse-event parse-event}))
