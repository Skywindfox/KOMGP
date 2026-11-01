@echo off
rem KOMGP debug launcher - shows the console output when KOMGP.exe fails.
rem Normal users should just double-click KOMGP.exe instead.
setlocal
cd /d "%~dp0"
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
echo Using Java: %JAVA%
"%JAVA%" -Xms256m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -cp "KOMGP.jar;lib\*" komgp.Main %*
echo.
echo Exit code: %ERRORLEVEL%
pause
endlocal
