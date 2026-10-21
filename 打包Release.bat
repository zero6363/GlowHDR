@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo   GlowHDR release 打包脚本
echo ============================================
echo.

rem ---------- 1. 定位 JDK (17+) ----------
set "JDK_HOME="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK_HOME=%JAVA_HOME%"
if not defined JDK_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JDK_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JDK_HOME (
    for /d %%D in ("D:\Android\jdk-*") do (
        if not defined JDK_HOME if exist "%%D\bin\java.exe" set "JDK_HOME=%%D"
    )
)
if not defined JDK_HOME (
    for /d %%D in ("%USERPROFILE%\.jdks\*") do (
        if not defined JDK_HOME if exist "%%D\bin\java.exe" set "JDK_HOME=%%D"
    )
)
if not defined JDK_HOME (
    echo [错误] 未找到 JDK 17+。请安装 Android Studio，或设置 JAVA_HOME 后重试。
    pause
    exit /b 1
)
echo [1/4] 使用 JDK: %JDK_HOME%
set "JAVA_HOME=%JDK_HOME%"

rem ---------- 2. 确认 Android SDK ----------
if not exist local.properties (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk> local.properties
    )
)
if not exist local.properties (
    echo [错误] 未找到 Android SDK。请新建 local.properties，内容: sdk.dir=SDK路径
    pause
    exit /b 1
)
echo [2/4] SDK 配置: local.properties

rem ---------- 3. Gradle 编译 ----------
set "GRADLE_CMD=gradlew.bat"
if exist "D:\Android\gradle-8.10.2\bin\gradle.bat" set "GRADLE_CMD=D:\Android\gradle-8.10.2\bin\gradle.bat"
echo [3/4] 开始编译 release APK（首次会下载依赖，需要几分钟）...
call %GRADLE_CMD% assembleRelease --no-daemon
if errorlevel 1 (
    echo.
    echo [错误] 编译失败，请检查上方日志。
    pause
    exit /b 1
)

rem ---------- 4. 收集产物（版本号改动时同步修改下面的文件名） ----------
if not exist release mkdir release
copy /y "app\build\outputs\apk\release\app-release.apk" "release\GlowHDR-v1.1.1-release.apk" >nul

echo.
echo [4/4] 打包完成！产物：
dir /b "release\*.apk"
echo.
echo 位置: %cd%\release\
pause
