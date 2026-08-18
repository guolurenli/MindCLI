@echo off
setlocal

rem MindCLI portable Windows launcher template.
rem Copy this file to run-mindcli.cmd, then adjust optional values below.

rem Always run from the project root, even when launched by double-click.
cd /d "%~dp0"

rem Use UTF-8 so Chinese text, Unicode symbols, ANSI output and chafa pixels render correctly.
chcp 65001 > nul

rem Renderer mode: inline gives the JLine prompt, status dock and startup mascot.
if not defined MINDCLI_RENDERER set "MINDCLI_RENDERER=inline"

rem Show the random neko assistant startup image when chafa is available.
if not defined MINDCLI_UI_MASCOT set "MINDCLI_UI_MASCOT=true"

rem Terminal encoding and ANSI capability hints for Windows terminals.
if not defined MINDCLI_TERMINAL_ENCODING set "MINDCLI_TERMINAL_ENCODING=UTF-8"
if not defined MINDCLI_TERMINAL_TYPE set "MINDCLI_TERMINAL_TYPE=xterm-256color"
if not defined TERM set "TERM=xterm-256color"

rem Truecolor enables the warm neko assistant palette.
if not defined COLORTERM set "COLORTERM=truecolor"
if not defined MINDCLI_TRUECOLOR set "MINDCLI_TRUECOLOR=true"

rem Optional: uncomment and change this if java is not on PATH and JAVA_HOME is not set.
rem set "MINDCLI_JAVA=C:\path\to\jdk-17-or-newer\bin\java.exe"

rem Optional: uncomment and change this if chafa is not on PATH.
rem set "MINDCLI_CHAFA_BIN=C:\path\to\chafa.exe"

rem Prefer explicit MINDCLI_JAVA, then JAVA_HOME, then java on PATH.
if not defined MINDCLI_JAVA if defined JAVA_HOME set "MINDCLI_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined MINDCLI_JAVA set "MINDCLI_JAVA=java"

if /i "%MINDCLI_JAVA%"=="java" (
    where java > nul 2>&1
    if errorlevel 1 (
        echo Java 17 or newer was not found.
        echo Install Java 17+ or set MINDCLI_JAVA to the java.exe path.
        exit /b 1
    )
) else if not exist "%MINDCLI_JAVA%" (
    echo Java executable not found: %MINDCLI_JAVA%
    echo Set MINDCLI_JAVA or JAVA_HOME, then try again.
    exit /b 1
)

if not exist "target\mindcli-1.0-SNAPSHOT.jar" (
    echo Jar not found: target\mindcli-1.0-SNAPSHOT.jar
    echo Run mvn clean package first.
    exit /b 1
)

rem If chafa is unavailable, keep startup reliable by showing the text banner only.
if not defined MINDCLI_CHAFA_BIN (
    where chafa > nul 2>&1
    if errorlevel 1 set "MINDCLI_UI_MASCOT=false"
)

"%MINDCLI_JAVA%" ^
    -Dfile.encoding=UTF-8 ^
    -Dsun.stdout.encoding=UTF-8 ^
    -Dsun.stderr.encoding=UTF-8 ^
    -Dmindcli.terminal.type="%MINDCLI_TERMINAL_TYPE%" ^
    -Dmindcli.render.truecolor="%MINDCLI_TRUECOLOR%" ^
    -jar "target\mindcli-1.0-SNAPSHOT.jar"

set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
