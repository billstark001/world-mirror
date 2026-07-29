[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$distributionDirectory = Join-Path $repositoryRoot "build\modrinth"
$versionsDirectory = Join-Path $repositoryRoot "versions"
$propertiesPath = Join-Path $repositoryRoot "gradle.properties"

if (-not $SkipBuild) {
    & (Join-Path $repositoryRoot "gradlew.bat") buildAll
    if ($LASTEXITCODE -ne 0) {
        throw "The complete build failed with exit code $LASTEXITCODE."
    }
}

$versionLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
if ($null -eq $versionLine) {
    throw "Could not find mod_version in $propertiesPath."
}
$modVersion = ($versionLine -split '=', 2)[1].Trim()
$artifactPattern = "^world-mirror-" + [regex]::Escape($modVersion) + "\+.+-fabric\.jar$"

if (Test-Path -LiteralPath $distributionDirectory) {
    Remove-Item -LiteralPath $distributionDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $distributionDirectory -Force | Out-Null

$artifacts = Get-ChildItem -Path $versionsDirectory -Recurse -File -Filter "world-mirror-*.jar" |
    Where-Object { $_.Name -match $artifactPattern } |
    Sort-Object Name

if ($artifacts.Count -ne 3) {
    throw "Expected three distributable $modVersion Fabric JARs after building, but found $($artifacts.Count)."
}

foreach ($artifact in $artifacts) {
    Copy-Item -LiteralPath $artifact.FullName -Destination $distributionDirectory
}

Write-Host "Collected $($artifacts.Count) Modrinth upload files in $distributionDirectory"
$artifacts | ForEach-Object { Write-Host " - $($_.Name)" }
