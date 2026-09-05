@echo off
REM WeaveLay customer launcher (portable). Do not ship weavelay-tools.
setlocal EnableExtensions EnableDelayedExpansion
set "APP_HOME=%~dp0"
if not defined WEAVELAY_HOME set "WEAVELAY_HOME=%LOCALAPPDATA%\WeaveLay"
if not exist "%WEAVELAY_HOME%" mkdir "%WEAVELAY_HOME%"
if not defined WEAVELAY_TMP set "WEAVELAY_TMP=%APP_HOME%tmp"
if not exist "%WEAVELAY_TMP%" mkdir "%WEAVELAY_TMP%"
set "TEMP=%WEAVELAY_TMP%"
set "TMP=%WEAVELAY_TMP%"
cd /d "%APP_HOME%"

if not exist "%APP_HOME%runtime\bin\java.exe" (
  echo ERROR: missing runtime\bin\java.exe
  pause
  exit /b 1
)
if not exist "%APP_HOME%app\weavelay-app.jar" (
  echo ERROR: missing app\weavelay-app.jar
  pause
  exit /b 1
)

set "CP=%APP_HOME%app\weavelay-app.jar"
for %%F in ("%APP_HOME%app\lib\*.jar") do set "CP=!CP!;%%~fF"

REM FormulaNet ONNX beside OCR models (models\pp-formula)
if not defined WEAVELAY_FORMULA_ROOT set "WEAVELAY_FORMULA_ROOT=%APP_HOME%models\pp-formula"

echo Starting WeaveLay...
"%APP_HOME%runtime\bin\java.exe" ^
  -Xms512m -Xmx3g -XX:MaxMetaspaceSize=256m ^
  -Djava.io.tmpdir="%WEAVELAY_TMP%" ^
  -Dweavelay.formula.root="%WEAVELAY_FORMULA_ROOT%" ^
  -Dprism.lcdtext=false ^
  --module-path "%APP_HOME%app\javafx-lib" ^
  --add-modules javafx.controls,javafx.fxml,javafx.web,javafx.graphics,javafx.base ^
  -cp "!CP!" ^
  com.weavelay.app.WeaveLayApp
set ERR=%ERRORLEVEL%
if not "%ERR%"=="0" (
  echo.
  echo Exit code %ERR%
  pause
)
exit /b %ERR%
