@echo off
setlocal
cls
echo ========================================================
echo Tuyage 3.0 FINAL TP - test connexion client ^> serveur
echo ========================================================
set /p SERVER_IP=Adresse IPv4 du PC serveur (ex: 192.168.137.224) : 
if "%SERVER_IP%"=="" exit /b 1
echo.
powershell -NoProfile -Command "$r=Test-NetConnection '%SERVER_IP%' -Port 9090; $r | Format-List ComputerName,RemoteAddress,RemotePort,SourceAddress,TcpTestSucceeded"
echo.
echo Si TcpTestSucceeded = True, utilise %SERVER_IP%:9090 dans Tuyage.
pause
endlocal
