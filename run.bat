@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title WeaveLay

if defined WEAVELAY_JAVA_HOME set "JAVA_HOME=%WEAVELAY_JAVA_HOME%"
if not defined JAVA_HOME if exist "G:\Java\jdk-17.0.10\bin\javac.exe" set "JAVA_HOME=G:\Java\jdk-17.0.10"
if not defined JAVA_HOME goto nojdk
if not exist "%JAVA_HOME%\bin\javac.exe" goto nojdk
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem C: 易满；ORT/OpenCV 抽原生库走工程盘临时目录
if not defined WEAVELAY_TMP set "WEAVELAY_TMP=%~dp0.tmp"
if not exist "%WEAVELAY_TMP%" mkdir "%WEAVELAY_TMP%"
if not defined WEAVELAY_HOME set "WEAVELAY_HOME=E:\tmp\weavelay\home"
if not exist "%WEAVELAY_HOME%" mkdir "%WEAVELAY_HOME%"
set "TEMP=%WEAVELAY_TMP%"
set "TMP=%WEAVELAY_TMP%"
set "TMPDIR=%WEAVELAY_TMP%"

echo [1/2] package weavelay-app ...
call mvn -pl weavelay-app -am package -DskipTests -q -f pom.xml
if errorlevel 1 goto fail

set "APP_DIR=%~dp0weavelay-app\"
set "CP=%APP_DIR%target\classes"
if not exist "%APP_DIR%target\lib" goto nolib
for %%F in ("%APP_DIR%target\lib\*.jar") do set "CP=!CP!;%%~fF"
if not exist "%APP_DIR%target\javafx-lib" goto nofx

echo [2/2] launch WeaveLayApp ...
echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -Xms512m -Xmx3g -XX:MaxMetaspaceSize=256m -Djava.io.tmpdir="%WEAVELAY_TMP%" -Dprism.lcdtext=false --module-path "%APP_DIR%target\javafx-lib" --add-modules javafx.controls,javafx.fxml,javafx.web -cp "!CP!" com.weavelay.app.WeaveLayApp
set ERR=%ERRORLEVEL%
if not "%ERR%"=="0" goto fail
pause
exit /b 0

:nojdk
echo ERROR: JDK 17 not found. Set WEAVELAY_JAVA_HOME e.g. G:\Java\jdk-17.0.10
goto fail

:nolib
echo ERROR: missing weavelay-app\target\lib - run build.bat first
goto fail

:nofx
echo ERROR: missing weavelay-app\target\javafx-lib - run build.bat first
goto fail

:fail
echo.
echo RUN FAILED - scroll up to copy errors
echo Use IDEA run config WeaveLay Desktop, not javafx:run
echo.
pause
exit /b 1
