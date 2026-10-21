param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath,
    [string]$ModsDirectory = 'C:\Users\tamas\Minecraft\bloCKommander\mods'
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $JarPath).Path
$mods = [System.IO.Path]::GetFullPath($ModsDirectory)
$expected = 'C:\Users\tamas\Minecraft\bloCKommander\mods'
if ($mods.TrimEnd('\') -ne $expected.TrimEnd('\')) {
    throw "Nem várt kliens modkönyvtár: $mods"
}
if (-not (Test-Path -LiteralPath $mods -PathType Container)) {
    throw "A kliens modkönyvtár nem létezik: $mods"
}

$backupRoot = Join-Path (Split-Path -Parent $mods) 'mods-backup'
$backup = Join-Path $backupRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Path $backup -Force | Out-Null

$existing = @(Get-ChildItem -LiteralPath $mods -File -Filter 'blockommander-fabric-*.jar')
foreach ($file in $existing) {
    Copy-Item -LiteralPath $file.FullName -Destination $backup -Force
}

$destination = Join-Path $mods (Split-Path -Leaf $source)
Copy-Item -LiteralPath $source -Destination $destination -Force
foreach ($file in $existing) {
    if ($file.FullName -ne $destination) {
        Remove-Item -LiteralPath $file.FullName -Force
    }
}

$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
$destinationHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash
if ($sourceHash -ne $destinationHash) {
    throw 'A kliensre másolt JAR hash-e nem egyezik a build artifacttal.'
}
Write-Output "Kliensmod telepítve: $destination"
Write-Output "SHA-256: $destinationHash"
Write-Output "Korábbi kliensmod-mentés: $backup"
