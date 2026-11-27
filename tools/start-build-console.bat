@echo off
chcp 65001 >nul
cd /d "%~dp0.."
E:\toolchains\python310\python.exe tools\build_console.py --port 8766 --open
pause
