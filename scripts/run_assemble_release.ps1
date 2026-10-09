param([string]$WorkingDirectory = $PSScriptRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path (Join-Path $WorkingDirectory 'gradlew'))) {
    throw "gradlew not found in $WorkingDirectory"
}

Set-Location $WorkingDirectory
Write-Host '==> Running :app:assembleRelease only...'
$env:GRADLE_OPTS = '-Dorg.gradle.daemon=false'
& ./gradlew :app:assembleRelease --no-daemon --stacktrace |
    Select-String -Pattern 'e:|BUILD FAILED|BUILD SUCCESSFUL' |
    ForEach-Object { Write-Host $_.Line }
