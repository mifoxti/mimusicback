param([string]$BaseUrl = "http://127.0.0.1:8080", [int]$TimeoutSec = 10)

Write-Host "1. Port 8080"
$listen = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $listen) {
    Write-Host "   FAIL - nothing listens on 8080. Run: cd mimusicback-master; .\gradlew.bat run"
    exit 1
}
$proc = Get-Process -Id $listen.OwningProcess -ErrorAction SilentlyContinue
Write-Host "   PID $($listen.OwningProcess) $($proc.ProcessName)"

Write-Host "2. GET /"
try {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-WebRequest "$BaseUrl/" -UseBasicParsing -TimeoutSec 5
    $sw.Stop()
    Write-Host "   OK $($sw.ElapsedMilliseconds) ms"
} catch {
    Write-Host "   FAIL $($_.Exception.Message)"
    exit 1
}

Write-Host "3. POST /login loadtest1"
try {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-RestMethod "$BaseUrl/login" -Method POST `
        -Body '{"login":"loadtest1","password":"12345678"}' -ContentType "application/json" -TimeoutSec $TimeoutSec
    $sw.Stop()
    if ($r.token) { Write-Host "   OK $($sw.ElapsedMilliseconds) ms, token length $($r.token.Length)" }
    else { Write-Host "   FAIL no token in response" }
} catch {
    Write-Host "   FAIL $($_.Exception.Message)"
    Write-Host "   Stop old server (Ctrl+C), then:"
    Write-Host "   Stop-Process -Id $($listen.OwningProcess) -Force"
    Write-Host "   cd mimusicback-master"
    Write-Host "   `$env:MUSIC_SCAN_ENABLED='false'; .\gradlew.bat run"
    exit 1
}

Write-Host "Backend ready for k6."
