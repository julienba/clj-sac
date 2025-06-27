(ns clj-sac.llm.http.util)

(defn extract-json-from-content [content]
  (let [code-block-re #"(?s)```(?:json)?\s*(\{.*?\})\s*```"
        match (re-find code-block-re content)]
    (if match
      (second match)
      ;; fallback: try to find the first { ... } block
      (let [brace-re #"(?s)(\{.*\})"
            brace-match (re-find brace-re content)]
        (second brace-match)))))
