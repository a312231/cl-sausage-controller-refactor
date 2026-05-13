# 蒲公英发布脚本

用于把已更新版本号的 release APK 自动上传到蒲公英，避免每次打开网页手动发布。

## 首次配置

在 PowerShell 中设置一次蒲公英 API Key：

```powershell
[Environment]::SetEnvironmentVariable("PGYER_API_KEY", "你的_api_key", "User")
```

重新打开 PowerShell 后即可使用。

## 发布命令

在仓库根目录运行：

```powershell
.\tools\publish-pgyer.ps1 -UpdateDescription "2.0.13：完善真实下位机下单失败诊断"
```

脚本会执行：

1. `.\gradlew.bat :app:assembleRelease`
2. 上传 `app\build\outputs\apk\release\app-release.apk`
3. 输出蒲公英返回的版本信息、二维码和短链接

如果 APK 已经构建好，可以跳过构建：

```powershell
.\tools\publish-pgyer.ps1 -SkipBuild -ApkPath ".\app\build\outputs\apk\release\app-release.apk"
```

只检查参数和 APK 路径、不上传：

```powershell
.\tools\publish-pgyer.ps1 -SkipBuild -DryRun -ApkPath ".\app\build\outputs\apk\release\app-release.apk"
```

默认公开安装。需要密码安装时：

```powershell
.\tools\publish-pgyer.ps1 -InstallType 2 -Password "123456" -UpdateDescription "更新说明"
```
