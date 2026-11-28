@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.13"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "DIST_ROOT=%GRADLE_USER_HOME%\wrapper\dists\streamforge-gradle-%GRADLE_VERSION%"
set "GRADLE_HOME=%DIST_ROOT%\gradle-%GRADLE_VERSION%"
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
  set "ZIP=%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip"
  if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip' -DestinationPath '%DIST_ROOT%' -Force"
)
call "%GRADLE_HOME%\bin\gradle.bat" -p "%APP_HOME%" %*
exit /b %ERRORLEVEL%
