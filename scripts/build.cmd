@echo off
chcp 65001 >nul
rem ?? debug APK????app\build\outputs\apk\debug\app-debug.apk?
call "D:\???\_tools\build-debug.cmd" -p "%~dp0.." assembleDebug --no-daemon --console=plain
