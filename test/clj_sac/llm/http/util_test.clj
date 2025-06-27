(ns clj-sac.llm.http.util-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-sac.llm.http.util :refer [extract-json-from-content]]))

(deftest extract-json-from-content-test
  (testing "Extract JSON from markdown code block with json tag"
    (let [content "```json\n{\n  \"foo\": 1, \"bar\": 2\n}\n```"]
      (is (= (extract-json-from-content content)
             "{\n  \"foo\": 1, \"bar\": 2\n}"))))

  (testing "Extract JSON from markdown code block without tag"
    (let [content "```\n{\"baz\": 42}\n```"]
      (is (= (extract-json-from-content content)
             "{\"baz\": 42}"))))

  (testing "Extract JSON from plain text"
    (let [content "Some text before {\"a\": 1, \"b\": 2} some text after"]
      (is (= (extract-json-from-content content)
             "{\"a\": 1, \"b\": 2}"))))

  (testing "No JSON present returns nil"
    (let [content "No JSON here!"]
      (is (nil? (extract-json-from-content content))))))