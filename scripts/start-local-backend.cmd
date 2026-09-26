@echo off
REM Thin wrapper so the backend can be started from cmd.exe / Explorer without
REM typing any PowerShell syntax. ASCII only on purpose: cmd.exe reads .cmd files
REM in the OEM codepage, so non-ASCII comments here would be mojibake.
REM
REM Double-click this file, or from the repo root run:  scripts\start-local-backend.cmd
REM To pass a key:  scripts\start-local-backend.cmd -Key sk-xxx
REM Or put DEEPSEEK_API_KEY=sk-xxx in E:\py\.runtime\deepseek-key.txt and just double-click.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local-backend.ps1" %*
