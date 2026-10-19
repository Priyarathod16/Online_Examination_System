@echo off
rem Compiles the application into out\classes (needs a JDK 8+ with javac on the PATH).
cd /d "%~dp0.."
if exist out\classes rmdir /s /q out\classes
mkdir out\classes
javac -encoding UTF-8 -d out\classes -sourcepath src\main\java src\main\java\com\onlineexam\app\Main.java src\main\java\com\onlineexam\model\Admin.java src\main\java\com\onlineexam\web\WebServer.java
if errorlevel 1 exit /b 1
echo Build OK -^> out\classes
