(ns update.core
  (:gen-class)
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]))

(def initial-update-status
  {:fetch-failure []
   :up-to-date []
   :updated []
   :merge-failure []})

(def updated-status (ref nil))

(defn log-status
  "git fetch/merge results in:
   {:exit 128 :out \"\" :err \"something\"} if error in fetching
   {:exit 0 :out \"\" :err \"\"} if already updated, nothing fetched
   {:exit 0 :out \"\" :err \"something\"} if something fetched
   {:exit 0 :out \"something\" :err \"\"} if something merged
   {:exit 0 :out \"something\" :err \"something\"} if error in merging"
  [repo-name kind]
  (when @updated-status
    (dosync (commute updated-status update-in [kind] conj repo-name))))

(defn sort-status
  "Sort the repo lists in the update-status."
  [{:keys [up-to-date updated merge-failure fetch-failure]}]
  {:up-to-date (vec (sort up-to-date))
   :updated (vec (sort updated))
   :fetch-failure (vec (sort fetch-failure))
   :merge-failure (vec (sort merge-failure))})

(defn exit-ok?
  [{:keys [exit out err]}]
  (zero? exit))

(defn up-to-date?
  [{:keys [exit out err]}]
  (and (empty? out) (empty? err)))

(defn print-sh-result
  [{:keys [exit out err]}]
  (when (seq err)
    (println err))
  (when (seq out)
    (println out)))

(defn git-repo?
  "returns true if d is a git repo."
  [d]
  (let [git (io/file d ".git")]
    (and (.exists git)
         (.isDirectory git)
         (not (.exists (io/file git "svn"))))))

(defn ignored?
  [d]
  "returns true if d contains  a file '.ignore'."
  (.exists (io/file d ".ignore")))

(defn local-repo?
  "returns true if repo is a local-only repo."
  [repo]
  (let [remotes (sh/with-sh-dir repo (sh/sh "git" "remote"))]
    (and (exit-ok? remotes)
         (empty? (:out remotes)))))

(defn update-git
  [^java.io.File repo]
  (println "git fetch" (str repo))
  (let [fetch (sh/with-sh-dir repo (sh/sh "git" "fetch"))
        repo-name (.getName repo)]
    (print-sh-result fetch)
    (if (exit-ok? fetch)
      (if (up-to-date? fetch)
        (log-status repo-name :up-to-date)
        (let [result (sh/with-sh-dir repo (sh/sh "git" "merge" "FETCH_HEAD"))]
          (println "git merge" (str repo))
          (print-sh-result result)
          (if (exit-ok? result)
            (log-status repo-name :updated)
            (log-status repo-name :merge-failure))))
      (log-status repo-name :fetch-failure))))

(defn update-repos
  [^java.io.File d]
  {:pre [(and (.exists d) (.isDirectory d))]}
  (dosync (ref-set updated-status initial-update-status))
  (let [repos
        (->> d
             (.listFiles)
             (filter #(and (.isDirectory ^java.io.File %)
                           (git-repo? %)
                           (not (ignored? %))
                           (not (local-repo? %))))
             sort
             (map agent))]
    (doseq [repo repos]
      (send repo update-git))
    (apply await repos))
  (sort-status @updated-status))

(defn add-shutdown-hook
  "handles Ctrl-C. f is a zero-argument function."
  [^Runnable f]
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. f)))

(defn -main
  "Given a list of directories, recurse to find subdirectories that contains a git repo and fetch/merge from the remote.

Skip the folder if there is any indication that the remote repo is dead."
  [& args]
  (add-shutdown-hook (fn [] (let [ret (str "Updating " (sort-status @updated-status))] (println ret))))
  (doseq [d args]
    (-> d io/file update-repos println))
  (System/exit 0))
