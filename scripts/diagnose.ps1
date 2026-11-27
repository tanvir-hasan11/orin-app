param(
    [int]$Seconds = 60,
    [string]$Apk = "release\orin-1.5.1-release.apk"
)

$ErrorActionPreference = "Continue"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$log = "logs\orin-diagnostic.log"
New-Item -ItemType Directory -Force -Path logs | Out-Null

function Adb([string[]]$args) { & $adb @args 2>&1 }

Write-Host "== Waiting for device =="
Adb @("wait-for-device") | Out-Null
$serial = (Adb @("get-serialno") | Select-Object -Last 1).Trim()
Write-Host "Device: $serial"

Write-Host "== Installing $Apk =="
Adb @("install", "-r", $Apk) | Select-Object -Last 3

Write-Host "== Granting runtime permissions =="
$perms = @(
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.CAMERA",
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.SCHEDULE_EXACT_ALARM"
)
foreach ($p in $perms) { Adb @("shell", "pm", "grant", "com.orin.app", $p) | Out-Null }
Adb @("shell", "appops", "set", "com.orin.app", "SYSTEM_ALERT_WINDOW", "allow") | Out-Null

Write-Host "== Enabling accessibility service =="
Adb @("shell", "settings", "put", "secure", "enabled_accessibility_services", "com.orin.app/com.orin.app.control.OrinControlService") | Out-Null
Adb @("shell", "settings", "put", "secure", "accessibility_enabled", "1") | Out-Null

Write-Host "== Enabling notification listener =="
Adb @("shell", "settings", "put", "secure", "enabled_notification_listeners", "com.orin.app/com.orin.app.notify.OrinNotificationListener") | Out-Null

Write-Host "== Launching Orin =="
Adb @("shell", "am", "force-stop", "com.orin.app") | Out-Null
Adb @("shell", "am", "start", "-n", "com.orin.app/.MainActivity") | Out-Null
Adb @("logcat", "-c") | Out-Null

Write-Host "== Capturing logcat for $Seconds seconds — use the app now =="
$job = Start-Job -ScriptBlock {
    param($adbPath, $outFile)
    & $adbPath logcat -v threadtime *:V > $outFile 2>&1
} -ArgumentList $adb, (Join-Path (Get-Location) $log)
Start-Sleep -Seconds $Seconds
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue

Write-Host "== Crash / error lines =="
Select-String -LiteralPath $log -Pattern "FATAL EXCEPTION|AndroidRuntime|com\.orin\.app.*(E|Exception|Error)|E/(AndroidRuntime|Orin)" |
    Select-Object -Last 80 | ForEach-Object { $_.Line }

Write-Host "== Screenshot =="
Adb @("exec-out", "screencap", "-p") > logs\screen.png
Write-Host "Log saved to $log"
