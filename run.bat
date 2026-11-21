@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title CS-2 JAVA — Сборка и запуск

cd /d "%~dp0"

echo ================================================================
echo   CS-2 JAVA : Dust II 3D Tactical Arena (Build ^& Launch)
echo ================================================================
echo.

:: 1. Поиск установленной JDK (javac и java)
set "JAVAC_CMD="
set "JAVA_CMD="

where javac >nul 2>nul
if not errorlevel 1 (
    where java >nul 2>nul
    if not errorlevel 1 (
        set "JAVAC_CMD=javac"
        set "JAVA_CMD=java"
    )
)

if not defined JAVAC_CMD (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\javac.exe" (
            if exist "%JAVA_HOME%\bin\java.exe" (
                set "JAVAC_CMD=%JAVA_HOME%\bin\javac.exe"
                set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
            )
        )
    )
)

if not defined JAVAC_CMD (
    for %%D in (
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Java"
        "C:\Program Files\Microsoft"
        "C:\Program Files\BellSoft"
        "C:\Program Files\Amazon Corretto"
    ) do (
        if exist "%%~D" (
            for /d %%J in ("%%~D\*jdk*") do (
                if exist "%%~J\bin\javac.exe" (
                    if exist "%%~J\bin\java.exe" (
                        set "JAVAC_CMD=%%~J\bin\javac.exe"
                        set "JAVA_CMD=%%~J\bin\java.exe"
                    )
                )
            )
        )
    )
)

if not defined JAVAC_CMD goto :NO_JDK
if not defined JAVA_CMD goto :NO_JDK

echo [1/4] Проверка JDK...
"%JAVAC_CMD%" -version
if errorlevel 1 goto :NO_JDK
echo.

:: 2. Подготовка папок lib/ и bin/ и проверка внешних зависимостей (.jar)
echo [2/4] Подготовка директорий и проверка внешних библиотек (lib/)...
if not exist "lib" mkdir "lib"
if not exist "bin" mkdir "bin"

:: Игра построена на собственном программном 3D-движке (Z-Buffer + Swing/AWT + javax.sound)
:: и не требует внешних нативных зависимостей. Если в список EXTRA_JARS добавлены
:: внешние библиотеки (формат ИмяФайла|URL), скрипт автоматически скачает их в lib/.
set "EXTRA_JARS="
for %%P in (%EXTRA_JARS%) do (
    for /f "tokens=1,2 delims=|" %%A in ("%%~P") do (
        if not exist "lib\%%A" (
            echo Скачивание %%A в папку lib\...
            where curl >nul 2>nul
            if not errorlevel 1 (
                curl -fSL "%%B" -o "lib\%%A"
            ) else (
                powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%%B' -OutFile 'lib\%%A'"
            )
            if errorlevel 1 goto :DOWNLOAD_ERROR
        )
    )
)
echo Все необходимые компоненты готовы (внешние .jar не требуются, используется стандартный JDK).
echo.

:: 3. Определение папки исходников (src\ или текущая папка) и компиляция в bin\
set "SRC_DIR=src"
if not exist "src\Main.java" (
    if exist "Main.java" (
        set "SRC_DIR=."
    ) else (
        goto :NO_SOURCES
    )
)

echo [3/4] Компиляция исходных файлов из "%SRC_DIR%\" в папку "bin\"...
del /q "bin\*.class" >nul 2>nul
"%JAVAC_CMD%" -encoding UTF-8 -cp "%SRC_DIR%;lib\*" -d "bin" "%SRC_DIR%\*.java"
if errorlevel 1 goto :COMPILE_ERROR
echo Компиляция успешно завершена!
echo.

:: 4. Запуск скомпилированной игры
echo [4/4] Запуск CS-2 JAVA...
echo Управление: WASD - движение, Space - прыжок, Ctrl - присед, ЛКМ - стрельба,
echo             R - перезарядка, 1/2/3 - оружие, Tab - счет, F5 - рестарт, ESC - пауза.
echo.
"%JAVA_CMD%" -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -cp "bin;lib\*" Main
if errorlevel 1 goto :RUNTIME_ERROR
goto :EOF

:NO_JDK
echo [ОШИБКА] На компьютере не найден установленный Java Development Kit (JDK: javac и java)!
echo.
echo Для компиляции и запуска игры установите бесплатную JDK 17 или JDK 21 (LTS):
echo   1. Рекомендуемая ссылка (Eclipse Adoptium Temurin JDK):
echo      https://adoptium.net/temurin/releases/?version=21
echo   2. Официальный сайт Oracle JDK:
echo      https://www.oracle.com/java/technologies/downloads/
echo.
echo   Быстрая установка через терминал Windows 10/11 (winget):
echo      winget install EclipseAdoptium.Temurin.21.JDK
echo.
echo Важно: при установке отметьте пункт "Add to PATH" (Добавить в переменную PATH),
echo после чего перезапустите этот файл run.bat.
echo.
pause
exit /b 1

:DOWNLOAD_ERROR
echo.
echo [ОШИБКА] Не удалось скачать внешнюю библиотеку в папку lib\. Проверьте подключение к интернету.
pause
exit /b 1

:NO_SOURCES
echo.
echo [ОШИБКА] Не найдены исходные файлы .java (ожидается папка src\Main.java).
pause
exit /b 1

:COMPILE_ERROR
echo.
echo [ОШИБКА] Ошибка компиляции Java-файлов! Проверьте целостность исходного кода в папке %SRC_DIR%.
pause
exit /b 1

:RUNTIME_ERROR
echo.
echo [ОШИБКА] Игра завершилась с кодом ошибки %ERRORLEVEL%.
pause
exit /b 1
