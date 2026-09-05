@echo off
setlocal
REM VENDOR ONLY — do not ship to customers.
REM Usage: issue-license.bat <machineId> [outFile] [customer] [days]
REM days defaults to 365. Pass perpetual as 4th arg for no expiry.
REM Calendar end date: use issue-license.ps1 -Until. See 授权签发说明.md

set JAVA_HOME=G:\Java\jdk-17.0.10
set PATH=%JAVA_HOME%\bin;%PATH%

set TOOLS_DIR=%~dp0
set ROOT=%TOOLS_DIR%..
cd /d "%ROOT%"

if "%~1"=="" (
  echo Usage: weavelay-tools\issue-license.bat ^<machineId^> [outFile] [customer] [days]
  echo machineId = 64-char hex from Settings -^> Licence -^> 机器指纹
  echo days = optional, default 365. Pass perpetual for no expiry.
  echo Output defaults to: weavelay-tools\issued\
  exit /b 2
)

set MACHINE=%~1
set OUT_ARG=%~2
set CUSTOMER=%~3
set DAYS=%~4

set ISSUED_DIR=%TOOLS_DIR%issued
if not exist "%ISSUED_DIR%" mkdir "%ISSUED_DIR%"

if "%OUT_ARG%"=="" (
  set OUT=%ISSUED_DIR%\license.weavelaylic
) else (
  echo %OUT_ARG%| findstr /R "^[A-Za-z]:\\ ^[\\/]" >nul
  if errorlevel 1 (
    set OUT=%ISSUED_DIR%\%OUT_ARG%
  ) else (
    set OUT=%OUT_ARG%
  )
)

set KEY=%TOOLS_DIR%keys\private.ed25519.b64
if not exist "%KEY%" (
  echo Private key not found: %KEY%
  exit /b 1
)

if "%DAYS%"=="" set DAYS=365

set ARGS=issue --machine %MACHINE% --out %OUT%
if not "%CUSTOMER%"=="" set ARGS=%ARGS% --customer %CUSTOMER%
if /I "%DAYS%"=="perpetual" (
  set ARGS=%ARGS% --perpetual
) else (
  set ARGS=%ARGS% --days %DAYS%
)

echo Issuing licence...
echo   machine=%MACHINE%
echo   out=%OUT%
if not "%CUSTOMER%"=="" echo   customer=%CUSTOMER%
if /I "%DAYS%"=="perpetual" (
  echo   expires=perpetual
) else (
  echo   days=%DAYS%
)
echo.
echo Please wait - first run may take ~1 minute to compile...
echo.

call mvn -q -pl weavelay-tools -am package -DskipTests
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)

set WEAVELAY_LICENSE_PRIVATE_KEY_FILE=%KEY%
call mvn -q -pl weavelay-tools exec:java "-Dexec.args=%ARGS%"
if errorlevel 1 (
  echo ISSUE FAILED
  exit /b 1
)

echo.
echo Done. Licence file:
echo   %OUT%
if exist "%OUT%" (
  echo.
  echo Open folder:
  explorer /select,"%OUT%"
)
exit /b 0
