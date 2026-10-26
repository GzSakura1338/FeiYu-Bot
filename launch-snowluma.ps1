param([string]$Qq = "", [string]$QqExe = "", [switch]$NoWatch)

$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot "snowluma"
$api = "http://127.0.0.1:5099/api"
$passwordFile = Join-Path $root "webui-password.txt"
$botFile = Join-Path $root "bot-qq.txt"
$attachedStates = @("loading", "connecting", "loaded", "online")

if (-not (Test-Path (Join-Path $root "index.mjs"))) {
    Write-Host "SnowLuma is not installed: unzip SnowLuma-<version>-win-x64.zip into $root"
    exit 1
}
if (-not $Qq -and (Test-Path $botFile)) {
    $Qq = (Get-Content $botFile -Raw).Trim()
}

function Test-WebUi {
    try {
        Invoke-WebRequest "http://127.0.0.1:5099/robots.txt" -UseBasicParsing -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Find-QqExe {
    if ($QqExe) { return $QqExe }
    foreach ($key in "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\QQ", "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\QQ") {
        $uninstall = (Get-ItemProperty $key -ErrorAction SilentlyContinue).UninstallString
        if ($uninstall) {
            $exe = Join-Path (Split-Path $uninstall.Trim('"')) "QQ.exe"
            if (Test-Path $exe) { return $exe }
        }
    }
    $running = Get-Process QQ -ErrorAction SilentlyContinue | Where-Object { $_.Path } | Select-Object -First 1
    if ($running) { return $running.Path }
    return Join-Path $env:ProgramFiles "Tencent\QQNT\QQ.exe"
}

function Invoke-SnowLuma([string]$path, [string]$method = "Get") {
    $warned = $false
    for ($i = 0; $i -lt 300; $i++) {
        try {
            return Invoke-RestMethod "$api$path" -Method $method -Headers $script:headers -TimeoutSec 60
        } catch {
            $code = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { 0 }
            if ($code -ne 403 -or "$($_.ErrorDetails.Message)" -notmatch "consentRequired") { throw }
            if (-not $warned) {
                Write-Host "First run: open http://127.0.0.1:5099, sign in with the password in snowluma\webui-password.txt"
                Write-Host "and accept the EULA and privacy policy there. Waiting..."
                $warned = $true
            }
            Start-Sleep -Seconds 3
        }
    }
    throw "the SnowLuma agreements were not accepted"
}

function Test-Bot($p) {
    return -not $Qq -or $p.uin -eq $Qq -or ($p.account -and $p.account.uin -eq $Qq)
}

function Connect-WebUi {
    $login = Invoke-RestMethod "$api/login" -Method Post -ContentType "application/json" `
        -Body (@{ password = (Get-Content $passwordFile -Raw).Trim() } | ConvertTo-Json)
    $script:headers = @{ "x-snowluma-token" = $login.token }
}

function Watch-Bot {
    if ($NoWatch -or -not $Qq) { exit 0 }
    Write-Host "Keeping QQ $Qq attached: when that QQ restarts, SnowLuma is attached to it again. Closing this window only stops this check."
    $tries = @{}
    while ($true) {
        Start-Sleep -Seconds 15
        try {
            $list = @((Invoke-SnowLuma "/processes").list)
        } catch {
            try { Connect-WebUi } catch { }
            continue
        }
        if ($list | Where-Object { ($_.injected -or $attachedStates -contains $_.status) -and (Test-Bot $_) }) { continue }
        $p = $list | Where-Object { $_.account -and $_.account.uin -eq $Qq } | Select-Object -First 1
        if (-not $p -or $tries[$p.pid] -ge 3) { continue }
        $tries[$p.pid] = 1 + $tries[$p.pid]
        $time = Get-Date -Format "MM-dd HH:mm:ss"
        try {
            $r = Invoke-SnowLuma "/processes/$($p.pid)/load" "Post"
            Write-Host "$time QQ $Qq is running again (pid $($p.pid)), attached: $($r.success) $($r.process.error)"
        } catch {
            Write-Host "$time attaching to QQ pid $($p.pid) failed: $($_.Exception.Message)"
        }
    }
}

if (-not (Test-WebUi)) {
    if (-not (Test-Path (Join-Path $root "config\webui.json"))) {
        if (-not (Test-Path $passwordFile)) {
            $chars = [char[]]"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            $bytes = New-Object byte[] 24
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
            $generated = "Sl" + (-join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })) + "7"
            Set-Content -Path $passwordFile -Value $generated -Encoding ASCII -NoNewline
        }
        $env:SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD = (Get-Content $passwordFile -Raw).Trim()
    }
    $env:SNOWLUMA_HOOK_AUTOLOAD = "0"
    Start-Process cmd.exe -ArgumentList "/k", ".\node.exe index.mjs" -WorkingDirectory $root -WindowStyle Minimized
    Remove-Item Env:SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 60 -and -not (Test-WebUi); $i++) { Start-Sleep -Seconds 1 }
    if (-not (Test-WebUi)) {
        Write-Host "SnowLuma did not start, check its window"
        exit 1
    }
    Write-Host "SnowLuma started, WebUI http://127.0.0.1:5099"
}

if (-not (Test-Path $passwordFile)) {
    Write-Host "No snowluma\webui-password.txt: load the bot's QQ process in the WebUI yourself, or save the WebUI password in that file"
    exit 0
}
try {
    Connect-WebUi
} catch {
    Write-Host "Cannot sign in to the SnowLuma WebUI ($($_.ErrorDetails.Message)). If you changed the password there, put the new one in snowluma\webui-password.txt"
    exit 1
}

$list = @((Invoke-SnowLuma "/processes").list)
$attached = $list | Where-Object { ($_.injected -or $attachedStates -contains $_.status) -and (Test-Bot $_) } | Select-Object -First 1
if ($attached) {
    Write-Host "SnowLuma is already attached to QQ $($attached.uin) (pid $($attached.pid), $($attached.status))"
    Watch-Bot
}

$target = if ($Qq) { $list | Where-Object { $_.account -and $_.account.uin -eq $Qq } | Select-Object -First 1 }
if (-not $target) {
    $exe = Find-QqExe
    $started = Start-Process $exe -PassThru
    Write-Host "Started a new QQ (pid $($started.Id)). Log in to the bot account in that QQ window."
    for ($i = 0; $i -lt 60 -and -not $target; $i++) {
        Start-Sleep -Seconds 1
        $target = @((Invoke-SnowLuma "/processes").list) | Where-Object { $_.pid -eq $started.Id } | Select-Object -First 1
    }
    if (-not $target) {
        Write-Host "SnowLuma cannot see QQ pid $($started.Id)"
        exit 1
    }
}

$result = Invoke-SnowLuma "/processes/$($target.pid)/load" "Post"
if (-not $result.success) {
    Write-Host "Injecting into QQ pid $($target.pid) failed: $($result.process.error)$($result.message)"
    exit 1
}
Write-Host "SnowLuma attached to QQ pid $($target.pid), waiting for the login (up to 10 minutes)..."
for ($i = 0; $i -lt 300; $i++) {
    $p = @((Invoke-SnowLuma "/processes").list) | Where-Object { $_.pid -eq $target.pid } | Select-Object -First 1
    if (-not $p) {
        Write-Host "QQ pid $($target.pid) exited"
        exit 1
    }
    if ($p.status -eq "online" -and $p.uin) {
        Set-Content -Path $botFile -Value $p.uin -Encoding ASCII -NoNewline
        Write-Host "QQ $($p.uin) is online, FeiYu Bot will be connected in a few seconds"
        $Qq = $p.uin
        Watch-Bot
    }
    Start-Sleep -Seconds 2
}
Write-Host "Still not logged in. Finish the login in the QQ window; SnowLuma keeps waiting."
Watch-Bot
