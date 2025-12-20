(ns clj-sac.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-sac.http :as http]
            [cheshire.core :as json]
            [hato-http-fake.fake :as http-fake]))

;; Helper functions

(defn- json-response
  ([status body-data]
   (json-response status body-data {}))
  ([status body-data headers]
   {:body (json/generate-string body-data)

    :headers (merge {"Content-Type" "application/json"} headers)
    :status status}))

(defn- assert-response-body
  "Assert that the response body contains the expected key-value pairs."
  [response expected-data]
  (doseq [[k v] expected-data]
    (is (= v (get-in response [:body k]))
        (str "Expected " k " to be " v " in response body"))))

(defn- make-status-handler
  "Create a status handler that records its call and validates the context."
  [expected-context handler-result]
  (let [called? (atom false)
        handler (fn [ctx]
                  (reset! called? true)
                  (doseq [[k v] expected-context]
                    (is (= v (get ctx k))
                        (str "Expected context " k " to be " v)))
                  handler-result)]
    {:handler handler
     :called? called?}))

(defn- make-get-status-handler
  "Create a status handler for GET requests that validates the context with hato params structure.
  For GET requests, :params in the context is the hato params map (containing :query-params, :headers, etc.)"
  [expected-url expected-headers expected-query-params handler-result]
  (let [called? (atom false)
        handler (fn [ctx]
                  (reset! called? true)
                  (is (= expected-url (:url ctx)))
                  (is (= expected-headers (:headers ctx)))
                  (is (= expected-query-params (:query-params (:params ctx))))
                  (is (some? (:response ctx)))
                  handler-result)]
    {:handler handler
     :called? called?}))

;; Tests

(deftest test-post-success
  (testing "POST request with successful response"
    (http-fake/with-fake-routes-in-isolation
      {"https://api.example.com/test"
       {:post (fn [_req]
                (json-response 200 {:message "success" :data 123}))}}

      (let [response (http/POST "https://api.example.com/test"
                       {:test "data"}
                       {:parse-json? true})]
        (is (= 200 (:status response)))
        (assert-response-body response {:message "success" :data 123})))))

(deftest test-post-with-schemas
  (testing "POST request with request and response schema validation"
    (let [request-schema [:map [:name string?] [:age int?]]
          response-schema [:map [:message string?] [:status int?]]]

      (http-fake/with-fake-routes-in-isolation
        {"https://api.example.com/validate"
         {:post (fn [_req]
                  (json-response 200 {:message "validated" :status 200}))}}

        (let [response (http/POST "https://api.example.com/validate"
                         {:name "John" :age 30}
                         {:schemas {:request-schema request-schema
                                    :response-schema response-schema}})]
          (is (= 200 (:status response)))
          (assert-response-body response {:message "validated" :status 200}))))))

(deftest test-get-success
  (testing "GET request with successful response"
    (http-fake/with-fake-routes-in-isolation
      {#"https://api.example.com/.*"
       {:get (fn [_req]
               (json-response 200 {:message "success" :data 456}))}}

      (let [response (http/GET "https://api.example.com/test"
                       {:query "param"}
                       {:parse-json? true})]
        (is (= 200 (:status response)))
        (assert-response-body response {:message "success" :data 456})))))

(deftest test-get-with-schemas
  (testing "GET request with request and response schema validation"
    (let [request-schema [:map [:query string?]]
          response-schema [:map [:message string?] [:count int?]]]

      (http-fake/with-fake-routes-in-isolation
        {#"https://api.example.com/.*"
         {:get (fn [_req]
                 (json-response 200 {:message "retrieved" :count 42}))}}

        (let [response (http/GET "https://api.example.com/query"
                         {:query "search-term"}
                         {:schemas {:request-schema request-schema
                                    :response-schema response-schema}})]
          (is (= 200 (:status response)))
          (assert-response-body response {:message "retrieved" :count 42}))))))

(deftest test-post-with-status-handler
  (testing "POST request with non-200 status and status handler"
    (http-fake/with-fake-routes-in-isolation
      {"https://api.example.com/error"
       {:post (fn [_req]
                (json-response 404 {:error "Not found"}))}}

      (let [handler-result {:handled true :status 404}
            {:keys [handler called?]} (make-status-handler
                                       {:url "https://api.example.com/error"
                                        :headers {:test "header"}
                                        :params {:test "param"}}
                                       handler-result)
            response (http/POST "https://api.example.com/error"
                       {:test "data"}
                       {:headers {:test "header"}
                        :params {:test "param"}
                        :statuses-handlers {404 handler}})]
        (is @called? "Status handler should be called")
        (is (= handler-result response) "Response should be the handler result")
        (is (= 404 (:status response)))))))

(deftest test-post-without-status-handler
  (testing "POST request with non-200 status without status handler"
    (http-fake/with-fake-routes-in-isolation
      {"https://api.example.com/error"
       {:post (fn [_req]
                (json-response 500 {:error "Internal server error"}))}}

      (let [response (http/POST "https://api.example.com/error"
                       {:test "data"}
                       {:parse-json? true})]
        (is (= 500 (:status response)))
        (assert-response-body response {:error "Internal server error"})))))

(deftest test-get-with-status-handler
  (testing "GET request with non-200 status and status handler"
    (http-fake/with-fake-routes-in-isolation
      {#"https://api.example.com/.*"
       {:get (fn [_req]
               (json-response 403 {:error "Forbidden"}))}}

      (let [handler-result {:handled true :status 403}
            {:keys [handler called?]} (make-get-status-handler
                                       "https://api.example.com/forbidden"
                                       {:auth "token"}
                                       {:query "test"}
                                       handler-result)
            response (http/GET "https://api.example.com/forbidden"
                       {:query "test"}
                       {:headers {:auth "token"}
                        :statuses-handlers {403 handler}})]
        (is @called? "Status handler should be called")
        (is (= handler-result response) "Response should be the handler result")))))

(deftest test-get-without-status-handler
  (testing "GET request with non-200 status without status handler"
    (http-fake/with-fake-routes-in-isolation
      {#"https://api.example.com/.*"
       {:get (fn [_req]
               (json-response 401 {:error "Unauthorized"}))}}

      (let [response (http/GET "https://api.example.com/unauthorized"
                       {:query "test"}
                       {:parse-json? true})]
        (is (= 401 (:status response)))
        (assert-response-body response {:error "Unauthorized"})))))