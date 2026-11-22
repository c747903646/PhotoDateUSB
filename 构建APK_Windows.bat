@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
title Photo Date USB - Build Android APK
echo.
echo ====================================================
echo        Photo Date USB - Build Android APK
echo ====================================================
echo.
echo This is a one-time build on a Windows computer.
echo The resulting APK installs directly on your Xiaomi phone.
echo.
set "SDK_DIR=%ANDROID_HOME%"
if not defined SDK_DIR set "SDK_DIR=%ANDROID_SDK_ROOT%"
if not defined SDK_DIR set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK_DIR%\platforms\android-35\android.jar" (
    echo [ERROR] Android SDK Platform 35 was not found.
    echo Install Android Studio, then open SDK Manager and install:
    echo  Android SDK Platform 35
    echo  Android SDK Build-Tools 35
    echo  Android SDK Platform-Tools
    echo Expected SDK location: %SDK_DIR%
    echo.
    pause
    exit /b 1
)
set "ANDROID_HOME=%SDK_DIR%"
set "ANDROID_SDK_ROOT=%SDK_DIR%"

if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
)
if not defined JAVA_HOME (
    where java >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Java 17+ not found. Install Android Studio or JDK 17.
        pause
        exit /b 1
    )
)

set "GRADLE_DIR=%~dp0.local-build-tools"
set "GRADLE_BIN=%GRADLE_DIR%\gradle-8.9\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
    echo [1/3] Downloading Gradle 8.9, about 130 MB...
    if not exist "%GRADLE_DIR%" mkdir "%GRADLE_DIR%"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile '%GRADLE_DIR%\gradle-8.9.zip'; Expand-Archive -LiteralPath '%GRADLE_DIR%\gradle-8.9.zip' -DestinationPath '%GRADLE_DIR%' -Force"
    if errorlevel 1 goto :build_error
) else (
    echo [1/3] Gradle 8.9 ready.
)

echo.
echo [2/3] Generating standard Gradle wrapper...
call "%GRADLE_BIN%" --no-daemon wrapper --gradle-version 8.9 --distribution-type bin
if errorlevel 1 goto :build_error

echo.
echo [3/3] Building Android debug APK...
call "%GRADLE_BIN%" --no-daemon :app:assembleDebug
if errorlevel 1 goto :build_error

echo.
echo ====================================================
echo SUCCESS: Your installable APK is here:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk
echo ====================================================
explorer "%CD%\app\build\outputs\apk\debug"
pause
exit /b 0

:build_error
echo.
echo [ERROR] The build did not finish.
echo Check your internet connection, SDK Platform 35, and Java 17+.
echo If you want help, copy the LAST 30 LINES of this window.
pause
exit /b 1
