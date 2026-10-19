@echo off
rem Rebuilds standalone\OnlineExamSystem.html (+ index.html) from web\. Needs Node.js 18+ (only for building).
cd /d "%~dp0.."
node scripts\build-standalone.mjs
