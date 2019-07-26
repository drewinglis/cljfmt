(ns cljfmt.tool.main
  "Main entry for cljfmt tool."
  (:gen-class)
  (:require
    [cljfmt.config :as config]
    [cljfmt.core :as cljfmt]
    [cljfmt.tool.diff :as diff]
    [cljfmt.tool.process :refer [walk-files!]]
    [clojure.java.io :as io]
    [clojure.stacktrace :as st]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.pprint :refer [pprint]])
  (:import
    java.io.File))


(def cli-options
  "Command-line tool options."
  [[nil  "--no-color" "Don't output ANSI color codes"]
   ["-v"  "--verbose" "Print detailed debugging output."]
   ["-h" "--help" "Show help and usage information."]])


(defn- print-general-usage
  "Print general usage help for the tool."
  [summary]
  (println "Usage: cljfmt [options] <command> [args...]")
  (newline)
  (println "Commands:")
  (println "    check     Find files with formatting errors and print a diff.")
  (println "    fix       Edit source files to fix formatting errors.")
  (println "    config    Show config used for a given path.")
  (println "    version   Print program version information.")
  (newline)
  (println "Options:")
  (println summary))



;; ## Utilities

(def ^:dynamic *options*
  "Configuration options."
  {})


(defn- printerr
  "Print a message to standard error."
  [message & fmt-args]
  (binding [*out* *err*]
    (apply printf (str message "\n") fmt-args)
    (flush)))


(defn- log
  "Log a message which will only be printed when --verbose is set."
  [message & fmt-args]
  (when (:verbose *options*)
    (apply printerr message fmt-args))
  nil)


