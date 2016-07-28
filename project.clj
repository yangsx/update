(defproject update "0.1.3"
  :description "A small utility for keeping local git repos up-to-date"
  :url "https://github.com/yangsx/update"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main update.core
  :aot :all
  :global-vars {*warn-on-reflection* true}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [me.raynes/fs "1.4.0"]])
