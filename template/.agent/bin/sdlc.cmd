@echo off
rem Fast launcher for the IDE hooks on Windows: runs the compiled copy in .agent\.build.
rem The first run (or a run after the sources changed) goes through the bootstrap, which compiles.
setlocal
set "AGENT_DIR=%~dp0.."
if exist "%AGENT_DIR%\.build\sdlc\Main.class" if exist "%AGENT_DIR%\.build\.stamp" (
  java -cp "%AGENT_DIR%\.build" sdlc.Main %*
  exit /b %ERRORLEVEL%
)
java "%AGENT_DIR%\Sdlc.java" %*
exit /b %ERRORLEVEL%
