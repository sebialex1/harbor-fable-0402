<#
.SYNOPSIS
    Build the Fable debug APK on Windows without Android Studio.
.DESCRIPTION
    Downloads the Android SDK command-line tools, JDK 17, MinGW-w64 (for the
    D3D11 test .exe), NDK 27 and CMake 3.22.1, then runs the Gradle build.
    Everything is cached under .build-cache/ so re-runs are fast.
.NOTES
    - arm64-v8a only (matches the app's ndk.abiFilters).
    - Produces app/build/outputs/apk/debug/app-debug.apk.
#>

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Cache    = Join-Path $RepoRoot '.build-cache'
$SdkRoot  = Join-Path $Cache 'android-sdk'
$JdkRoot  = Join-Path $Cache 'jdk17'
$MingwRoot = Join-Path $Cache 'mingw64'

New-Item -ItemType Directory -Force -Path $Cache | Out-Null

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# ---------------------------------------------------------------------------
# 1. JDK 17 (Temurin)
# ---------------------------------------------------------------------------
$JavaExe = Join-Path $JdkRoot 'bin\java.exe'
if (-not (Test-Path $JavaExe)) {
    Write-Step 'Downloading JDK 17 (Temurin)'
    # Detect architecture
    $arch = if ([Environment]::Is64BitOperatingSystem) { 'x64' } else { 'x86' }
    # Use the Adoptium API to get the latest JDK 17 LTS for Windows
    $apiUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/$arch/jdk/hotspot/normal/eclipse"
    $zip = Join-Path $Cache 'jdk17.zip'
    Write-Host "  Downloading from Adoptium..."
    Invoke-WebRequest -Uri $apiUrl -OutFile $zip -UseBasicParsing
    Write-Host "  Extracting..."
    Expand-Archive -Path $zip -DestinationPath $Cache -Force
    # The zip extracts to a folder like jdk-17.0.x+y; rename it
    $extracted = Get-ChildItem -Path $Cache -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1
    if ($extracted) {
        if (Test-Path $JdkRoot) { Remove-Item -Recurse -Force $JdkRoot }
        Rename-Item -Path $extracted.FullName -NewName 'jdk17'
    }
    Remove-Item $zip -Force
}
$env:JAVA_HOME = $JdkRoot
$env:PATH = "$JdkRoot\bin;$env:PATH"
Write-Host "  JAVA_HOME = $JdkRoot"

# ---------------------------------------------------------------------------
# 2. Android SDK command-line tools
# ---------------------------------------------------------------------------
$SdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
$SdkManagerAlt = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager'
if (-not (Test-Path $SdkManager) -and -not (Test-Path $SdkManagerAlt)) {
    Write-Step 'Downloading Android SDK command-line tools'
    $zip = Join-Path $Cache 'cmdline-tools.zip'
    $url = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Write-Host "  Extracting..."
    $tempExtract = Join-Path $Cache 'cmdline-tools-tmp'
    Expand-Archive -Path $zip -DestinationPath $tempExtract -Force
    # Move cmdline-tools/ to SdkRoot/cmdline-tools/latest
    $inner = Join-Path $tempExtract 'cmdline-tools'
    $target = Join-Path $SdkRoot 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item -Path $inner -Destination $target
    Remove-Item -Recurse -Force $tempExtract
    Remove-Item $zip -Force
}
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:PATH = "$SdkRoot\cmdline-tools\latest\bin;$SdkRoot\platform-tools;$env:PATH"

# Use the .bat or the shell script depending on what exists
$sdkCmd = if (Test-Path $SdkManager) { $SdkManager } else { $SdkManagerAlt }

# ---------------------------------------------------------------------------
# 3. SDK packages (NDK, CMake, platform, build-tools)
# ---------------------------------------------------------------------------
$NdkPath = Join-Path $SdkRoot "ndk\27.0.12077973"
$CmakePath = Join-Path $SdkRoot "cmake\3.22.1"
$PlatformPath = Join-Path $SdkRoot "platforms\android-34"
$BuildToolsPath = Join-Path $SdkRoot "build-tools\34.0.0"

$needPackages = $false
if (-not (Test-Path $NdkPath)) { $needPackages = $true }
if (-not (Test-Path $CmakePath)) { $needPackages = $true }
if (-not (Test-Path $PlatformPath)) { $needPackages = $true }
if (-not (Test-Path $BuildToolsPath)) { $needPackages = $true }

if ($needPackages) {
    Write-Step 'Installing Android SDK packages (NDK 27, CMake 3.22.1, platform-34, build-tools 34)'
    # sdkmanager accepts licenses automatically with --licenses
    $packages = @(
        'platforms;android-34',
        'build-tools;34.0.0',
        'ndk;27.0.12077973',
        'cmake;3.22.1'
    )
    foreach ($pkg in $packages) {
        Write-Host "  Installing $pkg ..."
        & $sdkCmd --licenses 2>$null | Out-Null
        & $sdkCmd $pkg 2>&1 | ForEach-Object { Write-Host "    $_" }
    }
} else {
    Write-Step 'SDK packages already installed'
}

# ---------------------------------------------------------------------------
# 4. MinGW-w64 (for the D3D11 test .exe)
# ---------------------------------------------------------------------------
$MingwGxx = Join-Path $MingwRoot 'bin\x86_64-w64-mingw32-g++.exe'
if (-not (Test-Path $MingwGxx)) {
    Write-Step 'Downloading MinGW-w64'
    # WinLibs UCRT build (no MSVCRT dependency issues)
    $url = 'https://github.com/brechtsanders/winlibs_mingw/releases/download/14.2.0posix-19.1.1ucrt-12.0.0-ucrt-r2/winlibs-x86_64-posix-seh-gcc-14.2.0-mingw-w64ucrt-12.0.0-r2.zip'
    $zip = Join-Path $Cache 'mingw64.zip'
    Write-Host "  Downloading from WinLibs..."
    try {
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    } catch {
        # Fallback: try a different mirror
        $url2 = 'https://github.com/brechtsanders/winlibs_mingw/releases/download/13.2.0posix-18.1.6ucrt-12.0.0-ucrt-r2/winlibs-x86_64-posix-seh-gcc-13.2.0-mingw-w64ucrt-12.0.0-r2.zip'
        Write-Host "  Primary download failed, trying fallback..."
        Invoke-WebRequest -Uri $url2 -OutFile $zip -UseBasicParsing
    }
    Write-Host "  Extracting..."
    $tempExtract = Join-Path $Cache 'mingw-tmp'
    Expand-Archive -Path $zip -DestinationPath $tempExtract -Force
    $mingwDir = Get-ChildItem -Path $tempExtract -Directory | Where-Object { $_.Name -like 'mingw64' } | Select-Object -First 1
    if ($mingwDir) {
        if (Test-Path $MingwRoot) { Remove-Item -Recurse -Force $MingwRoot }
        Move-Item -Path $mingwDir.FullName -Destination $MingwRoot
    }
    Remove-Item -Recurse -Force $tempExtract
    Remove-Item $zip -Force
}
$env:PATH = "$MingwRoot\bin;$env:PATH"
Write-Host "  MinGW-w64 g++ = $MingwGxx"

# ---------------------------------------------------------------------------
# 5. Build the D3D11 test .exe
# ---------------------------------------------------------------------------
Write-Step 'Building D3D11 test (MinGW-w64)'
$Cross = 'x86_64-w64-mingw32-'
$Gxx = Join-Path $MingwRoot "bin\$Cross`g++.exe"
$D3dTestDir = Join-Path $RepoRoot 'container_tools\d3d11-test'
$D3dExe = Join-Path $D3dTestDir 'build\d3d11-test.exe'
$D3dTarget = Join-Path $RepoRoot 'app\src\main\assets\container_tools\d3d11-test.exe'

if (-not (Test-Path $D3dExe)) {
    New-Item -ItemType Directory -Force -Path (Join-Path $D3dTestDir 'build') | Out-Null
    & $Gxx -O2 -Wall -Wextra -std=c++17 -fno-exceptions -fno-rtti `
        -mwindows -municode -static -static-libgcc -static-libstdc++ -s `
        -o $D3dExe (Join-Path $D3dTestDir 'src\main.cpp') -ld3d11
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  D3D11 test build failed (non-fatal; the APK will still build)" -ForegroundColor Yellow
    } else {
        Write-Host "  Built d3d11-test.exe"
    }
}

