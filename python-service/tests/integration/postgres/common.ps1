$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:B2TProject = 'blockcommander-ck03-b2t'
$script:B2TContainer = 'blockcommander-ck03-b2t-postgres'
$script:B2TVolume = 'blockcommander-ck03-b2t_pgdata'
$script:B2TNetwork = 'blockcommander-ck03-b2t_default'
$script:B2TIdentity = '5f4b53b6-2c82-4d68-b788-7860daf2b70f'
$script:B2TRemote = if ($env:B2T_SSH_TARGET) { $env:B2T_SSH_TARGET } else { 'cztadmin@192.168.3.4' }
$script:B2TSshPort = if ($env:B2T_SSH_PORT) { [int]$env:B2T_SSH_PORT } else { 2227 }
$script:B2TRemotePort = if ($env:B2T_REMOTE_PORT) { [int]$env:B2T_REMOTE_PORT } else { 5437 }
$script:B2TLocalPort = if ($env:B2T_LOCAL_TUNNEL_PORT) { [int]$env:B2T_LOCAL_TUNNEL_PORT } else { 55437 }
$script:B2TRemoteDir = '/home/cztadmin/blockcommander-ck03-b2t'
$script:B2TStatePath = Join-Path ([IO.Path]::GetTempPath()) 'blockcommander-ck03-b2t-state.json'
$script:Plink = if ($env:B2T_PLINK) { $env:B2T_PLINK } else { 'C:\Program Files\PuTTY\plink.exe' }
$script:Pscp = if ($env:B2T_PSCP) { $env:B2T_PSCP } else { 'C:\Program Files\PuTTY\pscp.exe' }

function Assert-B2TTools {
    foreach ($path in @($script:Plink, $script:Pscp)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required PuTTY tool is missing: $path"
        }
    }
}
function Invoke-B2TRemote {
    param([Parameter(Mandatory)][string]$Command)
    & $script:Plink -batch -agent -P $script:B2TSshPort $script:B2TRemote $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Remote command failed with exit code $LASTEXITCODE"
    }
}

function Get-B2TState {
    if (-not (Test-Path -LiteralPath $script:B2TStatePath -PathType Leaf)) {
        throw "B2-T state file is missing. Run start.ps1 first: $script:B2TStatePath"
    }
    $state = Get-Content -LiteralPath $script:B2TStatePath -Raw | ConvertFrom-Json
    if ($state.project -ne $script:B2TProject -or
        $state.containerName -ne $script:B2TContainer -or
        $state.identity -ne $script:B2TIdentity -or
        [int]$state.remotePort -ne $script:B2TRemotePort) {
        throw 'B2-T state identity mismatch; refusing to continue.'
    }
    return $state
}

function Assert-B2TOwnership {
    param([Parameter(Mandatory)]$State)
    $containerInspect = (@(Invoke-B2TRemote "docker inspect '$($State.containerId)'") -join [Environment]::NewLine | ConvertFrom-Json)[0]
    $volumeInspect = (@(Invoke-B2TRemote "docker volume inspect '$script:B2TVolume'") -join [Environment]::NewLine | ConvertFrom-Json)[0]
    $networkInspect = (@(Invoke-B2TRemote "docker network inspect '$script:B2TNetwork'") -join [Environment]::NewLine | ConvertFrom-Json)[0]
    $binding = $containerInspect.HostConfig.PortBindings.'5432/tcp'[0]
    $id = $containerInspect.Id
    $project = $containerInspect.Config.Labels.'com.docker.compose.project'
    $environment = $containerInspect.Config.Labels.'com.blockcommander.environment'
    $identity = $containerInspect.Config.Labels.'com.blockcommander.identity'
    $hostBinding = "$($binding.HostIp):$($binding.HostPort)"
    $volumeProject = $volumeInspect.Labels.'com.docker.compose.project'
    $volumeIdentity = $volumeInspect.Labels.'com.blockcommander.identity'
    $networkProject = $networkInspect.Labels.'com.docker.compose.project'

    if ($id -ne $State.containerId -or $project -ne $script:B2TProject -or
        $environment -ne 'ck03-b2t' -or $identity -ne $script:B2TIdentity -or
        $hostBinding -ne "127.0.0.1:$script:B2TRemotePort" -or
        $volumeProject -ne $script:B2TProject -or $volumeIdentity -ne $script:B2TIdentity -or
        $networkProject -ne $script:B2TProject) {
        throw 'Docker metadata identity check failed; refusing to touch or test this target.'
    }
}

