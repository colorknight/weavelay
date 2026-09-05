@echo off
rem 桌面启动: JavaFX 仅 module-path, OCR/rapidocr 走 classpath (避免 LoadException).
setlocal EnableExtensions EnableDelayedExpansion

call "%~dp0_java17.cmd"
if errorlevel 1 exit /b 1

cd /d %~dp0

if not defined WEAVELAY_TMP set "WEAVELAY_TMP=%~dp0.tmp"
if not exist "%WEAVELAY_TMP%" mkdir "%WEAVELAY_TMP%"
set "TEMP=%WEAVELAY_TMP%"
set "TMP=%WEAVELAY_TMP%"
set "TMPDIR=%WEAVELAY_TMP%"

echo [1/2] package weavelay-app ...
call mvn -pl weavelay-app -am package -DskipTests -q -f pom.xml
if errorlevel 1 (
    echo package failed.
    exit /b 1
)

set "APP_DIR=%~dp0weavelay-app"
set "CP=%APP_DIR%\target\classes"

if not exist "%APP_DIR%\target\lib" (
    echo missing %APP_DIR%\target\lib — run build.bat first.
    exit /b 1
)

for %%F in ("%APP_DIR%\target\lib\*.jar") do (
    set "CP=!CP!;%%~fF"
)

if not exist "%APP_DIR%\target\javafx-lib" (
    echo missing %APP_DIR%\target\javafx-lib
    exit /b 1
)

echo [2/2] launch WeaveLayApp ...
echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java" ^
  -Djava.io.tmpdir="%WEAVELAY_TMP%" ^
  -Dprism.lcdtext=false ^
  --module-path "%APP_DIR%\target\javafx-lib" ^
  --add-modules javafx.controls,javafx.fxml ^
  -cp "!CP!" ^
  com.weavelay.app.WeaveLayApp

exit /b %ERRORLEVEL%
