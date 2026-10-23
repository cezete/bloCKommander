. "$PSScriptRoot\common.ps1"
Assert-B2TTools

if (Test-Path -LiteralPath $script:B2TStatePath -PathType Leaf) {
    $existing = Get-B2TState
    Assert-B2TOwnership -State $existing
    $status = (Invoke-B2TRemote "docker inspect '$($existing.containerId)' --format '{{.State.Status}}'").Trim()
    if ($status -ne 'running') {
        Invoke-B2TRemote "cd '$script:B2TRemoteDir' && docker compose --env-file .env -p '$script:B2TProject' -f compose.yaml start"
        Write-Output "Restarted verified B2-T environment: $($existing.containerId)"
    } else {
        Write-Output "B2-T environment already running: $($existing.containerId)"
    }
    exit 0
}

$occupied = (@(Invoke-B2TRemote "ss -lntH 'sport = :$script:B2TRemotePort'") -join [Environment]::NewLine).Trim()
if ($occupied) {
    throw "Remote port $script:B2TRemotePort is already in use; refusing to start."
}
$foreign = (@(Invoke-B2TRemote "docker ps -aq --filter 'name=^/$script:B2TContainer`$'; docker volume ls -q --filter 'name=^$script:B2TVolume`$'; docker network ls -q --filter 'name=^$script:B2TNetwork`$'") -join [Environment]::NewLine).Trim()
if ($foreign) {
    throw 'A B2-T resource name already exists without a verified local state file; refusing to reuse it.'
}

$passwordBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes).Replace('+','A').Replace('/','B').TrimEnd('=')
$envPath = Join-Path ([IO.Path]::GetTempPath()) "blockcommander-ck03-b2t-$([Guid]::NewGuid().ToString('N')).env"
try {
    @(
        "B2T_POSTGRES_PASSWORD=$password"
        "B2T_REMOTE_PORT=$script:B2TRemotePort"
    ) | Set-Content -LiteralPath $envPath -Encoding utf8NoBOM

    Invoke-B2TRemote "install -d -m 700 '$script:B2TRemoteDir'"
    & $script:Pscp -batch -agent -P $script:B2TSshPort `
        "$PSScriptRoot\compose.yaml" "$PSScriptRoot\init-test-identity.sql" `
        "${script:B2TRemote}:$script:B2TRemoteDir/"
    if ($LASTEXITCODE -ne 0) { throw 'Copying B2-T Compose files failed.' }
    & $script:Pscp -batch -agent -P $script:B2TSshPort $envPath `
        "${script:B2TRemote}:$script:B2TRemoteDir/.env"
    if ($LASTEXITCODE -ne 0) { throw 'Copying the generated B2-T environment file failed.' }
    Invoke-B2TRemote "chmod 600 '$script:B2TRemoteDir/.env'; cd '$script:B2TRemoteDir' && docker compose --env-file .env -p '$script:B2TProject' -f compose.yaml up -d"
    $containerId = (Invoke-B2TRemote "docker inspect '$script:B2TContainer' --format '{{.Id}}'").Trim()
    [pscustomobject]@{
        project = $script:B2TProject
        containerName = $script:B2TContainer
        containerId = $containerId
        identity = $script:B2TIdentity
        remotePort = $script:B2TRemotePort
        localPort = $script:B2TLocalPort
        database = 'blockcommander_b2t'
        user = 'blockcommander_b2t'
        password = 'ck03-b2t-disposable-only-5f4b53b6'
        remoteDirectory = $script:B2TRemoteDir
    } | ConvertTo-Json | Set-Content -LiteralPath $script:B2TStatePath -Encoding utf8NoBOM
    Write-Output "Started isolated B2-T PostgreSQL container: $containerId"
} finally {
    if (Test-Path -LiteralPath $envPath) {
        Remove-Item -LiteralPath $envPath -Force
    }
}
