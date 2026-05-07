@echo off
REM Build script per ZK IntelliJ Plugin su Windows

echo ================================================
echo    ZK Binding Support Plugin - Build Script
echo ================================================
echo.

REM Controlla se JAVA_HOME è impostato
if "%JAVA_HOME%"=="" (
    echo [ERRORE] JAVA_HOME non è impostato!
    echo.
    echo Soluzioni:
    echo 1. Scarica Java 21 da https://adoptium.net/
    echo 2. Imposta JAVA_HOME manualmente:
    echo    set JAVA_HOME=C:\Path\To\Java\jdk-21
    echo.
    pause
    exit /b 1
)

echo [INFO] Usando JAVA_HOME: %JAVA_HOME%
echo.

REM Verifica versione Java
"%JAVA_HOME%\bin\java" -version 2>&1 | findstr /C:"21." >nul
if errorlevel 1 (
    echo [AVVISO] Java 21 richiesto! Versione attuale:
    "%JAVA_HOME%\bin\java" -version
    echo.
)

echo [INFO] Building plugin...
echo.

REM Esegui build
gradlew.bat clean buildPlugin

if errorlevel 1 (
    echo.
    echo [ERRORE] Build fallito!
    pause
    exit /b 1
)

echo.
echo [SUCCESSO] Build completato!
echo.
echo Plugin generato in:
echo   build\distributions\zk-intellij-plugin-1.0.2.zip
echo.
pause
