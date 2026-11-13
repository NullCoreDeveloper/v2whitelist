@echo off
chcp 65001 >nul
echo ========================================================
echo   Удаление системной службы ProBel (v2w-service)
echo ========================================================
echo.
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [i] Требуются права администратора для удаления службы Windows.
    echo [i] Запрашиваем повышение привилегий UAC...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
set "BIN=%~dp0bin\v2w-service.exe"
if not exist "%BIN%" set "BIN=%~dp0app\resources\bin\v2w-service.exe"
if not exist "%BIN%" set "BIN=%~dp0v2w-service.exe"
if not exist "%BIN%" (
    echo [*] Попытка остановить и удалить через sc.exe...
    sc stop v2w-service >nul 2>&1
    sc delete v2w-service >nul 2>&1
    echo [+] Служба удалена из системы.
    pause
    exit /b 0
)
echo [*] Остановка и удаление службы...
"%BIN%" -uninstall
echo.
echo [+] Служба ProBel VPN Service успешно удалена из системы.
echo.
pause

