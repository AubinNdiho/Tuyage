@echo off
cls
echo ========================================================
echo Tuyage - adresses IPv4 de ce PC
 echo ========================================================
ipconfig | findstr /I /C:"IPv4"
echo.
echo Choisis l'IPv4 de la carte Wi-Fi/Ethernet utilisee par les deux PC.
pause
