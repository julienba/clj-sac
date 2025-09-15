(ns clj-sac.llm.http.util)

(defn extract-json-from-content [content]
  (let [code-block-re #"(?s)```(?:json)?\s*(\{.*?\})\s*```"
        match (re-find code-block-re content)]
    (if match
      (second match)
      ;; fallback: try to find the first { ... } block
      (let [brace-match (re-find #"(?s)(\{.*\})" content)]
        (second brace-match)))))

(defn extract-clojure-from-content
  "When using keep in mind that evaluating code from a non-deterministc LLM is a security risk."
  [content]
  (when content
    (when-let [match (re-find #"(?s)```clojure\n(.*?)\n```" content)]
      (when match
        (second match)))))
