#!/usr/bin/env bash
# Compiles and runs every test that needs no database: Java unit tests, website HTTP/API tests and (if Node.js is
# installed) the front-end, standalone-mode and single-file-build tests.
set -e
cd "$(dirname "$0")/.."
rm -rf out/test-classes && mkdir -p out/test-classes
javac -encoding UTF-8 -d out/test-classes -sourcepath src/main/java:src/test/java \
  src/test/java/com/onlineexam/service/UnitTests.java src/test/java/com/onlineexam/service/DatabaseIntegrationTests.java \
  src/test/java/com/onlineexam/service/WebApiTests.java
status=0
java -cp "out/test-classes:lib/*" com.onlineexam.service.UnitTests || status=1
java -cp "out/test-classes:lib/*" com.onlineexam.service.WebApiTests || status=1
if command -v node >/dev/null 2>&1; then
  node src/test/js/web-tests.mjs || status=1
  node src/test/js/standalone-tests.mjs || status=1
  node scripts/build-standalone.mjs && node src/test/js/bundle-test.mjs || status=1
else
  echo "Node.js not found - skipping the front-end tests (src/test/js/web-tests.mjs)."
fi
exit $status
