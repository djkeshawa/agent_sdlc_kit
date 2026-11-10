@echo off
rem Fast launcher for the IDE hooks on Windows: runs the compiled copy in .agent\.build.
rem The first run (or a run after the sources changed) goes through the bootstrap, which compiles.
setlocal
set "AGENT_DIR=%~dp0.."
set "FAST="
if exist "%AGENT_DIR%\.build\sdlc\Main.class" if exist "%AGENT_DIR%\.build\.stamp" set "FAST=1"
if defined FAST java -cp "%AGENT_DIR%\.build" sdlc.Main %*
if not defined FAST java "%AGENT_DIR%\Sdlc.java" %*
set "RC=%ERRORLEVEL%"
rem The guard never fails open: 0 = allow or ask, 2 = refuse, anything else = it broke, so refuse.
if /i not "%~1 %~2"=="hook pre-tool-use" exit /b %RC%
if "%RC%"=="0" exit /b 0
if "%RC%"=="2" exit /b 2
echo Refused by project guardrail: the guard itself failed, exit %RC%. Ask a human to run: java .agent/Sdlc.java selftest 1>&2
exit /b 2
