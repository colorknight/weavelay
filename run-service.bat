@echo off
setlocal
cd /d %~dp0
call _java17.cmd
if errorlevel 1 goto :fail
if not defined WEAVELAY_TMP set "WEAVELAY_TMP=%~dp0.tmp"
if not exist "%WEAVELAY_TMP%" mkdir "%WEAVELAY_TMP%"
set "TEMP=%WEAVELAY_TMP%"
set "TMP=%WEAVELAY_TMP%"
set "TMPDIR=%WEAVELAY_TMP%"
call mvn -pl weavelay-service -am spring-boot:run "-Dspring-boot.run.jvmArguments=-Djava.io.tmpdir=%WEAVELAY_TMP%"
if errorlevel 1 goto :fail
exit /b 0
:fail
pause
exit /b 1
