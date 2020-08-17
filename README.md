# db-fuzzer

A SQL database fuzzer. Supports running both hand-written queries as well as queries generated from BNF.

## Code walkthrough
  ./src/clj/db_fuzzer/core.clj contains the test runner engine and CLI-related functionality.
  ./src/clj/db_fuzzer/introspect.clj contains code for reading in metadata (such as tables and columns) from the target database for use in query construction.
  ./src/cljc/db_fuzzer/generate.cljc contains the configuration map of available generators (used in core.clj) and defines a couple of hand-written query generator functions.
  ./src/cljc/db_fuzzer/sql_spec.cljc contains SQL BNF and code that parses that BNF and turns each rule into a generator function.

## Development Mode

### Start Cider from Emacs:

```
Open ./src/clj/db_fuzzer/core.clj
M-x cider-jack-in
C-c C-k to load the current buffer into the REPL
```

### Run application:
To print the help:
```
lein run -- --help
```

Example run agains the "wordpress" database with user test:
```
lein run -t mysql -d wordpress --user test -p test -n 100 -g ast-select
```

## Production Mode
```
lein clean && lein uberjar
# I've provided example arguments
java -jar ./target/db-fuzzer-0.1.0-SNAPSHOT-standalone.jar -t mysql -d wordpress --user test -p test -n 100 -g ast-select
```

The advantage of the uberjar is that it's configured to ahead-of-time (AOT) compile all the code,
which means that the SQL BNF will get parsed and the generator functions will be generated only once at compile time.
This should give a speed-up compared with using lein run.