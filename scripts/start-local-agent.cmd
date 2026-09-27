@echo off
REM Double-click wrapper so the agent can be started from Explorer / cmd.exe
REM without typing any PowerShell syntax.
REM ASCII only on purpose: cmd.exe reads .cmd files in the OEM codepage,
REM so non-ASCII comments here would be mojibake.
REM
REM Usage from repo root:  scripts\start-local-agent.cmd
REM                        scripts\start-local-agent.cmd -Full
REM                        scripts\start-local-agent.cmd -WithFrontend
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local-agent.ps1" %*
