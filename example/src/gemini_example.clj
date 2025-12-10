(ns gemini-example
  (:require [clj-sac.llm.http.gemini :as gemini]))

(def TOKEN (System/getenv "GEMINI_API_KEY"))

(comment
  (def answer (gemini/chat-completion
               {:messages [{:content "Write me a small haiku about Amsterdam"
                            :role "user"}]
                :model "gemini-2.5-flash"}
               {:headers {"x-goog-api-key" TOKEN}}))
  (get-in answer [:body :candidates 0 :content :parts 0 :text])
  ; "Canals gently flow,\nBicycles glide on the bridges,\nDutch charm fills the air."
  )
