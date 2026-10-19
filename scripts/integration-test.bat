@echo off
rem Runs the database integration tests against the MySQL database configured in config\db.properties.
cd /d "%~dp0.."
if exist out\test-classes rmdir /s /q out\test-classes
mkdir out\test-classes
javac -encoding UTF-8 -d out\test-classes -sourcepath src\main\java;src\test\java src\test\java\com\onlineexam\service\UnitTests.java src\test\java\com\onlineexam\service\DatabaseIntegrationTests.java
if errorlevel 1 exit /b 1
java -cp "out\test-classes;lib\*" com.onlineexam.service.DatabaseIntegrationTests
