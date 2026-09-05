@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title WeaveLay Build

if defined WEAVELAY_JAVA_HOME set "JAVA_HOME=%WEAVELAY_JAVA_HOME%"
if not defined JAVA_HOME if exist "G:\Java\jdk-17.0.10\bin\javac.exe" set "JAVA_HOME=G:\Java\jdk-17.0.10"
if not defined JAVA_HOME goto nojdk
if not exist "%JAVA_HOME%\bin\javac.exe" goto nojdk
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo === Java for this build ===
echo JAVA_HOME=%JAVA_HOME%
java -version 2>&1
javac -version 2>&1
echo.

echo [1/3] Clear cached com.weavelay in .m2 ...
set "M2_WEAVE=%USERPROFILE%\.m2\repository\com\weavelay"
if exist "%M2_WEAVE%" rmdir /s /q "%M2_WEAVE%"
if exist "%M2_WEAVE%" goto fail
echo Done.

echo.
echo [2/3] mvn clean install ...
call mvn clean install -DskipTests -U -f pom.xml
if errorlevel 1 goto fail

echo.
echo [3/3] Done. Run desktop: run.bat
echo.
pause
exit /b 0

:nojdk
echo ERROR: JDK 17 not found. Set WEAVELAY_JAVA_HOME e.g. G:\Java\jdk-17.0.10
goto fail

:fail
echo.
echo BUILD FAILED - scroll up to copy errors
echo.
pause
exit /b 1
