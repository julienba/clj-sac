(ns clj-sac.tool
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; Tool definitions
(def tools
  [{:type "function"
    :function {:name "read_file"
               :description "Read the contents of a file"
               :parameters {:type "object"
                            :properties {:path {:type "string"
                                                :description "The file path to read"}}
                            :required ["path"]}}}
   {:type "function"
    :function {:name "edit_file"
               :description "Edit a file by replacing old content with new content"
               :parameters {:type "object"
                            :properties {:path {:type "string"
                                                :description "The file path to edit"}
                                         :old_str {:type "string"
                                                   :description "The old content to replace (empty string for new file)"}
                                         :new_str {:type "string"
                                                   :description "The new content to insert"}}
                            :required ["path" "old_str" "new_str"]}}}
   {:type "function"
    :function {:name "list_directory"
               :description "List files and directories in a given path"
               :parameters {:type "object"
                            :properties {:path {:type "string"
                                                :description "The directory path to list"}}
                            :required ["path"]}}}])

;; Tool execution functions
(defn read-file-tool [path]
  (try
    {:success true
     :content (slurp path)}
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn edit-file-tool [path old-str new-str]
  (try
    (let [file (io/file path)]
      (if (.exists file)
        (if (str/blank? old-str)
          ;; Append to existing file
          (let [current-content (slurp file)
                new-content (str current-content "\n" new-str)]
            (spit path new-content)
            {:success true
             :message (str "Successfully appended to " path)})
          ;; Replace specific content in existing file
          (let [current-content (slurp file)
                new-content (str/replace current-content old-str new-str)]
            (spit path new-content)
            {:success true
             :message (str "Successfully edited " path)}))
        ;; Create new file
        (do
          (io/make-parents file)
          (spit path new-str)
          {:success true
           :message (str "Successfully created " path)})))
    (catch Exception e
      {:success false
       :error (if (instance? clojure.lang.ExceptionInfo e)
                (ex-message e)
                (.getMessage e))})))

(defn list-directory-tool [path]
  (try
    (let [dir (io/file path)]
      (if (.exists dir)
        {:success true
         :files (->> (.listFiles dir)
                     (map #(hash-map :name (.getName %)
                                     :type (if (.isDirectory %) "directory" "file")))
                     (sort-by :name))}
        {:success false
         :error (str "Directory does not exist: " path)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn execute-tool [tool-name args]
  (case tool-name
    "read_file" (read-file-tool (:path args))
    "edit_file" (edit-file-tool (:path args) (:old_str args) (:new_str args))
    "list_directory" (list-directory-tool (:path args))
    {:success false
     :error (str "Unknown tool: " tool-name)}))