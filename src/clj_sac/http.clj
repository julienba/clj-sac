(ns clj-sac.http
  (:require
   [hato.client :as http]
   [cheshire.core :as json]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

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
  (* 4 (* 60 1000)))

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
        _ (tap> {:debug-http-request {:url url
                                      :params params
                                      :body body}})
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
