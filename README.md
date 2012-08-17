# update

This is a small utility in Clojure to keep your local git repos updated.

## Usage

Suppose you have dozens and even hundreds git repos from remote
repositories, say github service. Keeping them all up-to-date soon
becomes a drudgery.

If you are using leiningen 2, you can run it in a console with:

    $ cd update
    $ lein run your-repos

Or you can simply call it with a standalone jar in a console:

    $ java -jar update-0.1.0-SNAPSHOT-standalone.jar your-repos

where all the directories in _your-repos_ will be checked for updates.

## License

Copyright © 2012 Yang Shouxun

Distributed under the Eclipse Public License, the same as Clojure.
