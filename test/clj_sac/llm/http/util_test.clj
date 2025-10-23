(ns clj-sac.llm.http.util-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-sac.llm.http.util :refer [extract-json-from-content
                                           extract-clojure-from-content
                                           extract-json-from-markdown]]))

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

(deftest extract-clojure-from-content-test
  (testing "Extract Clojure code from markdown code block with clojure tag"
    (let [content "```clojure\n(+ 1 2)\n```"]
      (is (= (extract-clojure-from-content content)
             "(+ 1 2)"))))

  (testing "Extract Clojure code from complex data structure"
    (let [content "```clojure\n[{:url \"https://example.com\" :title \"Test\" :description \"A test site\"}]\n```"]
      (is (= (extract-clojure-from-content content)
             "[{:url \"https://example.com\" :title \"Test\" :description \"A test site\"}]"))))

  (testing "Extract Clojure code with multiple lines"
    (let [content "```clojure\n(defn hello [name]\n  (str \"Hello, \" name))\n```"]
      (is (= (extract-clojure-from-content content)
             "(defn hello [name]\n  (str \"Hello, \" name))"))))

  (testing "Extract Clojure code from Gemini response format"
    (let [content "```clojure\n[{:url \"https://www.figma.com/blog/feed/\" :title \"Figma Blog\" :description \"Official updates, tips, and insights directly from the Figma team.\"}\n {:url \"https://uxdesign.cc/feed\" :title \"UX Collective\" :description \"A leading publication for UX, UI, and product design, often featuring Figma content.\"}]\n```"]
      (is (= (extract-clojure-from-content content)
             "[{:url \"https://www.figma.com/blog/feed/\" :title \"Figma Blog\" :description \"Official updates, tips, and insights directly from the Figma team.\"}\n {:url \"https://uxdesign.cc/feed\" :title \"UX Collective\" :description \"A leading publication for UX, UI, and product design, often featuring Figma content.\"}]"))))

  (testing "No Clojure code block present returns nil"
    (let [content "No Clojure code here!"]
      (is (nil? (extract-clojure-from-content content)))))

  (testing "Empty string returns nil"
    (let [content ""]
      (is (nil? (extract-clojure-from-content content)))))

  (testing "Nil input returns nil"
    (is (nil? (extract-clojure-from-content nil))))

  (testing "Code block without clojure tag returns nil"
    (let [content "```\n(+ 1 2)\n```"]
      (is (nil? (extract-clojure-from-content content)))))

  (testing "Malformed code block returns nil"
    (let [content "```clojure\n(+ 1 2)```"]
      (is (nil? (extract-clojure-from-content content)))))

  (testing "Multiple code blocks extracts the first one"
    (let [content "```clojure\n(+ 1 2)\n```\nSome text\n```clojure\n(* 3 4)\n```"]
      (is (= (extract-clojure-from-content content)
             "(+ 1 2)")))))

(deftest extract-json-from-markdown-test
  (testing "Extract JSON object from markdown code block with json tag"
    (let [content "```json\n{\n  \"foo\": 1, \"bar\": 2\n}\n```"]
      (is (= (extract-json-from-markdown content)
             "{\n  \"foo\": 1, \"bar\": 2\n}"))))

  (testing "Extract JSON array from markdown code block with json tag"
    (let [content "```json\n[1, 2, 3]\n```"]
      (is (= (extract-json-from-markdown content)
             "[1, 2, 3]"))))

  (testing "Extract JSON object from markdown code block without tag"
    (let [content "```\n{\"baz\": 42}\n```"]
      (is (= (extract-json-from-markdown content)
             "{\"baz\": 42}"))))

  (testing "Extract JSON array from markdown code block without tag"
    (let [content "```\n[\"a\", \"b\", \"c\"]\n```"]
      (is (= (extract-json-from-markdown content)
             "[\"a\", \"b\", \"c\"]"))))

  (testing "Extract JSON object from plain text"
    (let [content "Some text before {\"a\": 1, \"b\": 2} some text after"]
      (is (= (extract-json-from-markdown content)
             "{\"a\": 1, \"b\": 2}"))))

  (testing "Extract JSON array from plain text"
    (let [content "Here is an array: [1, 2, 3, 4] and more text"]
      (is (= (extract-json-from-markdown content)
             "[1, 2, 3, 4]"))))

  (testing "Extract nested JSON object"
    (let [content "```json\n{\"outer\": {\"inner\": [1, 2]}}\n```"]
      (is (= (extract-json-from-markdown content)
             "{\"outer\": {\"inner\": [1, 2]}}"))))

  (testing "Extract nested JSON array"
    (let [content "```json\n[[1, 2], [3, 4]]\n```"]
      (is (= (extract-json-from-markdown content)
             "[[1, 2], [3, 4]]"))))

  (testing "No JSON present returns nil"
    (let [content "No JSON here!"]
      (is (nil? (extract-json-from-markdown content)))))

  (testing "Empty string returns nil"
    (let [content ""]
      (is (nil? (extract-json-from-markdown content)))))

  (testing "Nil input returns nil"
    (is (nil? (extract-json-from-markdown nil))))

  (testing "Multiple code blocks extracts the first one"
    (let [content "```json\n{\"first\": 1}\n```\nSome text\n```json\n{\"second\": 2}\n```"]
      (is (= (extract-json-from-markdown content)
             "{\"first\": 1}"))))

  (testing "Extract JSON with extra whitespace"
    (let [content "```json\n  \n  {\"spaced\": true}  \n  \n```"]
      (is (= (extract-json-from-markdown content)
             "{\"spaced\": true}"))))

  (testing "Extract complex array of objects"
    (let [content "```json\n[{\"id\": 1, \"name\": \"Alice\"}, {\"id\": 2, \"name\": \"Bob\"}]\n```"]
      (is (= (extract-json-from-markdown content)
             "[{\"id\": 1, \"name\": \"Alice\"}, {\"id\": 2, \"name\": \"Bob\"}]")))))