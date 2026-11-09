@echo off
setlocal
set "BASE_DIR=%~dp0"
set "LOCAL_MVN=%BASE_DIR%.mvn\local\apache-maven-3.9.11\bin\mvn.cmd"
if exist "%LOCAL_MVN%" goto runlocal
where mvn >nul 2>nul
if not errorlevel 1 goto runinstalled
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $dir='%BASE_DIR%.mvn\local'; New-Item -ItemType Directory -Path $dir -Force | Out-Null; $zip=Join-Path $dir 'maven.zip'; Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath $dir -Force; Remove-Item $zip"
if errorlevel 1 exit /b 1
:runlocal
call "%LOCAL_MVN%" %*
exit /b %errorlevel%
:runinstalled
call mvn %*
exit /b %errorlevel%
