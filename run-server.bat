@echo off
setlocal
cd /d "%~dp0"
echo ========================================================
echo Tuyage 3.0 FINAL TP - serveur gRPC
echo ========================================================

netstat -ano | findstr /R /C:":9090 .*LISTENING" >nul
if not errorlevel 1 (
  echo.
  echo [ERREUR] Le port 9090 est deja utilise.
  echo Ferme l'ancien serveur Tuyage avant d'en lancer un autre.
  echo Commande utile : netstat -ano ^| findstr :9090
  echo.
  pause
  exit /b 1
)

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
  pause
  exit /b 1
)

echo Maven utilise : %MVN_CMD%
"%MVN_CMD%" compile
if errorlevel 1 (
  pause
  exit /b 1
)
"%MVN_CMD%" exec:java -Dexec.mainClass=com.tuyage.server.ServerMain
pause
endlocal
