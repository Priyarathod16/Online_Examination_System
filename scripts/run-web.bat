@echo off
rem Builds and starts the WEBSITE. Open http://localhost:8080/index.html afterwards.
rem Requires lib\mysql-connector-j-*.jar and config\db.properties. Options: --port 8080 --host 127.0.0.1 (0.0.0.0 = LAN)
cd /d "%~dp0.."
call scripts\build.bat
if errorlevel 1 exit /b 1
java -cp "out\classes;lib\*" com.onlineexam.web.WebServer %*
