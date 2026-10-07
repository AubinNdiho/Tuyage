@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Tuyage - Creation Setup EXE
call Construire-Tuyage-Portable.bat
if errorlevel 1 exit /b 1
where jpackage >nul 2>nul || (echo [ERREUR] jpackage introuvable.& pause & exit /b 1)
echo.
echo Creation de TuyageSetup.exe...
echo NOTE: jpackage peut demander WiX Toolset sur Windows pour le format EXE.
if exist installer rmdir /s /q installer
mkdir installer
jpackage --type exe --dest installer --name Tuyage --app-version 3.0.20 --vendor Tuyage --description "Tuyage Messenger" --input target\package-input --main-jar tuyage-grpc-messenger-3.0.14.jar --main-class com.tuyage.client.TuyageDesktopLauncher --add-launcher Tuyage-Client=packaging\client.properties --add-launcher Tuyage-Serveur=packaging\server.properties --win-menu --win-shortcut --win-dir-chooser
if errorlevel 1 (
 echo.
 echo [ERREUR] Setup EXE non cree. Si le message parle de WiX, installe WiX Toolset puis relance.
 pause
 exit /b 1
)
echo.
echo SUCCES : regarde dans le dossier installer.
pause
