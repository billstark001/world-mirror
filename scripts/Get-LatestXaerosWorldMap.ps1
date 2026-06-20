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

if ($ReplaceExisting) {
    Get-ChildItem -LiteralPath $outputPath -Filter "*.jar" |
        Where-Object { $_.Name -match "xaero.*world.*map|world.*map.*xaero" } |
        Remove-Item -Force
}

$destination = Join-Path $outputPath $file.filename
Write-Host "Downloading $($version.name) ($($version.version_number))..."
Write-Host "Target: $destination"
Invoke-WebRequest -Uri $file.url -Headers $headers -OutFile $destination

Write-Host "Done."
Write-Host "Version: $($version.version_number)"
Write-Host "Game versions: $(@($version.game_versions) -join ', ')"
Write-Host "File: $destination"
