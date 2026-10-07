@echo off
setlocal EnableExtensions
chcp 65001 >nul
title Tuyage 3.0 - Verification PC client

echo =======================================================
echo   TUYAGE 3.0 FINAL TP - VERIFICATION PC CLIENT
echo =======================================================
echo.

echo [1/3] Java 21...
where java >nul 2>nul
if errorlevel 1 (
  echo [ERREUR] Java n'est pas trouve dans le PATH.
  echo Installe/configure un JDK 21 avant de lancer Tuyage.
  goto :network
)
java -version 2>&1 | findstr /I "version"

echo.
echo [2/3] Maven ou Maven NetBeans...
where mvn >nul 2>nul
if not errorlevel 1 (
  for /f "delims=" %%M in ('where mvn') do echo [OK] %%M
) else (
  set "NBMVN="
  for /d %%D in ("C:\Program Files\NetBeans-*") do if exist "%%~fD\netbeans\java\maven\bin\mvn.cmd" set "NBMVN=%%~fD\netbeans\java\maven\bin\mvn.cmd"
  if defined NBMVN (
    echo [OK] Maven NetBeans trouve : %NBMVN%
  ) else (
    echo [ATTENTION] Maven n'est pas installe et aucun Maven NetBeans n'a ete trouve.
    echo run-client.bat aura besoin de Maven. L'installateur .exe sera prepare apres validation finale.
  )
)

:network
echo.
echo [3/3] Connexion au serveur gRPC...
set /p SERVER_IP=IPv4 du PC serveur (ex: 192.168.137.224) : 
if "%SERVER_IP%"=="" goto :end
powershell -NoProfile -Command "Test-NetConnection '%SERVER_IP%' -Port 9090 | Select-Object ComputerName,RemotePort,SourceAddress,TcpTestSucceeded"

echo.
echo Si TcpTestSucceeded = True, utilise dans Tuyage :
echo   %SERVER_IP%:9090

:end
echo.
pause
