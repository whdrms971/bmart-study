<#
.SYNOPSIS
  Delivery 백엔드(local-test 프로필)를 실행한다. Mock OMS/MFC·시뮬레이터·테스트 로그인이 활성화된다.
.EXAMPLE
  $env:DELIVERY_TEST_PASSCODE = '<8자 이상 임의 값>'
  .\scripts\start-backend.ps1            # 포그라운드 실행 (Ctrl+C 종료)
  .\scripts\start-backend.ps1 -Background # 백그라운드 실행, 로그: reports\runs\backend.log
#>
param(
    [int]$Port = 8080,
    [switch]$Background,
    [switch]$Postgres
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$svc = Join-Path $root 'delivery-service'

if (-not $env:DELIVERY_TEST_PASSCODE -or $env:DELIVERY_TEST_PASSCODE.Length -lt 8) {
    throw 'DELIVERY_TEST_PASSCODE(8자 이상) 환경변수를 먼저 설정하세요. 예: $env:DELIVERY_TEST_PASSCODE = "<임의값>"'
}
if (-not $env:KAKAO_REST_API_KEY) {
    Write-Warning 'KAKAO_REST_API_KEY 미설정: 주소검색/ETA/카카오 경로는 "확인 불가"로 표시되고 FIXTURE 경로만 사용 가능합니다.'
}
if (-not $env:DELIVERY_AUTH_SECRET) {
    Write-Warning 'DELIVERY_AUTH_SECRET 미설정: 서버 재기동마다 토큰이 무효화됩니다(앱 재로그인 필요).'
}

$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($busy) {
    $owner = (Get-Process -Id $busy.OwningProcess -ErrorAction SilentlyContinue).ProcessName
    throw "포트 $Port 를 이미 다른 프로세스($owner, PID $($busy.OwningProcess))가 사용 중입니다. -Port 로 다른 포트를 지정하세요. 예: -Port 18080"
}

$env:SPRING_PROFILES_ACTIVE = if ($Postgres) { 'local-test,postgres' } else { 'local-test' }
$env:PORT = "$Port"

$ips = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' }).IPAddress
Write-Host "프로필: $env:SPRING_PROFILES_ACTIVE / 포트: $Port"
Write-Host "휴대폰 API_BASE_URL 후보: $(( $ips | ForEach-Object { "http://$($_):$Port" }) -join ', ')"

$logDir = Join-Path $root 'reports\runs'
New-Item -ItemType Directory -Force $logDir | Out-Null
Push-Location $svc
try {
    # Gradle 데몬과 분리된 독립 프로세스로 실행하기 위해 bootJar 를 만든 뒤 java -jar 로 기동
    & (Join-Path $svc 'gradlew.bat') bootJar -q
    if ($LASTEXITCODE -ne 0) { throw 'bootJar 빌드 실패' }
    $jar = Get-ChildItem (Join-Path $svc 'build\libs') -Filter '*.jar' | Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
    $javaArgs = @('-Xmx768m', '-jar', $jar.FullName)
    if ($Background) {
        $log = Join-Path $logDir 'backend.log'
        $err = Join-Path $logDir 'backend.err.log'
        $p = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $svc `
            -RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $p.Id | Out-File (Join-Path $logDir 'backend.pid') -Encoding ascii
        Write-Host "백그라운드 기동 PID=$($p.Id), 로그=$log"
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Seconds 2
            if ($p.HasExited) { throw "서버 프로세스가 종료되었습니다(exit $($p.ExitCode)). $log 확인" }
            try {
                $h = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
                if ($h.status -eq 'UP') { Write-Host '서버 준비 완료 (health UP)'; return }
            } catch { }
        }
        throw "서버가 180초 내에 준비되지 않았습니다. $log 확인"
    } else {
        & java @javaArgs
    }
} finally {
    Pop-Location
}
