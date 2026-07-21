param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Test-ImageAsset {
    param(
        [string]$RelativePath,
        [int]$Width,
        [int]$Height,
        [long]$MaximumBytes,
        [string]$PixelFormat
    )

    $path = Join-Path $RepositoryRoot $RelativePath
    Assert-Condition (Test-Path -LiteralPath $path -PathType Leaf) "Missing asset: $RelativePath"

    $file = Get-Item -LiteralPath $path
    Assert-Condition ($file.Length -le $MaximumBytes) "$RelativePath exceeds $MaximumBytes bytes"

    $image = [System.Drawing.Bitmap]::FromFile($file.FullName)
    try {
        Assert-Condition ($image.Width -eq $Width) "$RelativePath must be $Width px wide"
        Assert-Condition ($image.Height -eq $Height) "$RelativePath must be $Height px high"
        Assert-Condition ($image.PixelFormat.ToString() -eq $PixelFormat) "$RelativePath must use $PixelFormat"
    }
    finally {
        $image.Dispose()
    }
}

$assets = 'docs/play-store/assets'
$playIcon = Join-Path $assets 'app-icon-play.png'
Test-ImageAsset $playIcon 512 512 1MB 'Format32bppArgb'

$icon = [System.Drawing.Bitmap]::FromFile((Join-Path $RepositoryRoot $playIcon))
try {
    foreach ($corner in @(@(0, 0), @(511, 0), @(0, 511), @(511, 511))) {
        $alpha = $icon.GetPixel($corner[0], $corner[1]).A
        Assert-Condition ($alpha -eq 255) "$playIcon must have opaque corners for Play's dynamic mask"
    }
}
finally {
    $icon.Dispose()
}

Test-ImageAsset (Join-Path $assets 'feature-graphic.png') 1024 500 15MB 'Format24bppRgb'

$screenshots = @(
    '01-home.png',
    '02-history.png',
    '03-voice-onboarding.png',
    '04-privacy-settings.png',
    '05-messaging-rules.png'
)
foreach ($name in $screenshots) {
    $relativePath = Join-Path $assets "phone-screenshots/en-US/$name"
    Test-ImageAsset $relativePath 1080 1920 8MB 'Format24bppRgb'
}

$metadataRoot = Join-Path $RepositoryRoot 'docs/play-store/metadata'
$locales = Get-ChildItem -LiteralPath $metadataRoot -Directory
Assert-Condition ($locales.Count -gt 0) 'No localized store metadata found'

foreach ($locale in $locales) {
    foreach ($spec in @(
        @{ Name = 'title.txt'; Maximum = 30 },
        @{ Name = 'short_description.txt'; Maximum = 80 },
        @{ Name = 'full_description.txt'; Maximum = 4000 }
    )) {
        $path = Join-Path $locale.FullName $spec.Name
        Assert-Condition (Test-Path -LiteralPath $path -PathType Leaf) "Missing $($locale.Name)/$($spec.Name)"
        $text = (Get-Content -LiteralPath $path -Raw -Encoding UTF8).Trim()
        Assert-Condition ($text.Length -gt 0) "$($locale.Name)/$($spec.Name) is empty"
        Assert-Condition ($text.Length -le $spec.Maximum) "$($locale.Name)/$($spec.Name) exceeds $($spec.Maximum) characters"
    }
}

Write-Output "Play Store assets verified: icon, feature graphic, $($screenshots.Count) phone screenshots, and $($locales.Count) locales."
