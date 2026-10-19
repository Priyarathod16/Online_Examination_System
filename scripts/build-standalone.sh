#!/usr/bin/env bash
# Rebuilds standalone/OnlineExamSystem.html (+ index.html) from web/. Needs Node.js 18+ (only for building; the result needs nothing).
set -e
cd "$(dirname "$0")/.."
node scripts/build-standalone.mjs