if (Test-Path $D3dExe) {
    New-Item -ItemType Directory -Force -Path (Split-Path $D3dTarget) | Out-Null
    Copy-Item -Path $D3dExe -Destination $D3dTarget -Force
    Write-Host "  Copied to app/src/main/assets/container_tools/"
} else {
    Write-Host "  Skipping d3d11-test.exe (build failed or MinGW not available)" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 6. Build the APK
# ---------------------------------------------------------------------------
Write-Step 'Building debug APK'
$Gradlew = Join-Path $RepoRoot 'gradlew.bat'
if (-not (Test-Path $Gradlew)) {
    Write-Error 'gradlew.bat not found. Make sure you extracted the repo zip fully.'
    exit 1
}

& $Gradlew 'assembleDebug' '--no-daemon' '--stacktrace'
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Gradle build failed!'
    exit $LASTEXITCODE
}

# ---------------------------------------------------------------------------
# 7. Report
# ---------------------------------------------------------------------------
$Apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $Apk) {
    $size = [math]::Round((Get-Item $Apk).Length / 1MB, 1)
    Write-Host ""
    Write-Host "=== BUILD SUCCESSFUL ===" -ForegroundColor Green
    Write-Host "  APK: $Apk" -ForegroundColor White
    Write-Host "  Size: $size MB" -ForegroundColor White
    Write-Host ""
    Write-Host "  Install with:" -ForegroundColor Gray
    Write-Host "    adb install `"$Apk`"" -ForegroundColor Gray
} else {
    Write-Error "Build reported success but APK not found at expected path: $Apk"
    exit 1
}
