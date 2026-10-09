param([string]$WorkingDirectory = $PSScriptRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path (Join-Path $WorkingDirectory 'gradlew'))) {
    throw "gradlew not found in $WorkingDirectory"
}

Set-Location $WorkingDirectory
Write-Host '==> Running compileReleaseKotlin only...'
$env:GRADLE_OPTS = '-Dorg.gradle.daemon=false'
& ./gradlew :app:compileReleaseKotlin --no-daemon
