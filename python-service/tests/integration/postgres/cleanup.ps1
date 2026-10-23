param([switch]$ConfirmCleanup)
. "$PSScriptRoot\common.ps1"
Assert-B2TTools
if (-not $ConfirmCleanup) {
    throw 'Destructive cleanup requires the explicit -ConfirmCleanup switch and separate user authorization.'
}
$state = Get-B2TState
Assert-B2TOwnership -State $state
Invoke-B2TRemote "cd '$script:B2TRemoteDir' && docker compose --env-file .env -p '$script:B2TProject' -f compose.yaml down --volumes"
Invoke-B2TRemote "rm -f '$script:B2TRemoteDir/.env'"
Remove-Item -LiteralPath $script:B2TStatePath -Force
Write-Output 'Removed only the verified B2-T container, network, named volume, remote secret file, and local state file.'
