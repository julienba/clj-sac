(ns clj-sac.agent2-test
  (:require [clojure.test :refer :all]
            [clj-sac.agent2 :as agent2]
            [clj-sac.llm.http.mistral :as mistral]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn mock-mistral-chat-completion [_model-opts _http-opts]
  ;; Simulate a response that would trigger the edit_file tool call for hello.py
  {:status 200
   :body {:id "mock-id"
          :object "chat.completion"
          :created 1234567890
          :model "mistral-large-latest"
          :choices [{:index 0
                     :message {:role "assistant"
                               :tool_calls [{:id "mock-tool-id"
                                             :type "function"
                                             :index 0
                                             :function {:name "edit_file"
                                                        :arguments (json/write-str {"path" "hello.py"
                                                                                    "old_str" ""
                                                                                    "new_str" "print('Hello World')"})}}]
                               :content ""}
                     :finish_reason "tool_calls"}]
          :usage {:prompt_tokens 42 :total_tokens 84 :completion_tokens 42}}})

(deftest agent2-hello-world-test
  (with-redefs [mistral/chat-completion mock-mistral-chat-completion]
    (let [test-file "hello.py"]
      (try
        ;; Clean up before test
        (when (.exists (io/file test-file))
          (io/delete-file test-file))

        (let [agent (agent2/create-agent "mistral-large-latest" agent2/default-system-prompt "Write a simple 'Hello World' program in Python and save it to hello.py")
              ;; Run one OODA loop iteration manually
              obs (agent2/observe agent)
              situation (agent2/orient agent obs)
              decision (agent2/decide agent situation)
              result (agent2/act agent decision)]
          (is (.exists (io/file test-file)) "hello.py should be created")
          (is (= (str/trim (slurp test-file)) "print('Hello World')") "hello.py should contain Hello World Python code")
          (is (:continue result) "Should continue after tool execution"))
        (finally
          ;; Clean up after test
          (when (.exists (io/file test-file))
            (io/delete-file test-file)))))))