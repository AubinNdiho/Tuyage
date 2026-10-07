@echo off
setlocal
cd /d "%~dp0"
title Tuyage Messenger - Serveur
where mvn >nul 2>nul
if errorlevel 1 (
  echo.
  echo [ERREUR] Maven n'est pas installe ou n'est pas dans PATH.
  echo Installe JDK 21 + Maven, puis relance ce fichier.
  echo.
  pause
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo [ERREUR] Java n'est pas installe ou n'est pas dans PATH.
  echo Installe JDK 21, puis relance ce fichier.
  echo.
  pause
  exit /b 1
)
echo [Tuyage] Compilation et lancement du serveur...
call mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.tuyage.server.ServerMain
if errorlevel 1 (
  echo.
  echo [ERREUR] Le serveur s'est arrete ou n'a pas pu demarrer.
  pause
)
endlocal
