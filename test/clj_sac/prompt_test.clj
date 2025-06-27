(ns clj-sac.prompt-test
  (:require [clojure.test :refer [deftest is]]
            [clj-sac.prompt :as sut]))

(deftest test-load-prompt
  (let [p (sut/load-prompt "resources/example.prompt")
        expected "Extract the requested information from the given text. If a piece of information\nis not present, omit that field from the output.\nReply in JSON following the format sent.\n\nText: John Doe is a 35-year-old software engineer living in New York."]
    (is (= (:model (:meta p)) "googleai/gemini-1.5-pro"))
    (is (= (get-in (:meta p) [:input :schema :text]) "string"))
    (is (string? (:template p)))
    (is (re-find #"Text: \{\{text\}\}" (:template p)))
    (is (= ((:render p) {:text "John Doe is a 35-year-old software engineer living in New York."})
           expected))))

(deftest test-nonexistent-file
  (is (thrown? java.io.FileNotFoundException
               (sut/load-prompt "resources/does-not-exist.prompt"))))

(deftest test-missing-variable
  (let [p (sut/load-prompt "resources/example.prompt")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing template variables:.*text.*"
                          ((:render p) {})))))

(deftest test-multiple-missing-variables
  (let [template "Hello {{foo}} and {{bar}}!"
        render (fn [vars] (#'sut/render-prompt template vars))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing template variables:.*foo.*bar.*"
                          (render {})))))

(deftest test-extra-variable
  (let [p (sut/load-prompt "resources/example.prompt")
        expected "Extract the requested information from the given text. If a piece of information\nis not present, omit that field from the output.\nReply in JSON following the format sent.\n\nText: foo"]
    ;; Extra variables are ignored, but required variable must be present and non-nil
    (is (= ((:render p) {:text "foo" :extra "bar"})
           expected))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing template variables:.*text.*"
                          ((:render p) {:text nil :extra "bar"})))))

(deftest test-nil-variable
  (let [p (sut/load-prompt "resources/example.prompt")]
    ;; Nil variable is treated as missing
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing template variables:.*text.*"
                          ((:render p) {:text nil})))))
