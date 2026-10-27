# Worker N개를 한 번에 실행 (로컬 PC, Windows PowerShell)
#   .\scripts\run_workers.ps1 -AssignerHost 3.35.xx.xx -N 6 [-Port 8000] [-LogDir run1_workers]
# N은 Assigner의 INIT 로그(N=...)를 보고 똑같이 넣는다.
param(
    [Parameter(Mandatory = $true)][string]$AssignerHost,
    [Parameter(Mandatory = $true)][int]$N,
    [int]$Port = 8000,
    [string]$LogDir = "run_workers"
)
Set-Location (Join-Path $PSScriptRoot "..")
New-Item -ItemType Directory -Force $LogDir | Out-Null
$procs = @()
for ($i = 0; $i -lt $N; $i++) {
    $procs += Start-Process java -ArgumentList "-cp build\classes Worker --host $AssignerHost --port $Port --logdir $LogDir" `
        -NoNewWindow -PassThru -RedirectStandardOutput "$LogDir\console_$i.txt" -RedirectStandardError "$LogDir\console_err_$i.txt"
}
Write-Output "started $N workers -> $AssignerHost`:$Port (logs: $LogDir\Worker{k}.txt)"
$procs | Wait-Process
Write-Output "all workers finished"
