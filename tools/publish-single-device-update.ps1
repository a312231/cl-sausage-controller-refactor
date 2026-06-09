param(
    [Parameter(Mandatory = $true)]
    [string]$AdminToken,
    [string]$BaseUrl = "https://iotadmin.cheerluck.com/prod-api",
    [string]$PgyerApiKey = $env:PGYER_API_KEY,
    [string]$BuildKey = "",
    [string]$DeviceCode = "5f744e1045bfab52",
    [int]$TargetVersionCode = 149,
    [string]$ReleaseNotes = "",
    [string]$Channel = "android12",
    [switch]$DeleteOldDeviceStrategies
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($BuildKey)) {
    throw "Missing Pgyer build key. Pass -BuildKey from the Pgyer upload response."
}

function Get-PgyerApkRedirectUrl {
    param(
        [string]$ApiKey,
        [string]$BuildKey
    )

    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        throw "Missing Pgyer API key. Set PGYER_API_KEY or pass -PgyerApiKey."
    }

    $installUrl = "https://api.pgyer.com/apiv2/app/install?_api_key=$ApiKey&buildKey=$BuildKey"
    $headers = & curl.exe -sS -I --max-redirs 0 $installUrl
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to resolve Pgyer APK redirect."
    }
    $location = $headers | Where-Object { $_ -match '^Location:' } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($location)) {
        throw "Pgyer did not return an APK redirect URL."
    }
    return ($location -replace '^Location:\s*', '').Trim()
}

function Invoke-AdminApi {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )

    $headers = @{ Authorization = "Bearer $AdminToken" }
    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -TimeoutSec 60
    }
    $json = $Body | ConvertTo-Json -Depth 8
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body $json -TimeoutSec 120
}

$apkUrl = Get-PgyerApkRedirectUrl -ApiKey $PgyerApiKey -BuildKey $BuildKey
$effectiveReleaseNotes = if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
    "JWS广播静默安装自启动验证版，先用于设备 $DeviceCode 单设备灰度"
} else {
    $ReleaseNotes
}

Write-Host "Syncing APK version to backend..."
$syncResp = Invoke-AdminApi -Method POST -Path "/appUpdate/version/sync" -Body @{
    apkUrl = $apkUrl
    channel = $Channel
    publishStatus = 1
    pgyerBuildKey = $BuildKey
    releaseNotes = $effectiveReleaseNotes
    remark = "single-device-jws-silent-install-verification"
}
$syncedVersion = $syncResp.data
if ($syncResp.code -ne 200 -or $null -eq $syncedVersion) {
    throw "Version sync failed: $($syncResp | ConvertTo-Json -Depth 8)"
}
Write-Host "Version synced: versionCode=$($syncedVersion.versionCode), versionName=$($syncedVersion.versionName), id=$($syncedVersion.id)"

if ($DeleteOldDeviceStrategies) {
    Write-Host "Deleting old device strategies for $DeviceCode except targetVersionCode=$TargetVersionCode..."
    $encodedDeviceCode = [uri]::EscapeDataString($DeviceCode)
    $strategyList = Invoke-AdminApi -Method GET -Path "/appUpdate/strategy/list?pageNum=1&pageSize=100&scopeType=DEVICE&deviceCode=$encodedDeviceCode&channel=$Channel"
    $oldIds = @($strategyList.rows | Where-Object { $_.targetVersionCode -ne $TargetVersionCode } | ForEach-Object { $_.id })
    if ($oldIds.Count -gt 0) {
        Invoke-AdminApi -Method DELETE -Path "/appUpdate/strategy/$($oldIds -join ',')" | Out-Null
        Write-Host "Deleted old strategy IDs: $($oldIds -join ',')"
    } else {
        Write-Host "No old device strategies to delete."
    }
}

Write-Host "Creating single-device update strategy..."
$strategyResp = Invoke-AdminApi -Method POST -Path "/appUpdate/strategy" -Body @{
    scopeType = "DEVICE"
    agentId = $null
    deviceCode = $DeviceCode
    channel = $Channel
    targetVersionCode = $TargetVersionCode
    forceUpdate = 0
    minSupportedVersionCode = $null
    rolloutPercent = 100
    enabled = 1
    effectiveTime = $null
    remark = "$TargetVersionCode JWS静默安装单设备灰度"
}
if ($strategyResp.code -ne 200) {
    throw "Strategy creation failed: $($strategyResp | ConvertTo-Json -Depth 8)"
}
Write-Host "Single-device strategy created and MQTT push should be scheduled by backend."
