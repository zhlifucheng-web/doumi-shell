@echo off
setlocal EnableExtensions
rem Fallback when "Doumi Shell.exe" reports Failed to launch JVM (native launcher / jvm.dll).
set "ROOT=%~dp0"
set "JAVA_EXE=%ROOT%runtime\bin\java.exe"
set "JAR=%ROOT%app\doumi-shell.jar"
if not exist "%JAR%" set "JAR=%ROOT%doumi-shell.jar"
if not exist "%JAR%" (
  echo Cannot find doumi-shell.jar under "%ROOT%"
  pause
  exit /b 1
)
if not exist "%JAVA_EXE%" (
  echo Bundled runtime not found: "%JAVA_EXE%"
  echo Repair the installation or install JDK 17 and set JAVA_HOME.
  pause
  exit /b 1
)
if not exist "%ROOT%runtime\bin\server\jvm.dll" (
  echo Missing runtime\bin\server\jvm.dll. Antivirus may have quarantined it; reinstall after whitelisting the install folder.
  pause
  exit /b 1
)
if not defined DOUMI_PORT set "DOUMI_PORT=80"
start "" "http://localhost:%DOUMI_PORT%/"
"%JAVA_EXE%" -Ddoumi.desktop=true -Djava.awt.headless=false ^
  --add-opens=java.base/java.lang=ALL-UNNAMED ^
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED ^
  -Dfile.encoding=UTF-8 ^
  -jar "%JAR%" %*
exit /b %ERRORLEVEL%
