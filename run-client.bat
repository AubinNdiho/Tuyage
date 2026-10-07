@echo off
setlocal
cd /d "%~dp0"
title Tuyage 3.0 FINAL TP - Client

echo ========================================================
echo Tuyage 3.0 FINAL TP - client JavaFX
echo ========================================================
echo.

set "MVN_CMD=mvn"
where mvn >nul 2>&1
if errorlevel 1 (
  set "MVN_CMD="
  for /d %%D in ("C:\Program Files\NetBeans-*") do (
    if exist "%%~fD\netbeans\java\maven\bin\mvn.cmd" set "MVN_CMD=%%~fD\netbeans\java\maven\bin\mvn.cmd"
  )
)

if "%MVN_CMD%"=="" (
  echo [ERREUR] Maven n'est pas disponible sur ce PC.
  echo Installe Maven ou NetBeans avec Maven, puis relance ce fichier.
  echo Java 21 est egalement requis.
  echo.
  pause
  exit /b 1
)

echo Maven utilise : %MVN_CMD%
echo Demarrage de Tuyage...
echo.
call "%MVN_CMD%" --no-transfer-progress javafx:run
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
  echo [ERREUR] Tuyage s'est ferme avec le code %EXIT_CODE%.
  echo Lis les lignes ci-dessus pour voir la cause.
) else (
  echo Tuyage a ete ferme normalement.
)
echo.
pause
exit /b %EXIT_CODE%
