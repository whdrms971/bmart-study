<#
.SYNOPSIS  지정 포트(-Port, 기본 8080)에서 실행 중인 Delivery 서버만 종료한다.
           같은 포트의 다른 프로그램(Docker 등)이나 다른 포트의 Delivery 서버는 건드리지 않는다.
#>
param([int]$Port = 8080)
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root 'reports\runs\backend.pid'

function Get-Proc([int]$id) { Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue }
function Test-DeliveryJava($cim) { $cim -and $cim.Name -match '^java(w)?\.exe$' -and $cim.CommandLine -match 'delivery-service' }

$stopped = $false
foreach ($c in (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
    $proc = Get-Proc $c.OwningProcess
    if (-not (Test-DeliveryJava $proc)) {
        Write-Host "포트 $Port 는 Delivery 서버가 아닌 프로세스(PID $($c.OwningProcess))가 사용 중 — 종료하지 않음"
        continue
    }
    # Oracle javapath 런처는 실제 java 를 자식으로 띄운다. 부모도 Delivery java 면 함께 종료
    $parent = Get-Proc $proc.ParentProcessId
    Stop-Process -Id $proc.ProcessId -Force -Confirm:$false
    Write-Host "포트 $Port Delivery 서버 PID $($proc.ProcessId) 종료"
    if (Test-DeliveryJava $parent) {
        Stop-Process -Id $parent.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue
        Write-Host "  런처 PID $($parent.ProcessId) 종료"
    }
    if ((Test-Path $pidFile) -and ((Get-Content $pidFile) -in @("$($proc.ProcessId)", "$($parent.ProcessId)"))) { Remove-Item $pidFile -Force }
    $stopped = $true
}
if ($stopped) { Write-Host '종료 완료 (시뮬레이터 실행 작업은 서버 프로세스와 함께 모두 정리됨)' } else { Write-Host "포트 $Port 에서 종료할 Delivery 서버 없음" }
