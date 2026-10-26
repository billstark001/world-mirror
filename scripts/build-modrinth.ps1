[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repositoryRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$buildDirectory = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot "build"))
$distributionDirectory = [System.IO.Path]::GetFullPath((Join-Path $buildDirectory "modrinth"))
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

$targets = @("1.21.11", "26.1.2", "26.2", "26.3")
$artifacts = @(
    foreach ($target in $targets) {
        $libraryDirectory = Join-Path $versionsDirectory "fabric-$target\build\libs"
        $matches = @(Get-ChildItem -LiteralPath $libraryDirectory -File -Filter "world-mirror-*.jar" |
            Where-Object { $_.Name -match $artifactPattern -and $_.Name -eq "world-mirror-$modVersion+$target-fabric.jar" })
        if ($matches.Count -ne 1) {
            throw "Expected one $target distributable in $libraryDirectory, found $($matches.Count)."
        }
        $matches[0]
    }
)

if ($artifacts.Count -ne 4) {
    throw "Expected four distributable $modVersion Fabric JARs after building, but found $($artifacts.Count)."
}

# Check the absolute target and reject redirected directories before recursive cleanup.
if ((Split-Path -Parent $distributionDirectory) -ne $buildDirectory -or
    (Split-Path -Leaf $distributionDirectory) -ne "modrinth") {
    throw "Refusing cleanup outside the repository's build/modrinth directory."
}
foreach ($directory in @($buildDirectory, $distributionDirectory)) {
    if (Test-Path -LiteralPath $directory) {
        $item = Get-Item -LiteralPath $directory -Force
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            throw "Refusing cleanup through a redirected directory: $directory"
        }
    }
}
if (Test-Path -LiteralPath $distributionDirectory) {
    Remove-Item -LiteralPath $distributionDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $distributionDirectory -Force | Out-Null

foreach ($artifact in $artifacts) {
    Copy-Item -LiteralPath $artifact.FullName -Destination $distributionDirectory
}

Write-Host "Collected $($artifacts.Count) Modrinth upload files in $distributionDirectory"
$artifacts | ForEach-Object { Write-Host " - $($_.Name)" }
