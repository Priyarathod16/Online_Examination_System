#!/usr/bin/env bash
# Builds and starts the WEBSITE. Open http://localhost:8080/index.html afterwards.
# Requires lib/mysql-connector-j-*.jar and config/db.properties. Options: --port 8080 --host 127.0.0.1 (0.0.0.0 = LAN)
set -e
cd "$(dirname "$0")/.."
./scripts/build.sh
java -cp "out/classes:lib/*" com.onlineexam.web.WebServer "$@"
