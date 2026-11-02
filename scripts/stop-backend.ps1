<#
.SYNOPSIS  start-backend.ps1 -Background 로 띄운 Delivery 서버만 종료한다.
           같은 포트를 쓰는 다른 프로그램(Docker 등)은 건드리지 않는다.
#>
param([int]$Port = 8080)
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root 'reports\runs\backend.pid'

function Test-DeliveryJava([int]$id) {
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
    return ($cim -and $cim.Name -match '^java(w)?\.exe$' -and $cim.CommandLine -match 'delivery-service')
}

$stopped = $false
if (Test-Path $pidFile) {
    $id = [int](Get-Content $pidFile)
    if (Test-DeliveryJava $id) {
        Stop-Process -Id $id -Force -Confirm:$false; Write-Host "Delivery 서버 PID $id 종료"; $stopped = $true
    }
    Remove-Item $pidFile -Force
}
# pid 파일이 없거나 맞지 않을 때: 포트 점유자가 Delivery java 인 경우에만 종료
foreach ($c in (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
    if (Test-DeliveryJava $c.OwningProcess) {
        Stop-Process -Id $c.OwningProcess -Force -Confirm:$false; Write-Host "포트 $Port Delivery 서버 PID $($c.OwningProcess) 종료"; $stopped = $true
    } else {
        Write-Host "포트 $Port 는 Delivery 서버가 아닌 프로세스(PID $($c.OwningProcess))가 사용 중 — 종료하지 않음"
    }
}
if ($stopped) { Write-Host '종료 완료 (시뮬레이터 실행 작업은 서버 프로세스와 함께 모두 정리됨)' } else { Write-Host '종료할 Delivery 서버 없음' }
