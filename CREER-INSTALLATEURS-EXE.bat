@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Tuyage - Creation des installateurs EXE

echo ========================================================
echo       TUYAGE - CREATION INSTALLATEURS WINDOWS EXE
echo ========================================================
echo.
set "MVN="
if exist "C:\maven\apache-maven-3.9.16\bin\mvn.cmd" set "MVN=C:\maven\apache-maven-3.9.16\bin\mvn.cmd"
if not defined MVN if exist "C:\Program Files\NetBeans-21\netbeans\java\maven\bin\mvn.cmd" set "MVN=C:\Program Files\NetBeans-21\netbeans\java\maven\bin\mvn.cmd"
if not defined MVN (where mvn >nul 2>nul && set "MVN=mvn")
if not defined MVN (echo [ERREUR] Maven introuvable.& pause& exit /b 1)
where jpackage >nul 2>nul || (if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%")
where jpackage >nul 2>nul || (echo [ERREUR] jpackage introuvable. JDK 21 complet requis.& pause& exit /b 1)

echo [1/4] Compilation...
call "%MVN%" -DskipTests clean package
if errorlevel 1 goto :fail

echo [2/4] Dependances...
if exist target\package-input rmdir /s /q target\package-input
mkdir target\package-input
copy /y target\tuyage-grpc-messenger-3.0.14.jar target\package-input\ >nul
call "%MVN%" -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target\package-input
if errorlevel 1 goto :fail

if exist installer rmdir /s /q installer
mkdir installer

echo [3/4] Tuyage-Setup.exe ^(client a partager^) ...
jpackage --type exe --dest installer --name Tuyage --app-version 3.0.24 --vendor Tuyage --description "Tuyage Messenger" --icon src\main\resources\images\tuyage.ico --input target\package-input --main-jar tuyage-grpc-messenger-3.0.14.jar --main-class com.tuyage.client.ClientLauncher --win-menu --win-shortcut --win-dir-chooser
if errorlevel 1 goto :wix
for %%F in (installer\Tuyage-*.exe) do if exist "%%F" ren "%%F" "Tuyage-Setup.exe"

echo [4/4] Tuyage-Serveur-Setup.exe ^(PC hote^) ...
jpackage --type exe --dest installer --name Tuyage-Serveur --app-version 3.0.24 --vendor Tuyage --description "Serveur Tuyage LAN" --icon src\main\resources\images\tuyage.ico --input target\package-input --main-jar tuyage-grpc-messenger-3.0.14.jar --main-class com.tuyage.server.ServerMain --win-menu --win-shortcut --win-dir-chooser
if errorlevel 1 goto :wix
for %%F in (installer\Tuyage-Serveur-*.exe) do if exist "%%F" ren "%%F" "Tuyage-Serveur-Setup.exe"

echo.
echo ========================================================
echo SUCCES
 echo ========================================================
echo A partager aux utilisateurs : installer\Tuyage-Setup.exe
echo Pour le PC serveur          : installer\Tuyage-Serveur-Setup.exe
echo.
echo Les utilisateurs n'ont PAS besoin de Java ni Maven.
echo Sur l'ecran de connexion, ils mettent IP_DU_SERVEUR:9090.
pause
exit /b 0

:wix
echo.
echo [ERREUR] Windows n'a pas pu fabriquer le Setup EXE.
echo Si le message jpackage parle de WiX, installe WiX Toolset puis relance ce fichier.
pause
exit /b 1

:fail
echo.
echo [ERREUR] Compilation ou preparation echouee.
pause
exit /b 1
