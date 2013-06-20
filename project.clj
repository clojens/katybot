(defproject

  katybot
  "0.3.1-SNAPSHOT"

  :description "Campfire bot written in clojure, forked by supersym."

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [http.async.client "0.5.2"]
                 [org.slf4j/slf4j-nop "1.7.5"]
                 [org.clojure/data.json "0.2.2"]]

  :repl-init katybot.repl

  ;:repl-init katybot.repl-api

  :jvm-opts ["-Dfile.encoding=UTF-8"])
