#!/usr/bin/env bash
# Builds and starts the application. Requires lib/mysql-connector-j-*.jar and config/db.properties.
set -e
cd "$(dirname "$0")/.."
./scripts/build.sh
java -cp "out/classes:lib/*" com.onlineexam.app.Main
