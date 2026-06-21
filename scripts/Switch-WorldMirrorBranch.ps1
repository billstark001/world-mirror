<#
.SYNOPSIS
Switches World Mirror development branches and refreshes branch-local run assets.

.DESCRIPTION
This helper checks out a branch, determines its Minecraft version from
`gradle.properties`, cleans branch-sensitive Fabric run caches, and downloads
the matching Xaero's World Map jar through `Get-LatestXaerosWorldMap.ps1`.

The default clean intentionally does not remove `build` because that can delete
already compiled jars under `build/libs`. Pass `-CleanBuild` when you really
want to remove the Gradle build directory. Pass `-CleanXaeroInspectCache` when
you want to remove `build/tmp/xaero-inspect`, the disassembly cache generated
by `Get-LatestXaerosWorldMap.ps1 -Disassemble`.

.PARAMETER CleanBuild
Remove the full Gradle `build` directory. Defaults to false.

.PARAMETER CleanXaeroInspectCache
Remove `build/tmp/xaero-inspect`. Defaults to false.
#>
param(
    [Parameter(Position = 0)]
    [string]$Branch,
    [string]$MinecraftVersion,
    [string]$ModsDirectory = "run\mods",
    [string]$Loader = "fabric",
    [switch]$NoCheckout,
    [switch]$SkipClean,
    [switch]$CleanBuild,
    [switch]$CleanXaeroInspectCache,
    [switch]$SkipXaero,
    [switch]$AllowAnyXaeroGameVersion
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

function Assert-InRepo {
    param([string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/')
    if (-not ($fullPath -eq $root -or $fullPath.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar))) {
        throw "Refusing to modify path outside repository: $fullPath"
    }
}

function Get-GradleProperty {
    param([string]$Name)
    $propertiesPath = Join-Path $repoRoot "gradle.properties"
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        return $null
    }
    $match = Select-String -LiteralPath $propertiesPath -Pattern "^$([Regex]::Escape($Name))=(.+)$" | Select-Object -First 1
    if ($match) {
        return $match.Matches[0].Groups[1].Value.Trim()
    }
    return $null
}

function Remove-GeneratedPath {
    param([string]$RelativePath)
    $path = Resolve-RepoPath $RelativePath
    Assert-InRepo $path
    if (Test-Path -LiteralPath $path) {
        Write-Host "Removing $RelativePath"
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}

Push-Location $repoRoot
try {
    if ($Branch -and -not $NoCheckout) {
        Write-Host "Switching to branch '$Branch'..."
        git switch $Branch
    } elseif (-not $Branch) {
        $Branch = (git branch --show-current).Trim()
        Write-Host "Using current branch '$Branch'."
    }

    if (-not $MinecraftVersion) {
        $MinecraftVersion = Get-GradleProperty "minecraft_version"
    }
    if (-not $MinecraftVersion) {
        throw "Unable to determine minecraft_version from gradle.properties. Pass -MinecraftVersion explicitly."
    }

    if (-not $SkipClean) {
        if ($CleanBuild) {
            Remove-GeneratedPath "build"
        } elseif ($CleanXaeroInspectCache) {
            Remove-GeneratedPath "build\tmp\xaero-inspect"
        }
        Remove-GeneratedPath "run\.fabric\processedMods"
        Remove-GeneratedPath "run\.fabric\remappedJars"
        Remove-GeneratedPath "run\.fabric\remappedMods"
    }

    $modsPath = Resolve-RepoPath $ModsDirectory
    Assert-InRepo $modsPath
    New-Item -ItemType Directory -Force -Path $modsPath | Out-Null

    if (-not $SkipXaero) {
        $xaeroScript = Join-Path $PSScriptRoot "Get-LatestXaerosWorldMap.ps1"
        $xaeroArgs = @{
            MinecraftVersion = $MinecraftVersion
            Loader = $Loader
            OutputDirectory = $modsPath
        }
        if ($AllowAnyXaeroGameVersion) {
            $xaeroArgs.AllowAnyGameVersion = $true
        }
        & $xaeroScript @xaeroArgs
    }

    Write-Host "Branch assets ready."
    Write-Host "Branch: $Branch"
    Write-Host "Minecraft: $MinecraftVersion"
    Write-Host "Mods: $modsPath"
} finally {
    Pop-Location
}
