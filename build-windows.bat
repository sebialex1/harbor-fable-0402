@echo off
REM =====================================================================
REM  Fable APK Build Script for Windows
REM  Usage: double-click build-windows.bat or run from cmd.
REM  Downloads JDK 17, Android SDK, NDK 27, CMake, MinGW-w64 automatically.
REM  Everything is cached under .build-cache\ so re-runs are fast.
REM =====================================================================

setlocal

set REPO_ROOT=%~dp0
set CACHE=%REPO_ROOT%.build-cache
set SDK_ROOT=%CACHE%\android-sdk
set JDK_ROOT=%CACHE%\jdk17
set MINGW_ROOT=%CACHE%\mingw64

if not exist "%CACHE%" mkdir "%CACHE%"

REM =====================================================================
REM  1. Check for PowerShell (required for the download/extract logic)
REM =====================================================================
where pwsh >nul 2>&1
if %errorlevel% equ 0 (
    set PS=pwsh
) else (
    where powershell >nul 2>&1
    if %errorlevel% equ 0 (
        set PS=powershell
    ) else (
        echo ERROR: PowerShell not found. Install PowerShell 7 or use Windows PowerShell.
        exit /b 1
    )
)

REM =====================================================================
REM  2. Run the PowerShell build script
REM =====================================================================
%PS% -ExecutionPolicy Bypass -File "%REPO_ROOT%build-windows.ps1"

if %errorlevel% neq 0 (
    echo.
    echo BUILD FAILED. See errors above.
    exit /b %errorlevel%
)

endlocal
