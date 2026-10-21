param(
    [ValidateSet('Server', 'Webhook', 'All')]
    [string]$Mode = 'All',
    [string]$HostName = '192.168.3.4',
    [int]$Port = 2227,
    [string]$User = 'cztadmin'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$plink = 'C:\Program Files\PuTTY\plink.exe'
$pscp = 'C:\Program Files\PuTTY\pscp.exe'
$hostKey = 'SHA256:9ziB/4g8rhaWFfKQY/rbrcgp741KzudgHW63Js+4L2A'
if (-not (Test-Path -LiteralPath $plink) -or -not (Test-Path -LiteralPath $pscp)) {
    throw 'PuTTY plink/pscp nem található.'
}

function Invoke-Pscp([string]$LocalPath, [string]$RemotePath) {
    & $pscp -batch -agent -hostkey $hostKey -P $Port $LocalPath "$User@$HostName`:$RemotePath"
    if ($LASTEXITCODE -ne 0) { throw "Sikertelen feltöltés: $LocalPath" }
}

function Invoke-RemoteDeploy([string]$Name, [string]$ScriptPath, [string]$Prelude) {
    $remoteScript = "/tmp/blockommander-$Name-deploy.sh"
    Invoke-Pscp $ScriptPath $remoteScript
    $remote = @"
set -euo pipefail
$Prelude
if sudo -n -u mcuser screen -list | grep -q '[.]blockommander-$Name-deploy'; then
  echo 'Már fut egy blockommander-$Name-deploy munkamenet.' >&2
  exit 40
fi
sudo -n -u mcuser screen -dmS blockommander-$Name-deploy bash $remoteScript
for i in `$(seq 1 300); do
  sudo -n -u mcuser screen -list | grep -q '[.]blockommander-$Name-deploy' || break
  sleep 2
done
sudo -n -u mcuser screen -list | grep -q '[.]blockommander-$Name-deploy' && { echo 'A telepítés időtúllépést ért el.' >&2; exit 41; }
sleep 1
cat /tmp/blockommander-$Name-deploy.log
test "`$(cat /tmp/blockommander-$Name-deploy.status)" = 0
"@
    & $plink -batch -agent -hostkey $hostKey -P $Port "$User@$HostName" $remote
    if ($LASTEXITCODE -ne 0) { throw "A(z) $Name telepítés vagy readiness ellenőrzés sikertelen." }
}

if ($Mode -in @('Webhook', 'All')) {
    $bundles = @(Get-ChildItem -LiteralPath (Join-Path $root 'build\distributions') -File -Filter 'blockommander-runtime-*.zip')
    if ($bundles.Count -ne 1) { throw 'Pontosan egy runtime bundle szükséges a build/distributions könyvtárban.' }
    Invoke-Pscp $bundles[0].FullName '/tmp/blockommander-fabric-runtime.zip'
    $prelude = @'
docker inspect blockommander-db >/dev/null
docker exec blockommander-db pg_dump -U admin -d bloCKommander > /tmp/blockommander-fabric-predeploy.sql
test -s /tmp/blockommander-fabric-predeploy.sql
'@
    Invoke-RemoteDeploy 'webhook' (Join-Path $PSScriptRoot 'remote-deploy-webhook.sh') $prelude
}

if ($Mode -in @('Server', 'All')) {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $root 'build\libs') -File -Filter 'blockommander-fabric-*.jar' |
        Where-Object { $_.Name -notlike '*-sources.jar' })
    if ($jars.Count -ne 1) { throw 'Pontosan egy szervermod JAR szükséges a build/libs könyvtárban.' }
    Invoke-Pscp $jars[0].FullName '/tmp/blockommander-fabric.jar'
    Invoke-RemoteDeploy 'mod' (Join-Path $PSScriptRoot 'remote-deploy-mod.sh') ''
}
