# Run Gradle with JDK 11+ (Ktor 3 Gradle plugin fails if Gradle uses Java 8).
# Usage: .\run-dev.ps1 run
#        .\run-dev.ps1 --no-daemon run

$ErrorActionPreference = "Stop"

function Test-JavaHome([string]$Home) {
    $java = Join-Path $Home "bin\java.exe"
    if (-not (Test-Path $java)) { return $false }
    $out = & $java -version 2>&1 | Out-String
    if ($out -match '"1\.8\.') { return $false }
    return $true
}

function Normalize-JavaHome([string]$Home) {
    $h = $Home.TrimEnd('\', '/')
    if (Test-Path (Join-Path $h "bin\java.exe")) { return $h }
    # Some setups point JAVA_HOME at ...\jdk...\bin by mistake
    if (Test-Path (Join-Path $h "java.exe")) { return (Split-Path $h -Parent) }
    return $h
}

function Collect-JdkCandidates {
    $list = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) {
        $list.Add((Normalize-JavaHome $env:JAVA_HOME))
    }
    foreach ($p in @(
            "$env:ProgramFiles\Android\Android Studio\jbr",
            "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
            "$env:LOCALAPPDATA\Programs\Android\Android Studio\jbr",
            "$env:LOCALAPPDATA\Android\Sdk\jbr",
            "C:\Program Files\Microsoft",
            "C:\Program Files\Java",
            "C:\Program Files\Eclipse Adoptium",
            "C:\Program Files\Amazon Corretto",
            "D:\AmazonCoretto"
        )) {
        if (-not (Test-Path $p)) { continue }
        if ($p -match 'Microsoft$|Java$|Adoptium$|Amazon Corretto$') {
            Get-ChildItem $p -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $j = $_.FullName
                if (Test-Path (Join-Path $j "bin\java.exe")) { $list.Add($j) }
            }
        } else {
            $list.Add($p)
        }
    }
    # JetBrains Toolbox Android Studio installs (best-effort glob)
    $tb = "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\AndroidStudio"
    if (Test-Path $tb) {
        Get-ChildItem $tb -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $jbr = Join-Path $_.FullName "jbr"
                if (Test-Path (Join-Path $jbr "bin\java.exe")) { $list.Add($jbr) }
            }
        }
    }
    return $list | Select-Object -Unique
}

$jdk = $null
foreach ($c in Collect-JdkCandidates) {
    if ([string]::IsNullOrWhiteSpace($c)) { continue }
    if (-not (Test-Path $c)) { continue }
    if (Test-JavaHome $c) { $jdk = $c; break }
}

if (-not $jdk) {
    Write-Host @"
JDK 11+ not found (Gradle is using Java 8 from PATH).

Fix one of:
  1) Install Temurin 17: https://adoptium.net/temurin/releases/?version=17
  2) In THIS terminal before run:
       `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  3) Uncomment org.gradle.java.home in gradle.properties

Current java on PATH:
"@
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) { Write-Host $j.Source }
    try { & java -version 2>&1 | ForEach-Object { Write-Host $_ } } catch { }
    exit 1
}

$env:JAVA_HOME = $jdk
$env:PATH = "$(Join-Path $jdk 'bin');$env:PATH"
Write-Host "Using JAVA_HOME=$jdk"
& "$PSScriptRoot\gradlew.bat" @args
