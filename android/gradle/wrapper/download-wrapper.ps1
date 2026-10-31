# 一键下载 gradle-wrapper.jar（Gradle 8.10.2 官方文件）
# 用法：powershell -ExecutionPolicy Bypass -File download-wrapper.ps1
$jar = Join-Path $PSScriptRoot 'gradle-wrapper.jar'
$url = 'https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar'
Invoke-WebRequest -Uri $url -OutFile $jar
Write-Host "已下载到: $jar ($((Get-Item $jar).Length) bytes)"
