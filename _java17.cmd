@echo off
rem Shared JDK 17 setup for WeaveLay (call from build.bat / run.bat).
rem Override: set WEAVELAY_JAVA_HOME before running, or set a valid JAVA_HOME with javac.

if defined WEAVELAY_JAVA_HOME (
    set "JAVA_HOME=%WEAVELAY_JAVA_HOME%"
    goto :apply
)

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" goto :apply
)

if exist "G:\Java\jdk-17.0.10\bin\javac.exe" (
    set "JAVA_HOME=G:\Java\jdk-17.0.10"
    goto :apply
)

for /d %%D in ("G:\Java\jdk-17*") do (
    if exist "%%D\bin\javac.exe" (
        set "JAVA_HOME=%%~fD"
        goto :apply
    )
)

for /d %%D in ("%USERPROFILE%\.jdks\*") do (
    if exist "%%D\bin\javac.exe" (
        set "JAVA_HOME=%%~fD"
        goto :apply
    )
)

echo [ERROR] JDK 17 not found. Install JDK 17 or set WEAVELAY_JAVA_HOME, e.g.:
echo   set WEAVELAY_JAVA_HOME=G:\Java\jdk-17.0.10
exit /b 1

:apply
set "PATH=%JAVA_HOME%\bin;%PATH%"
exit /b 0
