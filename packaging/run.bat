@echo off
rem Malody 4K launcher for Windows
setlocal
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
  echo.
  echo [ERROR] Java 17 or newer was not found.
  echo         Install a JDK/JRE 17+ from https://adoptium.net/ and try again,
  echo         or set JAVA_HOME to your JDK folder.
  echo.
  pause
  exit /b 1
)

"%JAVA%" -Xms256m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=UTF-8 ^
  -cp "Malody4K.jar;lib\*" rhythm.Main %*

if errorlevel 1 (
  echo.
  echo [ERROR] Game exited with an error. See the messages above.
  pause
)
endlocal
