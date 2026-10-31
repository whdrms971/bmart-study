<#
.SYNOPSIS  start-backend.ps1 -Background 로 띄운 서버와 그 하위 java 프로세스를 종료한다.
#>
param([int]$Port = 8080)
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root 'reports\runs\backend.pid'
# 포트를 점유한 java 프로세스(실제 Spring Boot)를 우선 종료
$conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) {
    try { Stop-Process -Id $c.OwningProcess -Force -Confirm:$false -ErrorAction Stop; Write-Host "포트 $Port 프로세스 $($c.OwningProcess) 종료" } catch { }
}
if (Test-Path $pidFile) {
    $id = Get-Content $pidFile
    try { Stop-Process -Id $id -Force -Confirm:$false -ErrorAction Stop; Write-Host "gradle 래퍼 $id 종료" } catch { }
    Remove-Item $pidFile -Force
}
Write-Host '종료 완료 (시뮬레이터 실행 작업은 서버 프로세스와 함께 모두 정리됨)'
