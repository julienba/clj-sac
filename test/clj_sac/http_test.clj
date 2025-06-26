(ns clj-sac.http-test
  (:require [clojure.test :refer :all]
            [clj-sac.http :as http]
            [hato-http-fake.fake :as http-fake]))

(deftest test-post-success
  (testing "POST request with successful response"
    (http-fake/with-fake-routes-in-isolation
      {"https://api.example.com/test"
       {:post (fn [_req]
                {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body "{\"message\": \"success\", \"data\": 123}"})}}

      (let [response (http/post "https://api.example.com/test"
                                {:test "data"}
                                {:parse-json? true})]
        (is (= 200 (:status response)))
        (is (= "success" (:message (:body response))))
        (is (= 123 (:data (:body response))))))))

(deftest test-post-with-schemas
  (testing "POST request with request and response schema validation"
    (let [request-schema [:map [:name string?] [:age int?]]
          response-schema [:map [:message string?] [:status int?]]]

      (http-fake/with-fake-routes-in-isolation
        {"https://api.example.com/validate"
         {:post (fn [_]
                  {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body "{\"message\": \"validated\", \"status\": 200}"})}}

        (let [response (http/post "https://api.example.com/validate"
                                  {:name "John" :age 30}
                                  {:schemas {:request-schema request-schema
                                             :response-schema response-schema}})]
          (is (= 200 (:status response)))
          (is (= "validated" (:message (:body response))))
          (is (= 200 (:status (:body response)))))))))