(defn- search-roots
  "Convert the list of paths into a collection of canonical search roots. If
  the path list is empty, uses the local directory as a single root."
  [paths]
  (mapv #(.getCanonicalFile (io/file %)) (or (seq paths) ["."])))


(defn- load-configs
  "Load parent configuration files. Returns a merged configuration map."
  [^File file]
  (let [configs (config/find-parents file 20)]
    (if (seq configs)
      (log "Using cljfmt configuration from %d sources for %s:\n%s"
           (count configs)
           (.getPath file)
           (str/join "\n" (map config/source-path configs)))
      (log "Using default cljfmt configuration for %s"
           (.getPath file)))
    (apply config/merge-settings config/default-config configs)))



;; ## Check Command

(defn- print-check-usage
  "Print help for the check command."
  []
  (println "Usage: cljfmt [options] check [paths...]")
  (newline)
  (println "Check source files for formatting errors. Prints a diff of all malformed lines")
  (println "found and exits with an error if any files have format errors."))


(defn- check-source
  "Check a single source file and produce a result."
  [options config path ^File file]
  (let [original (slurp file)
        revised (cljfmt/reformat-string original config)]
    (if (= original revised)
      {:type :correct
       :debug (str "Source file " path " is  formatted correctly")}
      (let [diff (cond-> (diff/unified-diff path original revised)
                   (not (:no-color options))
                   (diff/colorize-diff))]
        {:type :incorrect
         :debug (str "Source file " path " is formatted incorrectly")
         :info diff}))))


(defn- check-sources
  "Implementation of the `check` command."
  [paths]
  (->
    (->>
      (search-roots paths)
      (pmap (juxt load-configs identity identity))
      (walk-files! (partial check-source *options*)))
    (as-> results
      (let [counts (:counts results)
            total (apply + (vals counts))]
        (log "Checked %d files in %.2f ms"
             total
             (:elapsed results -1.0))
        (log (pr-str counts))
        (when-not (empty? (:errors results))
          (printerr "Failed to process %d files\n" (count (:errors results)))
          (System/exit 3))
        (when-not (zero? (:incorrect counts 0))
          (printerr "%d files formatted incorrectly" (:incorrect counts))
          (System/exit 2))
        (log "All %d files formatted correctly" (:correct counts))))))



;; ## Fix Command

(defn- print-fix-usage
  "Print help for the fix command."
  []
  (println "Usage: cljfmt [options] fix [paths...]")
  (newline)
  (println "Edit source files in place to correct formatting errors."))


(defn- fix-source
  "Fix a single source file and produce a result."
  [options config path ^File file]
  (let [original (slurp file)
        revised (cljfmt/reformat-string original config)]
    (if (= original revised)
      {:type :correct
       :debug (str "Source file " path " is  formatted correctly")}
      (do
        (spit file revised)
        {:type :fixed
         :info (str "Reformatting source file " path)}))))


(defn- fix-sources
  "Implementation of the `fix` command."
  [paths]
  (->
    (->>
      (search-roots paths)
      (pmap (juxt load-configs identity identity))
      (walk-files! (partial fix-source *options*)))
    (as-> results
      (let [counts (:counts results)
            total (apply + (vals counts))]
        (log "Checked %d files in %.2f ms"
             total
             (:elapsed results -1.0))
        (log (pr-str counts))
        (when-not (empty? (:errors results))
          (printerr "Failed to process %d files\n" (count (:errors results)))
          (System/exit 3))
        (if (zero? (:fixed counts 0))
          (log "All %d files formatted correctly" (:correct counts))
          (printerr "Corrected formatting of %d files" (:fixed counts)))))))



;; ## Config Command

(defn- print-config-usage
  "Print help for the config command."
  []
  (println "Usage: cljfmt [options] config [path]")
  (newline)
  (println "Show the merged configuration which would be used to format the file or")
  (println "directory at the given path. Uses the current directory if one is not given."))


(defn- show-config
  "Implementation of the `config` command."
  [paths]
  (when (< 1 (count paths))
    (binding [*out* *err*]
      (println "cljfmt config command takes at most one argument")
      (flush)
      (System/exit 1)))
  (let [file (first (search-roots paths))
        config (load-configs file)]
    (pprint config)))



;; ## Version Command

(def version
  "Project version string."
  (if-let [props-file (io/resource "META-INF/maven/mvxcvi/cljfmt/pom.properties")]
    (with-open [props-reader (io/reader props-file)]
      (let [props (doto (java.util.Properties.)
                    (.load props-reader))
            {:strs [groupId artifactId version revision]} props]
        (format "%s/%s %s (%s)"
                groupId artifactId version
                (str/trim-newline revision))))
    "HEAD"))


(defn- print-version
  "Implementation of the `version` command."
  [args]
  (when (seq args)
    (binding [*out* *err*]
      (println "cljfmt version command takes no arguments")
      (flush)
      (System/exit 1)))
  (println version)
  (flush))



;; ## Tool Entry

(defn -main
  "Main entry point."
  [& raw-args]
  (let [parsed (cli/parse-opts raw-args cli-options)
        [command args] (parsed :arguments)
        options (parsed :options)]
    ;; Print any option parse errors and abort.
    (when-let [errors (parsed :errors)]
      (binding [*out* *err*]
        (run! println errors)
        (flush)
        (System/exit 1)))
    ;; Show help for general usage or a command.
    (when (:help options)
      (case command
        "check"  (print-check-usage)
        "fix"    (print-fix-usage)
        "config" (print-config-usage)
        (print-general-usage (parsed :summary)))
      (flush)
      (System/exit 0))
    ;; If no command provided, print help and exit with an error.
    (when-not command
      (print-general-usage (parsed :summary))
      (flush)
      (System/exit 1))
    ;; Execute requested command.
    (try
      (binding [*options* options]
        (case command
          "check"   (check-sources args)
          "fix"     (fix-sources args)
          "config"  (show-config args)
          "version" (print-version args)
          (binding [*out* *err*]
            (println "Unknown cljfmt command:" command)
            (System/exit 1))))
      (catch Exception ex
        (binding [*out* *err*]
          (st/print-stack-trace ex)
          (flush)
          (System/exit 4))))
    ;; Successful tool run if no other exit.
    (System/exit 0)))
