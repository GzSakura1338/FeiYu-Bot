@echo off
chcp 65001 >nul
cd /d "%~dp0napcat"
call .\launcher-user.bat %*
