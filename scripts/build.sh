#!/usr/bin/env bash
# Compiles the application into out/classes (needs a JDK 8+ with javac on the PATH).
set -e
cd "$(dirname "$0")/.."
rm -rf out/classes && mkdir -p out/classes
javac -encoding UTF-8 -d out/classes -sourcepath src/main/java src/main/java/com/onlineexam/app/Main.java src/main/java/com/onlineexam/model/Admin.java src/main/java/com/onlineexam/web/WebServer.java
echo "Build OK -> out/classes"
