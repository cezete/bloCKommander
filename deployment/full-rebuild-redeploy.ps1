[CmdletBinding()]
param(
    [switch]$ConfirmProductionDeploy,
    [switch]$ConfirmCleanStages,
    [switch]$PlanOnly,
    [string]$HostName = '192.168.3.4',
    [int]$Port = 2227,
    [string]$User = 'cztadmin',
    [string]$ClientModsDirectory = 'C:\Users\tamas\Minecraft\bloCKommander\mods',
    [string]$GradleUserHome = 'C:\Users\tamas\.gradle'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deploymentDirectory = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $deploymentDirectory
$javaHome = Join-Path $repositoryRoot '.tools\jdk-25'
$javaExecutable = Join-Path $javaHome 'bin\java.exe'
$gradleWrapper = Join-Path $repositoryRoot 'gradlew.bat'
$clientDeployScript = Join-Path $deploymentDirectory 'deploy-client.ps1'
$remoteDeployScript = Join-Path $deploymentDirectory 'deploy-hp.ps1'
$expectedClientModsDirectory = [System.IO.Path]::GetFullPath('C:\Users\tamas\Minecraft\bloCKommander\mods')
$resolvedClientModsDirectory = [System.IO.Path]::GetFullPath($ClientModsDirectory)

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Invoke-Native([string]$Description, [string]$Executable, [string[]]$Arguments) {
    Write-Step $Description
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description sikertelen. Exit code: $LASTEXITCODE"
    }
}

function Get-SingleArtifact([string]$Directory, [string]$Filter, [switch]$ExcludeSources) {
    $items = @(Get-ChildItem -LiteralPath $Directory -File -Filter $Filter)
    if ($ExcludeSources) {
        $items = @($items | Where-Object { $_.Name -notlike '*-sources.jar' })
    }
    if ($items.Count -ne 1) {
        throw "Pontosan egy artifact szukseges itt: $Directory ($Filter); talalt darabszam: $($items.Count)."
    }
    return $items[0]
}

function Assert-ClientStopped {
    $clientRoot = Split-Path -Parent $resolvedClientModsDirectory
    try {
        $escapedRoot = [regex]::Escape($clientRoot)
        $running = @(Get-CimInstance Win32_Process | Where-Object {
            $_.CommandLine -and $_.CommandLine -match $escapedRoot
        })
    } catch {
        throw "Nem ellenorizheto, hogy a Minecraft kliens fut-e. Zard be a klienst, majd futtasd ujra. Reszlet: $($_.Exception.Message)"
    }
    if ($running.Count -gt 0) {
        $processList = ($running | ForEach-Object { "$($_.Name) (PID $($_.ProcessId))" }) -join ', '
        throw "A bloCKommander Minecraft kliens meg fut: $processList. Zard be a klienst a deploy elott."
    }
}

if ($resolvedClientModsDirectory.TrimEnd('\') -ne $expectedClientModsDirectory.TrimEnd('\')) {
    throw "Nem vart kliens modkonyvtar: $resolvedClientModsDirectory"
}

$requiredPaths = @(
    $javaExecutable,
    $gradleWrapper,
    $clientDeployScript,
    $remoteDeployScript,
    $resolvedClientModsDirectory
)
foreach ($path in $requiredPaths) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Hianyzo szukseges utvonal: $path"
    }
}

Write-Host 'bloCKommander teljes rebuild + redeploy' -ForegroundColor Green
Write-Host "Repository: $repositoryRoot"
Write-Host "Tavoli cel: $User@$HostName`:$Port"
Write-Host "Kliens mods: $resolvedClientModsDirectory"
Write-Host "JAVA_HOME: $javaHome"
Write-Host "GRADLE_USER_HOME: $GradleUserHome"
Write-Host 'Sorrend: cleanTest/buildAll -> kliensmod -> Python webhook -> Fabric szervermod'
Write-Host 'A webhook deploy DB- es source-backupot, a szervermod deploy vilag- es JAR-backupot keszit.'

if ($PlanOnly) {
    Write-Host "`nPLAN ONLY: nem tortent build, masolas, deploy vagy restart." -ForegroundColor Yellow
    exit 0
}

if (-not $ConfirmProductionDeploy) {
    throw 'A produkcios redeployhoz add meg a -ConfirmProductionDeploy kapcsolot.'
}
if (-not $ConfirmCleanStages) {
    throw 'Elobb igazold, hogy nincs megorzendo dirty Stage/workspace, majd add meg a -ConfirmCleanStages kapcsolot.'
}

Assert-ClientStopped

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
$env:GRADLE_USER_HOME = $GradleUserHome

Push-Location $repositoryRoot
try {
    Invoke-Native 'Java 25 ellenorzese' $javaExecutable @('-version')
    Invoke-Native 'Teljes clean build es Gradle/Python tesztkapu' $gradleWrapper @(
        'cleanTest',
        'buildAll',
        '--no-daemon'
    )

    $jar = Get-SingleArtifact (Join-Path $repositoryRoot 'build\libs') 'blockommander-fabric-*.jar' -ExcludeSources
    $runtimeZip = Get-SingleArtifact (Join-Path $repositoryRoot 'build\distributions') 'blockommander-runtime-*.zip'
    $jarHash = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash
    $zipHash = (Get-FileHash -LiteralPath $runtimeZip.FullName -Algorithm SHA256).Hash

    Write-Step 'Build artifactok rogzitese'
    Write-Host "JAR: $($jar.FullName)"
    Write-Host "JAR meret: $($jar.Length) byte"
    Write-Host "JAR SHA-256: $jarHash"
    Write-Host "Runtime ZIP: $($runtimeZip.FullName)"
    Write-Host "Runtime ZIP meret: $($runtimeZip.Length) byte"
    Write-Host "Runtime ZIP SHA-256: $zipHash"

    Write-Step 'Kliensmod mentese es telepitese'
    & $clientDeployScript -JarPath $jar.FullName -ModsDirectory $resolvedClientModsDirectory

    Write-Step 'Python webhook, majd Fabric szervermod kontrollalt tavoli telepitese'
    & $remoteDeployScript -Mode All -HostName $HostName -Port $Port -User $User

    $clientJar = Join-Path $resolvedClientModsDirectory $jar.Name
    if (-not (Test-Path -LiteralPath $clientJar -PathType Leaf)) {
        throw "A telepitett kliens-JAR nem talalhato: $clientJar"
    }
    $clientHash = (Get-FileHash -LiteralPath $clientJar -Algorithm SHA256).Hash
    if ($clientHash -ne $jarHash) {
        throw "A vegso kliens-JAR hash elter. Build: $jarHash; kliens: $clientHash"
    }

    Write-Host "`nREDEPLOY SIKERES" -ForegroundColor Green
    Write-Host "Kliens/server build SHA-256: $jarHash"
    Write-Host 'A Python webhook health es a Fabric port/log readiness ellenorzeset a tavoli deploy szkriptek elvegeztek.'
    Write-Host 'Kovetkezo lepes: Minecraft kliens inditasa, reconnect es az elfogadott acceptance forgatokonyv.'
} catch {
    Write-Host "`nA teljes redeploy megszakadt: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'A tavoli komponensszkriptek a sajat lepesukon belul automatikus rollbacket vegeznek.' -ForegroundColor Yellow
    Write-Host 'Korabban mar sikeresen telepitett komponens nem kerul automatikusan cross-component rollbackre; ellenorizd a kiirt backup utvonalakat.' -ForegroundColor Yellow
    throw
} finally {
    Pop-Location
}
