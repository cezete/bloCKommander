. "$PSScriptRoot\common.ps1"
Assert-B2TTools
$state = Get-B2TState
Assert-B2TOwnership -State $state
Invoke-B2TRemote "cd '$script:B2TRemoteDir' && docker compose --env-file .env -p '$script:B2TProject' -f compose.yaml stop"
Write-Output 'B2-T container stopped; its container and named volume remain available for inspection.'
