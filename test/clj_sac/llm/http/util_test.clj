(ns clj-sac.llm.http.util-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-sac.llm.http.util :refer [extract-json-from-content extract-clojure-from-content]]))

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