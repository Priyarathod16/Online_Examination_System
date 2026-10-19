@echo off
rem Compiles and runs every test that needs no database (Java unit tests, website API tests, front-end tests if Node.js exists).
cd /d "%~dp0.."
if exist out\test-classes rmdir /s /q out\test-classes
mkdir out\test-classes
javac -encoding UTF-8 -d out\test-classes -sourcepath src\main\java;src\test\java src\test\java\com\onlineexam\service\UnitTests.java src\test\java\com\onlineexam\service\DatabaseIntegrationTests.java src\test\java\com\onlineexam\service\WebApiTests.java
if errorlevel 1 exit /b 1
set STATUS=0
java -cp "out\test-classes;lib\*" com.onlineexam.service.UnitTests || set STATUS=1
java -cp "out\test-classes;lib\*" com.onlineexam.service.WebApiTests || set STATUS=1
where node >nul 2>nul
if errorlevel 1 (echo Node.js not found - skipping the front-end tests.) else (node src\test\js\web-tests.mjs || set STATUS=1 & node src\test\js\standalone-tests.mjs || set STATUS=1 & node scripts\build-standalone.mjs & node src\test\js\bundle-test.mjs || set STATUS=1)
exit /b %STATUS%
