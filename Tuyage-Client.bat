@echo off
setlocal
cd /d "%~dp0"
title Tuyage Messenger - Client
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
echo [Tuyage] Compilation et lancement du client...
call mvn -q -DskipTests javafx:run
if errorlevel 1 (
  echo.
  echo [ERREUR] Le client n'a pas pu demarrer. Le message ci-dessus indique la cause.
  pause
)
endlocal
