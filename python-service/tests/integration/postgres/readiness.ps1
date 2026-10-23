. "$PSScriptRoot\common.ps1"
Assert-B2TTools
$state = Get-B2TState
Assert-B2TOwnership -State $state

$deadline = [DateTime]::UtcNow.AddSeconds(90)
do {
    $health = (Invoke-B2TRemote "docker inspect '$($state.containerId)' --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}'").Trim()
    if ($health -eq 'running|healthy') { break }
    if ($health -match 'exited|dead|unhealthy') { throw "B2-T PostgreSQL is not healthy: $health" }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $deadline)
if ($health -ne 'running|healthy') { throw "Timed out waiting for B2-T PostgreSQL: $health" }

$marker = (Invoke-B2TRemote "docker exec '$($state.containerId)' psql -XAt -U blockcommander_b2t -d blockcommander_b2t -c 'SELECT identity::text || chr(124) || compose_project || chr(124) || purpose FROM public.blockcommander_test_identity'").Trim()
$expected = "$script:B2TIdentity|$script:B2TProject|disposable-postgresql-integration"
if ($marker -ne $expected) { throw 'Database identity marker mismatch; refusing to run tests.' }

$role = (Invoke-B2TRemote "docker exec '$($state.containerId)' psql -XAt -U blockcommander_b2t -d blockcommander_b2t -c 'SELECT rolsuper::text || chr(124) || rolcreatedb::text || chr(124) || rolcreaterole::text || chr(124) || rolreplication::text FROM pg_roles WHERE rolname=current_user'").Trim()
if ($role -ne 'false|false|false|false') { throw "Test role is over-privileged: $role" }
Write-Output "READY: $($state.containerId) on remote loopback port $($state.remotePort)"
