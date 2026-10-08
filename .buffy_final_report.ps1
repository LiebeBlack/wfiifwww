$lines = Get-Content '.github/workflows/release.yml'
Write-Host '=== remaining ${{ }} expressions (line: content) ==='
$lines | Select-String -Pattern '\$\{\{.*?\}\}' | ForEach-Object { Write-Host "$($_.LineNumber): $($_.Line)" }
Write-Host ''
Write-Host '=== cache key context (lines 44-52) ==='
$lines[43..51] | ForEach-Object { Write-Host $_ }
Write-Host ''
Write-Host '=== release step context (lines 88-98) ==='
$lines[87..97] | ForEach-Object { Write-Host $_ }
