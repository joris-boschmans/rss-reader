@echo off
setlocal enabledelayedexpansion

rem --- Find a JDK -------------------------------------------------------
set "JAVA_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"

if not defined JAVA_BIN (
  for /d %%D in ("%LOCALAPPDATA%\Programs\IntelliJ IDEA*") do (
    if exist "%%D\jbr\bin\java.exe" set "JAVA_BIN=%%D\jbr\bin\java.exe"
  )
)
if not defined JAVA_BIN (
  for /d %%D in ("%ProgramFiles%\JetBrains\*") do (
    if exist "%%D\jbr\bin\java.exe" set "JAVA_BIN=%%D\jbr\bin\java.exe"
  )
)
if not defined JAVA_BIN (
  where java >nul 2>&1 && set "JAVA_BIN=java"
)
if not defined JAVA_BIN (
  echo Could not find Java.
  echo Set JAVA_HOME to a JDK 21 or newer, then run this again.
  exit /b 1
)

rem --- Find Maven (only needed to build) --------------------------------
set "MVN_BIN="
where mvn >nul 2>&1 && set "MVN_BIN=mvn"
if not defined MVN_BIN (
  for /d %%D in ("%LOCALAPPDATA%\Programs\IntelliJ IDEA*") do (
    if exist "%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN_BIN=%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
  )
)

set "JAR=%~dp0target\rss-reader.jar"
if not exist "%JAR%" (
  if not defined MVN_BIN (
    echo Could not find Maven, and target\rss-reader.jar has not been built yet.
    echo Install Maven ^(or open this folder in IntelliJ^) and run: mvn -q package
    exit /b 1
  )
  echo Building ^(first run only^)...
  set "JAVA_HOME=%JAVA_BIN:\bin\java.exe=%"
  call "%MVN_BIN%" -q -DskipTests package || exit /b 1
)

"%JAVA_BIN%" -jar "%JAR%" %*
