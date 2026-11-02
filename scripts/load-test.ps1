<#
.SYNOPSIS
  부하 테스트: 일회용 PostgreSQL + 별도 포트의 local-test 서버(부하용 계정 N명)를 띄우고 simulator-admin/e2e/load-test.ts 실행.
  끝나면 서버와 컨테이너를 정리한다. 개발 DB·실행 중인 다른 서버는 건드리지 않는다.
.EXAMPLE
  $env:DELIVERY_TEST_PASSCODE = '<임의 값>'
  .\scripts\load-test.ps1 -Riders 30 -DurationSeconds 60
#>
param(
    [int]$Riders = 30,
    [int]$DurationSeconds = 60,
    [int]$Port = 18082,
    [int]$DbPort = 25434
)
$ErrorActionPreference = 'Stop'
if (-not $env:DELIVERY_TEST_PASSCODE) { throw 'DELIVERY_TEST_PASSCODE 를 설정하세요' }
$root = Split-Path -Parent $PSScriptRoot
foreach ($p in $Port, $DbPort) { if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { throw "포트 $p 사용 중" } }
$name = "delivery-pg-load-$(Get-Random -Maximum 99999)"
$pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
docker run -d --rm --name $name -e POSTGRES_DB=delivery_load -e POSTGRES_USER=delivery -e "POSTGRES_PASSWORD=$pw" -p "127.0.0.1:${DbPort}:5432" postgres:17-alpine | Out-Null
$server = $null
$code = 1
try {
    for ($i = 0; $i -lt 60; $i++) { if ("$(docker exec $name pg_isready -U delivery -d delivery_load 2>&1)" -match 'accepting') { break }; Start-Sleep 1 }
    $ids = 1..$Riders | ForEach-Object { '{0:D3}' -f $_ }
    $accounts = @{ delivery = @{ 'test-accounts' = @{
                riders = @($ids | ForEach-Object { "R-LOAD-$_" }); customers = @($ids | ForEach-Object { "C-LOAD-$_" }); admins = @('ADMIN-TEST') } } }
    $env:SPRING_APPLICATION_JSON = ($accounts | ConvertTo-Json -Depth 6 -Compress)
    $env:SPRING_PROFILES_ACTIVE = 'local-test,postgres'; $env:PORT = "$Port"
    $env:DELIVERY_DB_URL = "jdbc:postgresql://127.0.0.1:${DbPort}/delivery_load"; $env:DELIVERY_DB_USER = 'delivery'; $env:DELIVERY_DB_PASSWORD = $pw
    $jar = Get-ChildItem (Join-Path $root 'delivery-service\build\libs') -Filter '*.jar' | Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
    if (-not $jar) { throw 'bootJar 없음 — delivery-service 에서 gradlew bootJar 먼저' }
    $log = Join-Path $root 'reports\runs\backend-load.log'
    New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
    $server = Start-Process java -ArgumentList '-Xmx1g', '-jar', $jar.FullName -WorkingDirectory (Join-Path $root 'delivery-service') `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    $up = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep 2
        if ($server.HasExited) { throw "부하 서버 기동 실패. $log 확인" }
        try { if ((Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2).status -eq 'UP') { $up = $true; break } } catch { }
    }
    if (-not $up) { throw '부하 서버 준비 시간 초과' }
    Write-Host "부하 서버 준비: 127.0.0.1:$Port (PostgreSQL $name), 라이더·고객 각 $Riders 명"
    Push-Location (Join-Path $root 'simulator-admin')
    try {
        $env:API_URL = "http://127.0.0.1:$Port"; $env:RIDERS = "$Riders"; $env:DURATION_S = "$DurationSeconds"
        npx tsx e2e/load-test.ts
        $code = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    Remove-Item env:SPRING_APPLICATION_JSON -ErrorAction SilentlyContinue
    if ($server) {
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$($server.Id)" -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue }
        if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -Confirm:$false -ErrorAction SilentlyContinue }
    }
    docker rm -f $name 2>&1 | Out-Null
    Write-Host "정리: 부하 서버 종료, 컨테이너 $name 삭제"
}
exit $code
