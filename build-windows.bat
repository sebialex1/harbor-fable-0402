@echo off
REM =====================================================================
REM  Fable APK Build Script for Windows
REM  Usage: double-click build-windows.bat or run from cmd.
REM  Downloads JDK 17, Android SDK, NDK 27, CMake, MinGW-w64 automatically.
REM  Everything is cached under .build-cache\ so re-runs are fast.
REM =====================================================================

setlocal

set "REPO_ROOT=%~dp0"

REM =====================================================================
REM  1. Find PowerShell (Windows PowerShell ships with every Windows 10+)
REM =====================================================================
set "PS="

REM PowerShell 7 (pwsh.exe) — check PATH first
for /f "delims=" %%i in ('where pwsh 2^>nul') do (
    set "PS=%%i"
    goto :ps_found
)

REM Windows PowerShell (powershell.exe) — check PATH
for /f "delims=" %%i in ('where powershell 2^>nul') do (
    set "PS=%%i"
    goto :ps_found
)

REM Windows PowerShell — hardcoded path (every Windows 10/11 has it here)
if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" (
    set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
    goto :ps_found
)

REM Windows PowerShell — check by OS architecture (32-bit Windows on 64-bit)
if exist "%SystemRoot%\SysNative\WindowsPowerShell\v1.0\powershell.exe" (
    set "PS=%SystemRoot%\SysNative\WindowsPowerShell\v1.0\powershell.exe"
    goto :ps_found
)

echo.
echo ERROR: PowerShell not found on this system.
echo   PowerShell ships with Windows 10 and later. If you're on an older
echo   Windows version, install Windows Management Framework 5.1 from:
echo     https://www.microsoft.com/en-us/download/details.aspx?id=54616
echo   Or install PowerShell 7 from:
echo     https://github.com/PowerShell/PowerShell/releases
echo.
pause
exit /b 1

:ps_found
echo Using PowerShell: %PS%

REM =====================================================================
REM  2. Run the build script
REM =====================================================================
"%PS%" -ExecutionPolicy Bypass -File "%REPO_ROOT%build-windows.ps1"

if %errorlevel% neq 0 (
    echo.
    echo BUILD FAILED. See errors above.
    pause
    exit /b %errorlevel%
)

endlocal
