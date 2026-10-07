$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Set-Location $PSScriptRoot

try {
    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Cyan
    Write-Host "   TUYAGE - CONSTRUCTION PORTABLE" -ForegroundColor Cyan
    Write-Host "=========================================" -ForegroundColor Cyan

    if (-not [Environment]::Is64BitOperatingSystem) {
        throw "Windows 64 bits est necessaire."
    }

    # 1. Rechercher Java et Maven portables
    Write-Host ""
    Write-Host "[1/5] Recherche de Java et Maven portables..."

    $tools = Join-Path $PSScriptRoot "outils_portables"

    if (-not (Test-Path $tools)) {
        throw "Dossier outils_portables introuvable."
    }

    $jdk = Get-ChildItem $tools -Directory -Recurse |
        Where-Object {
            Test-Path (Join-Path $_.FullName "bin\jpackage.exe")
        } |
        Select-Object -First 1

    if (-not $jdk) {
        throw "Java JDK 21 portable introuvable."
    }

    $mvn = Get-ChildItem $tools -Recurse -File -Filter "mvn.cmd" |
        Where-Object {
            $_.DirectoryName -match "apache-maven.*\\bin$"
        } |
        Select-Object -First 1

    if (-not $mvn) {
        throw "Maven portable introuvable."
    }

    $env:JAVA_HOME = $jdk.FullName
    $env:PATH = (Join-Path $jdk.FullName "bin") +
        ";" + $mvn.DirectoryName + ";" + $env:PATH

    Write-Host "Java : $($jdk.FullName)" -ForegroundColor Green
    Write-Host "Maven : $($mvn.FullName)" -ForegroundColor Green

    # Verifier le logo Tuyage
    $icon = Join-Path $PSScriptRoot "src\main\resources\images\tuyage.ico"

    if (-not (Test-Path $icon)) {
        throw "Logo Tuyage introuvable : $icon"
    }

    Write-Host "Logo : $icon" -ForegroundColor Green

    # 2. Compiler Tuyage
    Write-Host ""
    Write-Host "[2/5] Compilation de Tuyage..."

    & $mvn.FullName clean package "-DskipTests"

    if ($LASTEXITCODE -ne 0) {
        throw "Echec de la compilation Maven."
    }

    # 3. Preparer les dependances
    Write-Host ""
    Write-Host "[3/5] Preparation des dependances..."

    $inputDir = Join-Path $PSScriptRoot "target\package-input"

    if (Test-Path $inputDir) {
        Remove-Item $inputDir -Recurse -Force
    }

    New-Item -ItemType Directory -Path $inputDir -Force |
        Out-Null

    $jar = Join-Path $PSScriptRoot `
        "target\tuyage-grpc-messenger-3.0.14.jar"

    if (-not (Test-Path $jar)) {
        throw "Le fichier JAR Tuyage est introuvable."
    }

    Copy-Item $jar $inputDir -Force

    & $mvn.FullName dependency:copy-dependencies `
        "-DincludeScope=runtime" `
        "-DoutputDirectory=$inputDir"

    if ($LASTEXITCODE -ne 0) {
        throw "Echec de la copie des dependances."
    }

    # 4. Creer l'application Windows portable
    Write-Host ""
    Write-Host "[4/5] Creation des applications Windows..."

    $dest = Join-Path $PSScriptRoot "dist"
    $app = Join-Path $dest "Tuyage"

    if (Test-Path $app) {
        Remove-Item $app -Recurse -Force
    }

    New-Item -ItemType Directory -Path $dest -Force |
        Out-Null

    $clientProperties = Join-Path $PSScriptRoot `
        "packaging\client.properties"

    $serverProperties = Join-Path $PSScriptRoot `
        "packaging\server.properties"

    if (-not (Test-Path $clientProperties)) {
        throw "packaging\client.properties introuvable."
    }

    if (-not (Test-Path $serverProperties)) {
        throw "packaging\server.properties introuvable."
    }

    $jpackage = Join-Path $jdk.FullName "bin\jpackage.exe"

    & $jpackage `
        --type app-image `
        --dest $dest `
        --name Tuyage `
        --app-version 3.0.20 `
        --vendor Tuyage `
        --icon $icon `
        --input $inputDir `
        --main-jar "tuyage-grpc-messenger-3.0.14.jar" `
        --main-class "com.tuyage.client.TuyageDesktopLauncher" `
        --add-launcher "Tuyage-Client=$clientProperties" `
        --add-launcher "Tuyage-Serveur=$serverProperties"

    if ($LASTEXITCODE -ne 0) {
        throw "Echec de la creation du package Windows."
    }

    # 5. Ajouter le lancement invisible et les instructions
    Write-Host ""
    Write-Host "[5/5] Preparation du serveur invisible..."

    $vbs = @'
Set sh = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
sh.CurrentDirectory = fs.GetParentFolderName(WScript.ScriptFullName)
sh.Run """" & sh.CurrentDirectory & "\Tuyage-Serveur.exe""", 0, False
'@

    Set-Content `
        -Path (Join-Path $app "Serveur-Invisible.vbs") `
        -Value $vbs `
        -Encoding ASCII

    $guide = @'
TUYAGE PORTABLE

PC 1 : SERVEUR PRINCIPAL + CLIENT

1. Le serveur principal fonctionne sur le port 9090.
2. Serveur-Invisible.vbs permet de lancer le serveur sans fenetre.
3. Tuyage.exe permet d'ouvrir la messagerie.
4. Le client local peut utiliser 127.0.0.1:9090.

PC 2 / PC 3 : CLIENTS

1. Copier le dossier Tuyage complet.
2. Lancer Tuyage-Client.exe ou Tuyage.exe.
3. Ne pas lancer Tuyage-Serveur.exe.
4. Ne pas lancer Serveur-Invisible.vbs.

CONNEXION A DISTANCE

PC serveur Tailscale :
100.64.87.28

Port Tuyage :
9090

Les clients connectes au meme reseau Tailscale
peuvent utiliser :

100.64.87.28:9090

Ne pas exposer directement le port 9090
sur Internet sans securiser le serveur.

IMPORTANT

Le PC 1 serveur doit rester allume.
Tailscale doit etre connecte pour les connexions
depuis des reseaux differents.

Java et Maven ne sont pas necessaires sur
les PC qui utilisent le package final.
'@

    Set-Content `
        -Path (Join-Path $app "LIRE-MOI.txt") `
        -Value $guide `
        -Encoding UTF8

    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host " CONSTRUCTION TERMINEE !" -ForegroundColor Green
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Dossier : $app" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Logo Tuyage integre." -ForegroundColor Green
    Write-Host "Copiez le dossier Tuyage complet."
    Write-Host "Java est integre au package."
    Write-Host "Maven n'est necessaire que pour construire le package."
}
catch {
    Write-Host ""
    Write-Host "ERREUR : $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}