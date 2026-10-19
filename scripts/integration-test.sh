#!/usr/bin/env bash
# Runs the database integration tests against the MySQL database configured in config/db.properties.
# Prepare it first with:  mysql -u root -p < database/online_exam.sql
set -e
cd "$(dirname "$0")/.."
rm -rf out/test-classes && mkdir -p out/test-classes
javac -encoding UTF-8 -d out/test-classes -sourcepath src/main/java:src/test/java \
  src/test/java/com/onlineexam/service/UnitTests.java src/test/java/com/onlineexam/service/DatabaseIntegrationTests.java src/test/java/com/onlineexam/service/WebApiTests.java
java -cp "out/test-classes:lib/*" com.onlineexam.service.DatabaseIntegrationTests
