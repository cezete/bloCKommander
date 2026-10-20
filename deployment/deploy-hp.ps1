param(
    [string]$HostName = '192.168.3.4',
    [int]$Port = 2227,
    [string]$User = 'cztadmin'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$bundle = Join-Path $root 'build\distributions\blockommander-runtime-0.1.0.zip'
$remoteScript = Join-Path $PSScriptRoot 'remote-deploy.sh'
$plink = 'C:\Program Files\PuTTY\plink.exe'
$pscp = 'C:\Program Files\PuTTY\pscp.exe'
$hostKey = 'SHA256:9ziB/4g8rhaWFfKQY/rbrcgp741KzudgHW63Js+4L2A'
if (-not (Test-Path -LiteralPath $bundle)) { throw "Hiányzó runtime bundle: $bundle" }
if (-not (Test-Path -LiteralPath $remoteScript)) { throw "Hiányzó távoli telepítő: $remoteScript" }
if (-not (Test-Path -LiteralPath $plink) -or -not (Test-Path -LiteralPath $pscp)) { throw 'PuTTY plink/pscp nem található.' }

$remoteZip = '/tmp/blockommander-fabric-runtime.zip'
$remoteShell = '/tmp/blockommander-fabric-remote-deploy.sh'
& $pscp -batch -agent -hostkey $hostKey -P $Port $bundle "$User@$HostName`:$remoteZip"
if ($LASTEXITCODE -ne 0) { throw 'A feltöltés sikertelen.' }
& $pscp -batch -agent -hostkey $hostKey -P $Port $remoteScript "$User@$HostName`:$remoteShell"
if ($LASTEXITCODE -ne 0) { throw 'A távoli telepítő feltöltése sikertelen.' }

$remote = @'
set -euo pipefail
docker inspect blockommander-db >/dev/null
docker exec blockommander-db pg_dump -U admin -d bloCKommander > /tmp/blockommander-fabric-predeploy.sql
test -s /tmp/blockommander-fabric-predeploy.sql
if sudo -n -u mcuser screen -list | grep -q '[.]blockommander-fabric-deploy'; then
  echo 'Már fut egy blockommander-fabric-deploy munkamenet.' >&2
  exit 40
fi
sudo -n -u mcuser screen -dmS blockommander-fabric-deploy bash /tmp/blockommander-fabric-remote-deploy.sh
for i in $(seq 1 300); do
  sudo -n -u mcuser screen -list | grep -q '[.]blockommander-fabric-deploy' || break
  sleep 2
done
sudo -n -u mcuser screen -list | grep -q '[.]blockommander-fabric-deploy' && { echo 'A telepítés időtúllépést ért el.' >&2; exit 41; }
cat /tmp/blockommander-fabric-deploy.log
test "$(cat /tmp/blockommander-fabric-deploy.status)" = 0
'@
& $plink -batch -agent -hostkey $hostKey -P $Port "$User@$HostName" $remote
if ($LASTEXITCODE -ne 0) { throw 'A távoli telepítés vagy readiness ellenőrzés sikertelen.' }
