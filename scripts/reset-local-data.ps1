<#
.SYNOPSIS  local-test H2 데이터·증빙 사진을 삭제한다(서버를 먼저 종료). 운영 DB 와 무관.
#>
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $root 'delivery-service\data'
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw '서버가 실행 중입니다. 먼저 .\scripts\stop-backend.ps1' }
if (Test-Path $data) { Remove-Item -Recurse -Force $data -Confirm:$false; Write-Host "삭제: $data" } else { Write-Host '삭제할 데이터 없음' }
