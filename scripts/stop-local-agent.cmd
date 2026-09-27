@echo off
REM Thin wrapper so the agent can be stopped from cmd.exe / Explorer.
REM ASCII only on purpose: cmd.exe reads .cmd files in the OEM codepage.
REM
REM Why a wrapper: this machine has Windows PowerShell 5.1 only -- `pwsh`
REM (PowerShell 7) is NOT installed, so `pwsh -File ...` fails with
REM "'pwsh' is not recognized". The wrapper always calls `powershell`.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-local-agent.ps1" %*
