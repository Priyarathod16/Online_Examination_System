@echo off
rem Builds and starts the application. Requires lib\mysql-connector-j-*.jar and config\db.properties.
cd /d "%~dp0.."
call scripts\build.bat
if errorlevel 1 exit /b 1
java -cp "out\classes;lib\*" com.onlineexam.app.Main
