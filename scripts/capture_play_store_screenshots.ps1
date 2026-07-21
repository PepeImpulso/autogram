param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$localProperties = Join-Path $repoRoot "local.properties"
$sdkLine = Get-Content -LiteralPath $localProperties |
    Where-Object { $_ -match '^sdk.dir=' } |
    Select-Object -First 1
if (-not $sdkLine) {
    throw "sdk.dir is missing from local.properties"
}

$sdkDir = $sdkLine.Substring(8).Replace('\\', '\')
$adb = Join-Path $sdkDir "platform-tools\adb.exe"
$adbArgs = @()
if ($Serial) {
    $adbArgs += @("-s", $Serial)
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @adbArgs @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

Push-Location $repoRoot
try {
    & .\gradlew.bat :app:assemblePlayDebug --max-workers=1 --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Play debug build failed"
    }

    Invoke-Adb -Arguments @("shell", "wm", "size", "1080x1920")
    Invoke-Adb -Arguments @("shell", "wm", "density", "420")
    Invoke-Adb -Arguments @("shell", "settings", "put", "system", "system_locales", "en-US")
    Invoke-Adb -Arguments @("install", "-r", "app\build\outputs\apk\play\debug\app-play-debug.apk")

    $outputDir = Join-Path $repoRoot "docs\play-store\assets\phone-screenshots\en-US"
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $component = "com.autogram.app/.ui.phone.StoreScreenshotActivity"

    $captures = [ordered]@{
        "home" = "01-home.png"
        "history" = "02-history.png"
        "onboarding_record" = "03-voice-onboarding.png"
        "settings_privacy" = "04-privacy-settings.png"
    }

    foreach ($entry in $captures.GetEnumerator()) {
        Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.autogram.app")
        Invoke-Adb -Arguments @(
            "shell", "am", "start", "-W", "-n", $component,
            "--es", "screen", $entry.Key
        )
        Start-Sleep -Seconds 4
        $remote = "/sdcard/$($entry.Value)"
        Invoke-Adb -Arguments @("shell", "screencap", "-p", $remote)
        Invoke-Adb -Arguments @("pull", $remote, (Join-Path $outputDir $entry.Value))
    }

    $ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
    Get-ChildItem -LiteralPath $outputDir -Filter "*.png" | ForEach-Object {
        $converted = Join-Path $_.DirectoryName "$($_.BaseName).rgb.png"
        & $ffmpeg -hide_banner -loglevel error -y -i $_.FullName -vf "format=rgb24" $converted
        if ($LASTEXITCODE -ne 0) {
            throw "Could not convert $($_.Name) to a 24-bit PNG"
        }
        Move-Item -LiteralPath $converted -Destination $_.FullName -Force
    }

    Add-Type -AssemblyName System.Drawing
    Get-ChildItem -LiteralPath $outputDir -Filter "*.png" | ForEach-Object {
        $image = [System.Drawing.Image]::FromFile($_.FullName)
        try {
            if ($image.Width -ne 1080 -or $image.Height -ne 1920) {
                throw "$($_.Name) is $($image.Width)x$($image.Height), expected 1080x1920"
            }
        } finally {
            $image.Dispose()
        }
    }

    Write-Host "Google Play screenshots are ready in $outputDir"
} finally {
    Pop-Location
}
