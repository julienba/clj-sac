(ns clj-sac.core
  (:require
   [malle.llm.protocol :as p]
   [malle.llm.mistral :as llm.mistral]))


;(p/count-tokens (llm.mistral/new {}) [])