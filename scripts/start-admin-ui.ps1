<#
.SYNOPSIS  React+TS 시뮬레이터 관리 화면(Vite dev server) 실행. 기본 http://127.0.0.1:5173
.EXAMPLE
  .\scripts\start-admin-ui.ps1
  .\scripts\start-admin-ui.ps1 -Port 15173 -Backend http://127.0.0.1:18080   # 기본 포트를 다른 프로그램이 쓸 때
#>
param(
    [string]$Backend = 'http://127.0.0.1:8080',
    [int]$Port = 5173
)
$root = Split-Path -Parent $PSScriptRoot
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "포트 $Port 를 이미 다른 프로세스가 사용 중입니다. -Port 로 다른 포트를 지정하세요."
}
Push-Location (Join-Path $root 'simulator-admin')
try {
    if (-not (Test-Path node_modules)) { npm install --no-audit --no-fund }
    $env:VITE_BACKEND = $Backend
    $env:VITE_PORT = "$Port"
    Write-Host "관리 화면: http://127.0.0.1:$Port  (백엔드 $Backend)"
    npm run dev
} finally { Pop-Location }
