param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [int]$Count = 30,
    [string]$Password = "12345678",
    [string]$InviteCode = "TESTK-EYDEV-BUILD",
    [int]$TimeoutSec = 20
)

Write-Host "Check server $BaseUrl ..."
try {
    $null = Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing -TimeoutSec 5
    Write-Host "Server OK"
} catch {
    Write-Host "Server not reachable. Start: cd mimusicback-master; .\gradlew.bat run"
    exit 1
}

for ($i = 1; $i -le $Count; $i++) {
    $login = "loadtest$i"
    Write-Host -NoNewline "[$i/$Count] $login ... "

    $loginBody = @{ login = $login; password = $Password } | ConvertTo-Json
    try {
        $null = Invoke-RestMethod -Uri "$BaseUrl/login" -Method POST -Body $loginBody `
            -ContentType "application/json" -TimeoutSec $TimeoutSec
        Write-Host "SKIP (exists)"
        continue
    } catch {
        # not registered yet
    }

    $regBody = @{ login = $login; password = $Password; inviteCode = $InviteCode } | ConvertTo-Json
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/register" -Method POST -Body $regBody `
            -ContentType "application/json" -TimeoutSec $TimeoutSec
        Write-Host "OK id=$($r.id)"
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq 409) { Write-Host "SKIP (exists)" }
        else { Write-Host "FAIL HTTP $code" }
    }
}

Write-Host "Done. k6: `$env:K6_MULTI_USER='1'; `$env:K6_PASSWORD='$Password'"
