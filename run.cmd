@echo off
REM ============================================================
REM  Запуск lis-client-ktor: сборка + старт distribution-скрипта.
REM 用法: run.cmd           — обычный запуск
REM        run.cmd --debug  — старт с JDWP-агентом (suspend=y, порт 5005)
REM ============================================================
setlocal
cd /d "%~dp0"

echo [1/2] installDist...
call .\gradlew.bat installDist -x test
if errorlevel 1 (
    echo [error] installDist failed
    exit /b 1
)

if "%~1"=="--debug" (
    set "JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
    echo [debug] JVM встал на паузе. Attach Remote JVM Debug на localhost:5005
)

echo [2/2] run...
call .\build\install\lis-client-ktor\bin\lis-client-ktor.bat
endlocal
