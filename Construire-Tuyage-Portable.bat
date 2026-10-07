@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Tuyage - Creation application Windows portable

echo ========================================================
echo   TUYAGE - CREATION APPLICATION WINDOWS SANS JAVA/MAVEN
echo ========================================================
where java >nul 2>nul || (echo [ERREUR] Java JDK 21 introuvable.& pause & exit /b 1)
where mvn >nul 2>nul || (echo [ERREUR] Maven introuvable dans PATH.& pause & exit /b 1)
where jpackage >nul 2>nul || (echo [ERREUR] jpackage introuvable. Utilise un JDK 21 complet.& pause & exit /b 1)

echo [1/4] Compilation...
call mvn -DskipTests clean package
if errorlevel 1 goto :fail

echo [2/4] Preparation des dependances...
if exist target\package-input rmdir /s /q target\package-input
mkdir target\package-input
copy /y target\tuyage-grpc-messenger-3.0.14.jar target\package-input\ >nul
call mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target\package-input
if errorlevel 1 goto :fail

echo [3/4] Creation du runtime Java integre et de Tuyage.exe...
if exist dist\Tuyage rmdir /s /q dist\Tuyage
if not exist dist mkdir dist
jpackage --type app-image --dest dist --name Tuyage --app-version 3.0.20 --vendor Tuyage --description "Tuyage Messenger" --input target\package-input --main-jar tuyage-grpc-messenger-3.0.14.jar --main-class com.tuyage.client.TuyageDesktopLauncher --add-launcher Tuyage-Client=packaging\client.properties --add-launcher Tuyage-Serveur=packaging\server.properties
if errorlevel 1 goto :fail

echo [4/4] Termine.
echo.
echo Application creee dans :
echo   %CD%\dist\Tuyage\Tuyage.exe
echo.
echo Tuyage.exe        = serveur local + client ^(PC hote^)
echo Tuyage-Client.exe = client uniquement ^(autres PC^)
echo Tuyage-Serveur.exe= serveur uniquement
pause
exit /b 0
:fail
echo.
echo [ERREUR] La creation a echoue. Lis le message ci-dessus.
pause
exit /b 1
