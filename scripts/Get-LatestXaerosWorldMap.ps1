param(
    [string]$MinecraftVersion,
    [string]$Loader = "fabric",
    [string]$ProjectSlug = "xaeros-world-map",
    [string]$OutputDirectory = "run\mods",
    [switch]$AllowAnyGameVersion,
    [switch]$ReplaceExisting
)

$ErrorActionPreference = "Stop"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {
    Write-Verbose "Unable to force TLS 1.2; continuing with the current PowerShell defaults."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $MinecraftVersion) {
    $propertiesPath = Join-Path $repoRoot "gradle.properties"
    if (Test-Path -LiteralPath $propertiesPath) {
        $match = Select-String -LiteralPath $propertiesPath -Pattern "^minecraft_version=(.+)$" | Select-Object -First 1
        if ($match) {
            $MinecraftVersion = $match.Matches[0].Groups[1].Value.Trim()
        }
    }
}

$outputPath = $OutputDirectory
if (-not [System.IO.Path]::IsPathRooted($outputPath)) {
    $outputPath = Join-Path $repoRoot $outputPath
}
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$headers = @{
    "User-Agent" = "WorldMirrorDevScript/1.0 (PowerShell; +https://github.com/)"
}

function ConvertTo-UrlJsonArray {
    param([string]$Value)
    return [Uri]::EscapeDataString(('["{0}"]' -f $Value.Replace('\', '\\').Replace('"', '\"')))
}

function Get-ModrinthVersions {
    param(
        [string]$Slug,
        [string]$LoaderName,
        [string]$GameVersion
    )

    $baseUrl = "https://api.modrinth.com/v2/project/$Slug/version"
    $query = "loaders=$(ConvertTo-UrlJsonArray $LoaderName)"
    if ($GameVersion) {
        $query = "$query&game_versions=$(ConvertTo-UrlJsonArray $GameVersion)"
    }

    return @(Invoke-RestMethod -Uri "$baseUrl`?$query" -Headers $headers)
}

function Test-XaeroWorldMapFileName {
    param([string]$Name)
    return $Name -match "(?i)(xaero.*world.*map|world.*map.*xaero).*\.jar(\.disabled)?$"
}

function Get-ActiveJarName {
    param([string]$Name)
    if ($Name.EndsWith(".jar.disabled", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $Name.Substring(0, $Name.Length - ".disabled".Length)
    }
    return $Name
}

function Disable-XaeroWorldMapJar {
    param([System.IO.FileInfo]$File)

    if ($File.Name.EndsWith(".jar.disabled", [System.StringComparison]::OrdinalIgnoreCase)) {
        return
    }

    $disabledPath = "$($File.FullName).disabled"
    if (Test-Path -LiteralPath $disabledPath) {
        Remove-Item -LiteralPath $File.FullName -Force
        return
    }

    Rename-Item -LiteralPath $File.FullName -NewName ($File.Name + ".disabled")
}

$versions = @()
if (-not $AllowAnyGameVersion -and $MinecraftVersion) {
    Write-Host "Querying latest $Loader Xaero's World Map for Minecraft $MinecraftVersion..."
    $versions = Get-ModrinthVersions -Slug $ProjectSlug -LoaderName $Loader -GameVersion $MinecraftVersion
}

if ($versions.Count -eq 0) {
    if (-not $AllowAnyGameVersion -and $MinecraftVersion) {
        Write-Host "No compatible version found for Minecraft $MinecraftVersion; querying latest $Loader version instead..."
    } else {
        Write-Host "Querying latest $Loader Xaero's World Map..."
    }
    $versions = Get-ModrinthVersions -Slug $ProjectSlug -LoaderName $Loader -GameVersion $null
}

if ($versions.Count -eq 0) {
    throw "No Modrinth versions found for project '$ProjectSlug' and loader '$Loader'."
}

$version = $versions |
    Sort-Object { [DateTime]$_.date_published } -Descending |
    Select-Object -First 1

$file = @($version.files | Where-Object { $_.primary } | Select-Object -First 1)
if ($file.Count -eq 0) {
    $file = @($version.files | Select-Object -First 1)
}
if ($file.Count -eq 0 -or -not $file[0].url) {
    throw "Selected Modrinth version '$($version.version_number)' has no downloadable file."
}
$file = $file[0]

$destination = Join-Path $outputPath $file.filename
$existingXaeroFiles = Get-ChildItem -LiteralPath $outputPath -File |
    Where-Object { Test-XaeroWorldMapFileName $_.Name }

foreach ($existing in $existingXaeroFiles) {
    $activeName = Get-ActiveJarName $existing.Name
    if ($activeName -ne $file.filename) {
        Disable-XaeroWorldMapJar $existing
    }
}

$disabledLatest = "$destination.disabled"
if (Test-Path -LiteralPath $disabledLatest) {
    Remove-Item -LiteralPath $disabledLatest -Force
}

Write-Host "Downloading $($version.name) ($($version.version_number))..."
Write-Host "Target: $destination"
$downloadPath = "$destination.download"
if (Test-Path -LiteralPath $downloadPath) {
    Remove-Item -LiteralPath $downloadPath -Force
}
Invoke-WebRequest -Uri $file.url -Headers $headers -OutFile $downloadPath
Move-Item -LiteralPath $downloadPath -Destination $destination -Force

$disabledCount = @(Get-ChildItem -LiteralPath $outputPath -File |
    Where-Object {
        (Test-XaeroWorldMapFileName $_.Name) -and
        $_.Name.EndsWith(".jar.disabled", [System.StringComparison]::OrdinalIgnoreCase)
    }).Count

Write-Host "Done."
Write-Host "Version: $($version.version_number)"
Write-Host "Game versions: $(@($version.game_versions) -join ', ')"
Write-Host "File: $destination"
Write-Host "Disabled old/other Xaero jars: $disabledCount"
