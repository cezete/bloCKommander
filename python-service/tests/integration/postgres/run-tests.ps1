. "$PSScriptRoot\common.ps1"
Assert-B2TTools
& "$PSScriptRoot\readiness.ps1"
if ($LASTEXITCODE -ne 0) { throw 'B2-T readiness check failed.' }
$state = Get-B2TState

$localBusy = Get-NetTCPConnection -State Listen -LocalPort $script:B2TLocalPort -ErrorAction SilentlyContinue
if ($localBusy) { throw "Local tunnel port $script:B2TLocalPort is already in use." }

$pythonService = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$python = Join-Path $pythonService '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Python venv is missing: $python" }
$testFile = Join-Path $PSScriptRoot 'postgres_graph_integration.py'
$tunnelArgs = @(
    '-batch', '-agent', '-N', '-P', [string]$script:B2TSshPort,
    '-L', "$script:B2TLocalPort`:127.0.0.1:$script:B2TRemotePort",
    $script:B2TRemote
)
$tunnel = Start-Process -FilePath $script:Plink -ArgumentList $tunnelArgs -PassThru -WindowStyle Hidden
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        if ($tunnel.HasExited) { throw "SSH tunnel exited early with code $($tunnel.ExitCode)." }
        $listening = Get-NetTCPConnection -State Listen -LocalPort $script:B2TLocalPort -ErrorAction SilentlyContinue
        if ($listening) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $listening) { throw 'Timed out waiting for the SSH tunnel.' }

    $oldPythonPath = $env:PYTHONPATH
    $env:PYTHONPATH = Join-Path $pythonService 'src'
    $env:BLOCKCOMMANDER_TEST_DB_DSN = "host=127.0.0.1 port=$script:B2TLocalPort dbname=$($state.database) user=$($state.user) password=$($state.password) connect_timeout=5"
    $env:BLOCKCOMMANDER_TEST_IDENTITY = $script:B2TIdentity
    $env:BLOCKCOMMANDER_TEST_COMPOSE_PROJECT = $script:B2TProject
    $env:BLOCKCOMMANDER_TEST_CONTAINER_ID = $state.containerId
    & $python $testFile -v
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL integration suite failed with exit code $LASTEXITCODE." }
    $stageServiceTestFile = Join-Path $PSScriptRoot 'postgres_stage_service_integration.py'
    & $python $stageServiceTestFile -v
    if ($LASTEXITCODE -ne 0) { throw "StageService PostgreSQL integration suite failed with exit code $LASTEXITCODE." }
} finally {
    $env:PYTHONPATH = $oldPythonPath
    Remove-Item Env:BLOCKCOMMANDER_TEST_DB_DSN -ErrorAction SilentlyContinue
    Remove-Item Env:BLOCKCOMMANDER_TEST_IDENTITY -ErrorAction SilentlyContinue
    Remove-Item Env:BLOCKCOMMANDER_TEST_COMPOSE_PROJECT -ErrorAction SilentlyContinue
    Remove-Item Env:BLOCKCOMMANDER_TEST_CONTAINER_ID -ErrorAction SilentlyContinue
    if (-not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force }
    $tunnel.Dispose()
}
