(ns update.core
  (:gen-class)
  (:require [clojure.java.shell :as sh]
            [me.raynes.fs :as fs]))

(def initial-update-status
  {:fetch-failure []
   :up-to-date []
   :updated []
   :merge-failure []})

(def updated-status (ref nil))

(defn log-status
  "git fetch/rebase/pull results in:
   {:exit 128 :out \"\" :err \"something\"} if error in fetching
   {:exit 0 :out \"\" :err \"\"} if already updated, nothing fetched
   {:exit 0 :out \"\" :err \"something\"} if something fetched
   {:exit 0 :out \"something\" :err \"\"} if something rebased
   {:exit 0 :out \"something\" :err \"something\"} if error in rebasing"
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
  (println "git fetch" repo)
  (let [fetch (sh/with-sh-dir repo (sh/sh "git" "fetch"))
        repo-name (fs/base-name repo)]
    (print-sh-result fetch)
    (if (exit-ok? fetch)
      (if (up-to-date? fetch)
        (log-status repo-name :up-to-date)
        (let [rebase (sh/with-sh-dir repo (sh/sh "git" "merge" "FETCH_HEAD"))]
          (println "git merge" repo)
          (print-sh-result rebase)
          (if (exit-ok? rebase)
            (log-status repo-name :updated)
            (log-status repo-name :merge-failure))))
      (log-status repo-name :fetch-failure))))

(defn update-repos
  [d]
  {:pre [(and (fs/exists? d) (fs/directory? d))]}
  (dosync (ref-set updated-status initial-update-status))
  (let [repos
        (->> d
             fs/list-dir
             (map #(-> (fs/file d %) fs/normalized-path fs/absolute-path))
             (filter #(and (fs/directory? %)
                           (git-repo? %)
                           (not (local-repo? %))))
             sort
             (map agent))]
    (doseq [repo repos]
      (send repo update-git))
    (apply await repos))
  (sort-status @updated-status))

(defn add-shutdown-hook
  "handles Ctrl-C. f is a zero-argument function."
  [f]
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. f)))

(defn -main
  "Given a list of directories, recurse to find subdirectories that contains a git/mercury repo and fetch/merge/rebase from the remote.

Skip the folder if there is any indication that the remote repo is dead."
  [& args]
  (add-shutdown-hook (fn [] (let [ret (str "Updating " (sort-status @updated-status))] (println ret))))
  (doseq [d args]
    (-> d update-repos println))
  (System/exit 0))
