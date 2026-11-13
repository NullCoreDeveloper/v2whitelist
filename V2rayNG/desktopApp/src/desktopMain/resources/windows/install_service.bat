@echo off
chcp 65001 >nul
echo ========================================================
echo   Установка системной службы ProBel (v2w-service)
echo ========================================================
echo.
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [i] Требуются права администратора для регистрации службы Windows.
    echo [i] Запрашиваем повышение привилегий UAC...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
set "BIN=%~dp0bin\v2w-service.exe"
if not exist "%BIN%" set "BIN=%~dp0app\resources\bin\v2w-service.exe"
if not exist "%BIN%" set "BIN=%~dp0v2w-service.exe"
if not exist "%BIN%" (
    echo [!] Ошибка: v2w-service.exe не найден.
    pause
    exit /b 1
)
echo [*] Установка и запуск службы: "%BIN%"
"%BIN%" -install
if %errorLevel% equ 0 (
    echo.
    echo [+] Служба ProBel VPN Service успешно установлена и запущена!
    echo [+] Теперь приложение можно запускать без прав администратора.
) else (
    echo.
    echo [!] Ошибка установки службы (код: %errorLevel%).
)
echo.
pause

