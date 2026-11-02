<#
.SYNOPSIS
  delivery-service 자동 테스트 전체를 PostgreSQL 에서 실행한다 (기본 gradlew test 는 H2 PostgreSQL 모드).
  일회용 PostgreSQL 컨테이너를 띄우고, 테스트 후 항상 삭제한다. 기존 컨테이너·데이터는 건드리지 않는다.
.EXAMPLE
  .\scripts\test-postgres.ps1
  .\scripts\test-postgres.ps1 -Tests 'com.baropick.delivery.DeliveryLifecycleIT'
#>
param(
    [int]$Port = 25433,
    [string]$Image = 'postgres:17-alpine',
    [string]$Tests = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$name = "delivery-pg-test-$(Get-Random -Maximum 99999)"
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "포트 $Port 사용 중. -Port 로 바꾸세요" }
$pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })

docker run -d --rm --name $name -e POSTGRES_DB=delivery_test -e POSTGRES_USER=delivery -e "POSTGRES_PASSWORD=$pw" -p "127.0.0.1:${Port}:5432" $Image | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 컨테이너 시작 실패 (Docker 실행 여부 확인)' }
$code = 1
try {
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ("$(docker exec $name pg_isready -U delivery -d delivery_test 2>&1)" -match 'accepting') { $ready = $true; break }
        Start-Sleep 1
    }
    if (-not $ready) { throw 'PostgreSQL 준비 시간 초과' }
    Write-Host "PostgreSQL 준비: $name (127.0.0.1:$Port)"
    Push-Location (Join-Path $root 'delivery-service')
    try {
        $gradleArgs = @('test', '--console=plain', "-PtestDbUrl=jdbc:postgresql://127.0.0.1:${Port}/delivery_test", '-PtestDbUser=delivery', "-PtestDbPassword=$pw")
        if ($Tests) { $gradleArgs += @('--tests', $Tests) }
        & .\gradlew.bat @gradleArgs
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    Write-Host "결과 보고서: delivery-service\build\reports\tests\test\index.html"
} finally {
    docker rm -f $name 2>&1 | Out-Null
    Write-Host "컨테이너 삭제: $name"
}
exit $code
