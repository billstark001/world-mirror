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

$task = if ($SkipBuild) { "writeTargetManifest" } else { "collectArtifacts" }
& (Join-Path $repositoryRoot "gradlew.bat") $task
if ($LASTEXITCODE -ne 0) { throw "Gradle $task failed with exit code $LASTEXITCODE." }

$manifest = Get-Content -LiteralPath (Join-Path $buildDirectory "target-manifest.json") -Raw | ConvertFrom-Json
$versionLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
if ($null -eq $versionLine) { throw "Could not find mod_version in $propertiesPath." }
$modVersion = ($versionLine -split '=', 2)[1].Trim()
if ($manifest.version -ne $modVersion) { throw "Target manifest version does not match gradle.properties." }
$targets = @($manifest.targets)
if ($targets.Count -eq 0) { throw "The target manifest is empty." }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$artifacts = @(
    foreach ($target in $targets) {
        $path = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $target.artifact))
        $expectedDirectory = [System.IO.Path]::GetFullPath((Join-Path $versionsDirectory "fabric-$($target.minecraft)/build/libs"))
        if ((Split-Path -Parent $path) -ne $expectedDirectory -or
            (Split-Path -Leaf $path) -ne "world-mirror-$modVersion+$($target.minecraft)-fabric.jar") {
            throw "Unexpected artifact path in target manifest: $path"
        }
        $artifact = Get-Item -LiteralPath $path
        $archive = [System.IO.Compression.ZipFile]::OpenRead($artifact.FullName)
        try {
            $entry = $archive.GetEntry("fabric.mod.json")
            if ($null -eq $entry) { throw "Missing Fabric metadata in $path" }
            $reader = [System.IO.StreamReader]::new($entry.Open())
            try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            if ($metadata.id -ne $manifest.modId -or
                $metadata.version -ne "$modVersion+$($target.minecraft)-fabric") {
                throw "Artifact metadata does not match the target manifest: $path"
            }
        } finally { $archive.Dispose() }
        $artifact
    }
)

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
