@echo off
setlocal
set "APP_ROOT=%~dp0"
if not exist "%APP_ROOT%doumi-shell.jar" set "APP_ROOT=%~dp0..\"
cd /d "%APP_ROOT%"
set "JAR=%APP_ROOT%doumi-shell.jar"
if not exist "%JAR%" set "JAR=%APP_ROOT%shell-end\target\shell-end-1.0.0-SNAPSHOT.jar"
if not exist "%JAR%" (
  echo Build the application first with scripts\package-release.ps1
  exit /b 1
)
if not defined DOUMI_DATA_DIR set "DOUMI_DATA_DIR=%APP_ROOT%data"
if not defined DOUMI_PORT set "DOUMI_PORT=80"
set "JAVA_EXE=java"
if exist "%APP_ROOT%runtime\bin\java.exe" set "JAVA_EXE=%APP_ROOT%runtime\bin\java.exe"
echo Open http://localhost:%DOUMI_PORT%/
"%JAVA_EXE%" -jar "%JAR%"
exit /b %ERRORLEVEL%
