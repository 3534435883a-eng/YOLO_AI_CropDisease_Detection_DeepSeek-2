@echo off
set "WORKSPACE=%~dp0..\..\..\.."
set "BLENDER_USER_CONFIG=%WORKSPACE%\.runtime\blender-user\config"
set "BLENDER_USER_SCRIPTS=%WORKSPACE%\.runtime\blender-user\scripts"
start "Greenhouse Blender" "%WORKSPACE%\.runtime\blender\blender-5.2.2-windows-x64\blender.exe" "%~dp0..\..\assets\greenhouse\greenhouse-v2.blend"
