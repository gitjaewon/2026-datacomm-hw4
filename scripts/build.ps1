# 컴파일 (Windows PowerShell). 결과: build\classes
Set-Location (Join-Path $PSScriptRoot "..")
New-Item -ItemType Directory -Force build\classes | Out-Null
javac -encoding UTF-8 -d build\classes (Get-ChildItem src\*.java).FullName
if ($LASTEXITCODE -eq 0) { Write-Output "build OK -> build\classes" }
