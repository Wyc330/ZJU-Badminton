@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem =============================================================================
rem  把本地仓库推送到 https://github.com/Wyc330/ZJU-Badminton
rem
rem  用法:
rem      scripts\push-to-github.cmd ^<你的 GitHub Token^>
rem
rem  Token 需要具备的权限（fine-grained PAT）:
rem      Repository access : Wyc330/ZJU-Badminton
rem      Contents          : Read and write      ← 必须有，否则 git push 会 403
rem
rem  如果用 classic token（ghp_ 开头）: 勾选 repo 即可。
rem =============================================================================

set "TOKEN=%~1"
if "%TOKEN%"=="" (
  echo.
  echo  用法: scripts\push-to-github.cmd ^<GitHub Token^>
  echo.
  echo  fine-grained PAT 必须勾选: Contents = Read and write
  echo.
  exit /b 1
)

set "REPO=%~dp0.."
set "GIT=D:\抢场地\_tools\git\cmd\git.exe"
if not exist "%GIT%" set "GIT=git"

pushd "%REPO%"
echo [1/4] 配置远端（带 token，推完会重置成干净 URL）
"%GIT%" remote remove origin >nul 2>&1
"%GIT%" remote add origin "https://x-access-token:%TOKEN%@github.com/Wyc330/ZJU-Badminton.git"

echo [2/4] 推送 main
"%GIT%" push -u origin main
if errorlevel 1 goto FAIL

echo [3/4] 推送 tag v0.9.0alpha1
"%GIT%" push origin v0.9.0alpha1

echo [4/4] 还原远端地址（不把 token 留在 .git/config 里）
"%GIT%" remote set-url origin "https://github.com/Wyc330/ZJU-Badminton.git"
popd
echo.
echo 完成: https://github.com/Wyc330/ZJU-Badminton
exit /b 0

:FAIL
echo.
echo 推送失败。常见原因：
echo   1) token 没有 Contents: Read and write 权限
echo   2) token 已过期
echo   3) 仓库不属于该 token 的账号 / 没有协作权限
popd
exit /b 1
