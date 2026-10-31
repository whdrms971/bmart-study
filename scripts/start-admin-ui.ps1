<#
.SYNOPSIS  React+TS 시뮬레이터 관리 화면(Vite dev server) 실행. http://127.0.0.1:5173
#>
param([string]$Backend = 'http://127.0.0.1:8080')
$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root 'simulator-admin')
try {
    if (-not (Test-Path node_modules)) { npm install --no-audit --no-fund }
    $env:VITE_BACKEND = $Backend
    npm run dev
} finally { Pop-Location }
