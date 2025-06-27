(ns clj-sac.prompt
  "Inspired by https://github.com/google/dotprompt"
  (:require [clj-yaml.core :as yaml]
            [selmer.parser :as selmer]
            [clojure.string :as str]))

(defn- parse-prompt-file
  "Reads a .prompt file, returns a map with :meta (YAML), :template (string)."
  [file]
  (let [content (slurp file)
        splits (str/split content #"(?m)^---$" 3)
        yaml-str (second splits)
        template (->> splits (drop 2) (str/join "---") str/trim)]
    {:meta (yaml/parse-string yaml-str)
     :template template}))

(defn- template-vars
  "Extracts all variable names from a Selmer template string."
  [template]
  (->> (selmer.parser/known-variables template)
       (map name)
       set))

(defn- render-prompt
  "Renders the template with the given variables. Throws ex-info if any variable is missing or nil."
  [template vars]
  (let [string-key-vars (into {}
                              (map (fn [[k v]]
                                     [(if (keyword? k) (name k) k) v])
                                   vars))
        required-vars (template-vars template)
        missing (remove #(and (contains? string-key-vars %)
                              (some? (get string-key-vars %))) required-vars)]
    (if (seq missing)
      (throw (ex-info (str "Missing template variables: " (pr-str missing)) {:missing missing}))
      (selmer/render template string-key-vars))))

(defn load-prompt
  "Loads a .prompt file and returns a map with :meta, :template, and a :render function."
  [file]
  (let [{:keys [meta template]} (parse-prompt-file file)]
    {:meta meta
     :template template
     :render (fn [vars] (render-prompt template vars))}))
