@echo off
chcp 65001 >nul
title dafeiyu
cd /d "%~dp0dafeiyu"
call .\gradlew.bat run --console=plain -q
pause
