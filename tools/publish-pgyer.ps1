param(
    [string]$ApiKey = $env:PGYER_API_KEY,
    [string]$ApkPath,
    [string]$UpdateDescription,
    [ValidateSet("1", "2", "3")]
    [string]$InstallType = "1",
    [string]$Password = "",
    [switch]$SkipBuild,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    $scriptDir = Split-Path -Parent $MyInvocation.ScriptName
    return (Resolve-Path (Join-Path $scriptDir "..")).Path
}

function Get-AppVersionText {
    param([string]$BuildFile)

    $content = Get-Content -LiteralPath $BuildFile -Raw
    $versionName = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [regex]::Match($content, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($versionName) -or [string]::IsNullOrWhiteSpace($versionCode)) {
        throw "Cannot read versionName/versionCode from $BuildFile"
    }
    return "versionName=$versionName, versionCode=$versionCode"
}

function Invoke-GradleReleaseBuild {
    param([string]$RepoRoot)

    Write-Host "Building release APK..."
    Push-Location $RepoRoot
    try {
        & ".\gradlew.bat" ":app:assembleRelease"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle assembleRelease failed, exitCode=$LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-PgyerUpload {
    param(
        [string]$ApiKey,
        [string]$FilePath,
        [string]$Description,
        [string]$InstallType,
        [string]$Password
    )

    Add-Type -AssemblyName System.Net.Http

    $client = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $stream = [System.IO.File]::OpenRead($FilePath)

    try {
        $content.Add([System.Net.Http.StringContent]::new($ApiKey), "_api_key")
        $content.Add([System.Net.Http.StringContent]::new($InstallType), "buildInstallType")
        if (-not [string]::IsNullOrWhiteSpace($Password)) {
            $content.Add([System.Net.Http.StringContent]::new($Password), "buildPassword")
        }
        if (-not [string]::IsNullOrWhiteSpace($Description)) {
            $content.Add([System.Net.Http.StringContent]::new($Description), "buildUpdateDescription")
        }

        $fileContent = [System.Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/vnd.android.package-archive")
        $content.Add($fileContent, "file", [System.IO.Path]::GetFileName($FilePath))

        Write-Host "Uploading to Pgyer: $FilePath"
        $response = $client.PostAsync("https://api.pgyer.com/apiv2/app/upload", $content).GetAwaiter().GetResult()
        $responseText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Pgyer upload HTTP failed: status=$([int]$response.StatusCode), body=$responseText"
        }
        return $responseText
    } finally {
        $stream.Dispose()
        $content.Dispose()
        $client.Dispose()
    }
}

$repoRoot = Resolve-RepoRoot
$buildFile = Join-Path $repoRoot "app\build.gradle.kts"
$versionText = Get-AppVersionText -BuildFile $buildFile

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "Missing Pgyer API key. Set environment variable first: `$env:PGYER_API_KEY='your_api_key'"
}

if (-not $SkipBuild) {
    Invoke-GradleReleaseBuild -RepoRoot $repoRoot
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
}
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}

if ([string]::IsNullOrWhiteSpace($UpdateDescription)) {
    $UpdateDescription = "肠乐上位机 $versionText"
}

if ($DryRun) {
    Write-Host "Dry run only. No upload will be performed."
    Write-Host "APK: $ApkPath"
    Write-Host "Description: $UpdateDescription"
    Write-Host "InstallType: $InstallType"
    exit 0
}

$responseText = Invoke-PgyerUpload `
    -ApiKey $ApiKey `
    -FilePath $ApkPath `
    -Description $UpdateDescription `
    -InstallType $InstallType `
    -Password $Password

Write-Host "Pgyer upload response:"
Write-Host $responseText

try {
    $json = $responseText | ConvertFrom-Json
    if ($json.code -ne 0) {
        throw "Pgyer business failed: code=$($json.code), message=$($json.message)"
    }
    $data = $json.data
    if ($null -ne $data) {
        Write-Host "Publish completed: buildVersion=$($data.buildVersion), buildVersionNo=$($data.buildVersionNo), buildBuildVersion=$($data.buildBuildVersion)"
        if ($data.buildQRCodeURL) {
            Write-Host "QR code: $($data.buildQRCodeURL)"
        }
        if ($data.buildShortcutUrl) {
            Write-Host "Shortcut URL: https://www.pgyer.com/$($data.buildShortcutUrl)"
        }
    }
} catch {
    throw "Failed to parse Pgyer response: $($_.Exception.Message)"
}
