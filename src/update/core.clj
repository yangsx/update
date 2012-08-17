(ns update.core
  (:gen-class)
  (:require [clojure.java.shell :as sh]
            [fs.core :as fs]))

(def ^:dynamic *initial-update-status*
  {:fetch-failure []
   :up-to-date []
   :updated []
   :merge-failure []})

(def ^:dynamic *update-status* nil)

(defn log-status
  "git fetch/rebase/pull results in:
   {:exit 128 :out \"\" :err \"something\"} if error in fetching 
   {:exit 0 :out \"\" :err \"\"} if already updated, nothing fetched
   {:exit 0 :out \"\" :err \"something\"} if something fetched
   {:exit 0 :out \"something\" :err \"\"} if something rebased
   {:exit 0 :out \"something\" :err \"something\"} if error in rebasing"
  [repo-name kind]
  (when *update-status*
    (dosync (commute *update-status* update-in [kind] conj repo-name))))

(defn exit-ok?
  [{:keys [exit out err]}]
  (zero? exit))

(defn up-to-date?
  [{:keys [exit out err]}]
  (and (empty? out) (empty? err)))

(defn print-sh-result
  [{:keys [exit out err]}]
  (when (not (empty? err))
    (println err))
  (when (not (empty? out))
    (println out)))

(defn git-repo?
  "returns true if d is a git repo."
  [d]
  (let [git (fs/file d ".git")]
    (and (fs/exists? git)
         (fs/directory? git)
         (not (fs/exists? (fs/file git "svn"))))))

(defn local-repo?
  "returns true if repo is a local-only repo."
  [repo]
  (let [remotes (sh/with-sh-dir repo (sh/sh "git" "remote"))]
    (and (exit-ok? remotes)
         (empty? (:out remotes)))))

(defn update-git
  [repo]
  (let [fetch (sh/with-sh-dir repo (sh/sh "git" "fetch"))
        repo-name (fs/base-name repo)]
    (println "git fetch" repo)
    (print-sh-result fetch)
    (if (exit-ok? fetch)
      (if (up-to-date? fetch)
        (log-status repo-name :up-to-date)
        (let [rebase (sh/with-sh-dir repo (sh/sh "git" "rebase"))]
          (println "git rebase" repo)
          (print-sh-result rebase)
          (if (exit-ok? rebase)
            (log-status repo-name :updated)
            (log-status repo-name :merge-failure))))
      (log-status repo-name :fetch-failure))))

(defn update-repos
  [d]
  {:pre [(and (fs/exists? d) (fs/directory? d))]}
  (binding [*update-status* (ref *initial-update-status*)]
    (doseq [repo (fs/list-dir d)]
      (let [repo (fs/absolute-path (fs/file d repo))]
        (when (fs/directory? repo)
          (if (and (git-repo? repo)
                   (not (local-repo? repo)))
            (update-git repo)
            ;; not a git repo
            ))))
    @*update-status*))

(defn -main
  "Given a list of directories, recurse to find subdirectories that contains a git/mercury repo and fetch/merge/rebase from the remote.

Skip the folder if there is any indication that the remote repo is dead."
  [& args]
  (doseq [d args]
    (-> d update-repos println)))