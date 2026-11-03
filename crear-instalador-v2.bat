@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==========================================
echo   Cafe Cafe - crear instalador para Windows ^(v2^)
echo ==========================================
echo.

where jpackage >nul 2>nul
if errorlevel 1 (
  echo [ERROR] No se encontro jpackage. Instala el JDK 21 y abre una terminal nueva.
  goto fin
)

where mvn >nul 2>nul
if errorlevel 1 (
  if not exist "tools\maven\bin\mvn.cmd" (
    echo [AVISO] No se encontro Maven. Descargando Maven 3.9.9 ^(solo la primera vez, ~9 MB^)...
    if not exist tools mkdir tools
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile 'tools\maven.zip'; Expand-Archive -Force 'tools\maven.zip' 'tools'; Rename-Item 'tools\apache-maven-3.9.9' 'maven'; Remove-Item 'tools\maven.zip'"
  )
  if not exist "tools\maven\bin\mvn.cmd" (
    echo [ERROR] No se pudo descargar Maven. Revisa tu conexion a internet y vuelve a intentar.
    goto fin
  )
  set "PATH=%~dp0tools\maven\bin;%PATH%"
)
echo [1/3] Compilando el sistema ^(la primera vez descarga librerias y tarda unos minutos^)...
call mvn -q -B clean package -DskipTests
if errorlevel 1 (
  echo [ERROR] Fallo la compilacion. Revisa los mensajes de arriba.
  goto fin
)

if not exist "target\Cafe_Cafe-1.0.0.jar" (
  echo [ERROR] No existe target\Cafe_Cafe-1.0.0.jar
  goto fin
)
copy /y "target\Cafe_Cafe-1.0.0.jar" "target\libs\" >nul

set TIPO=exe
where candle.exe >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("%ProgramFiles(x86)%\WiX Toolset v3*" "%ProgramFiles%\WiX Toolset v3*") do (
    if exist "%%~D\bin\candle.exe" set "PATH=%%~D\bin;%PATH%"
  )
)
where candle.exe >nul 2>nul
if errorlevel 1 (
  set TIPO=app-image
  echo [AVISO] No se encontro WiX Toolset 3: se creara una carpeta portable en vez de un instalador .exe.
)

echo [2/3] Creando %TIPO%...
if exist instalador rmdir /s /q instalador

set MODULOS=java.base,java.desktop,java.logging,java.naming,java.net.http,java.prefs,java.sql,java.xml,jdk.unsupported,jdk.jsobject,jdk.xml.dom

if "%TIPO%"=="exe" (
  jpackage --type exe --name "Cafe Cafe" --app-version 1.0.0 --vendor "C3 Dev Studio" ^
    --description "Sistema de punto de venta Cafe Cafe" --input target\libs --main-jar Cafe_Cafe-1.0.0.jar ^
    --main-class application.AppLauncher --icon packaging\cafecafe.ico --dest instalador ^
    --add-modules %MODULOS% --java-options "-Dfile.encoding=UTF-8" ^
    --win-menu --win-shortcut --win-dir-chooser --win-upgrade-uuid 7c1f4b52-3a9e-4d6b-9a14-5e2b8c0d3f71
) else (
  jpackage --type app-image --name "Cafe Cafe" --app-version 1.0.0 --vendor "C3 Dev Studio" ^
    --input target\libs --main-jar Cafe_Cafe-1.0.0.jar --main-class application.AppLauncher ^
    --icon packaging\cafecafe.ico --dest instalador ^
    --add-modules %MODULOS% --java-options "-Dfile.encoding=UTF-8"
)
if errorlevel 1 (
  echo [ERROR] jpackage fallo. Copia el mensaje de arriba y mandamelo.
  goto fin
)

echo.
echo [3/3] Listo. Mira la carpeta "instalador".
if "%TIPO%"=="exe" (echo Archivo para entregar: instalador\Cafe Cafe-1.0.0.exe) else (echo Carpeta portable: instalador\Cafe Cafe  ^(ejecutable: Cafe Cafe.exe^))
:fin
echo.
pause
