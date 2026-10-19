@echo off
chcp 65001 >nul
rem ? 27 ? JVM ????????? / ???? / ?????
rem ???Gradle ? test worker ??????? ClassNotFoundException?
rem       ????? ASCII ?????junction???????????
if not exist "D:\cg_tools" cmd /c mklink /J "D:\cg_tools" "D:\???\_tools" >nul
if not exist "D:\cg_work"  cmd /c mklink /J "D:\cg_work"  "D:\???" >nul
set "JAVA_HOME=D:\cg_tools\jdk17"
set "ANDROID_HOME=D:\cg_tools\android-sdk"
set "ANDROID_SDK_ROOT=D:\cg_tools\android-sdk"
set "GRADLE_USER_HOME=D:\cg_tools\gradle-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"
"D:\cg_tools\gradle-8.7\bin\gradle.bat" -p "D:\cg_work\ZjuCourtGrab" testDebugUnitTest --no-daemon --console=plain
