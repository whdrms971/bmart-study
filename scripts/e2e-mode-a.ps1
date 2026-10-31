<#
.SYNOPSIS
  모드 A(SIMULATED_ROUTE) 자동 모의 E2E — 실행 중인 local-test 서버에 실제 HTTP 로 고객·라이더·관리자 역할을 수행한다.
  Android 실기기 검증을 대체하지 않는다(결과 보고서에 명시).
.EXAMPLE
  .\scripts\e2e-mode-a.ps1 -Rate 10
  .\scripts\e2e-mode-a.ps1 -RouteSource KAKAO_DIRECTIONS -Rate 10   # 카카오 길찾기 실호출 1회
#>
param(
    [string]$Base = 'http://127.0.0.1:8080',
    [ValidateSet('FIXTURE', 'KAKAO_DIRECTIONS')][string]$RouteSource = 'FIXTURE',
    [ValidateSet(1, 5, 10)][int]$Rate = 10,
    [string]$Customer = 'C-TEST-01',
    [string]$Rider = 'R-TEST-01',
    [int]$Packages = 2
)
$ErrorActionPreference = 'Stop'
if (-not $env:DELIVERY_TEST_PASSCODE) { throw 'DELIVERY_TEST_PASSCODE 를 서버와 같은 값으로 설정하세요' }
$root = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $root 'reports\runs'
New-Item -ItemType Directory -Force $runDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path $runDir "mode-a-$($RouteSource.ToLower())-$stamp.md"
$lines = [System.Collections.Generic.List[string]]::new()
function Log([string]$m) { $t = (Get-Date).ToString('HH:mm:ss.fff'); Write-Host "$t $m"; $lines.Add("- ``$t`` $m") }

function Login($u) { (Invoke-RestMethod -Method Post "$Base/api/auth/test-login" -ContentType 'application/json' -Body (@{ userId = $u; passcode = $env:DELIVERY_TEST_PASSCODE } | ConvertTo-Json)).accessToken }
$tok = @{ admin = Login 'ADMIN-TEST'; cust = Login $Customer; rider = Login $Rider }
function Call($who, $method, $path, $body = $null) {
    $h = @{ Authorization = "Bearer $($tok[$who])" }
    $args = @{ Method = $method; Uri = "$Base$path"; Headers = $h }
    if ($null -ne $body) { $args.ContentType = 'application/json'; $args.Body = ($body | ConvertTo-Json -Depth 6) }
    try { Invoke-RestMethod @args } catch {
        $msg = $_.ErrorDetails.Message; throw "$method $path 실패: $msg"
    }
}
function Expect-Fail($who, $method, $path, $code, $body = $null) {
    try { Call $who $method $path $body | Out-Null; throw "예상 실패($code)가 성공함: $path" }
    catch { if ($_.Exception.Message -notmatch $code) { throw "예상 코드 $code 아님: $($_.Exception.Message)" }; Log "기대한 거부 확인: $path → $code" }
}

$lines.Add("# 모드 A 모의 E2E 보고서 ($stamp)")
$lines.Add('')
$lines.Add("- 서버: $Base (local-test) / 위치 출처: **SIMULATED_ROUTE** / 경로 출처: **$RouteSource** / 배속 ${Rate}×")
$lines.Add('- OMS·MFC: **Mock** (계약 기반). 배차·인계·위치 검증·완료 판정: **실제 Delivery 구현**')
$lines.Add('- Android 실기기: **미사용** — 이 결과는 실기기 E2E 통과가 아니다 `[미검증]`')
$lines.Add('')
$lines.Add('## 실행 로그')

# 1. 테스트 주문 (원거리 가상 MFC → FIXTURE 전용 배송지 T1)
$fx = (Call admin GET '/test-api/simulations/fixtures')[0]
$order = Call cust POST '/test-api/mock-oms/orders' @{ order = @{ mfcId = $fx.mfcId; latitude = $fx.destLat; longitude = $fx.destLng; address = $fx.destinationAddress; addressDetail = '101호'; totalWeightGrams = 3000; packageCount = $Packages; autoSeal = $false } }
$d = $order.deliveries[0].deliveryId
Log "주문 $($order.orderId) / 배송 $d (포장 $Packages) 생성 — Mock OMS → Delivery /internal/delivery-requests"

# 2. 배차: 라이더 운행 시작 → 서버 배차 스케줄러 제안 → 수락
Call rider POST '/api/rider/me/duty' @{ dutyStatus = 'ON_DUTY_ACCEPTING' } | Out-Null
$offer = $null
for ($i = 0; $i -lt 30 -and -not $offer; $i++) { Start-Sleep -Milliseconds 700; $offer = (Call rider GET '/api/rider/offers') | Where-Object { $_.deliveryId -eq $d } | Select-Object -First 1 }
if (-not $offer) { throw '배차 제안이 오지 않았습니다 (다른 라이더가 운행 중이면 먼저 수락됐을 수 있음)' }
Log "배차 제안 수신 offer=$($offer.offerId) 남은 $($offer.remainingSeconds)s"
$acc = Call rider POST "/api/rider/offers/$($offer.offerId)/accept"
Log "배차 수락 → $($acc.result) (배정버전 $($acc.assignmentVersion))"

# 3. 시뮬레이터 생성·검증. 인계 전 START 거부 확인
$sim = Call admin POST '/test-api/simulations' @{ deliveryId = $d; riderId = $Rider; routeSource = $RouteSource; fixtureId = $fx.id; rate = $Rate; seed = 42; faults = @() }
$sid = $sim.scenarioId
$prep = Call admin POST "/test-api/simulations/$sid/prepare"
$route = Call admin GET "/test-api/simulations/$sid/route"
Log "시나리오 $sid → $($prep.state), 경로 $($route.source) 점 $($route.pointCount)개 $([math]::Round($route.totalMeters))m ($($route.note))"
Expect-Fail admin POST "/test-api/simulations/$sid/start" 'HANDOVER_NOT_COMPLETED'

# 4. MFC 도착 → Mock MFC 포장 완료 → 전 포장 QR 인계
Call rider POST "/api/rider/deliveries/$d/arrive-mfc" | Out-Null
Call admin POST "/test-api/mock-mfc/deliveries/$d/seal" | Out-Null
$qrs = (Call admin GET "/test-api/mock-mfc/deliveries/$d/packages").qrCode
for ($k = 0; $k -lt $qrs.Count; $k++) {
    if ($k -eq $qrs.Count - 1) { Expect-Fail rider POST "/api/rider/deliveries/$d/start" 'HANDOVER_NOT_COMPLETED' }
    $s = Call rider POST "/api/rider/deliveries/$d/handover-scans" @{ qrCode = $qrs[$k] }
    Log "QR 스캔 $($s.packageId) → $($s.result) ($($s.handedOver)/$($s.total)) 배송상태 $($s.deliveryStatus)"
}
Call rider POST "/api/rider/deliveries/$d/start" | Out-Null
Log '라이더 배송 출발 (IN_TRANSIT)'

# 5. 재생 시작 → 고객 추적 API 로 위치 갱신 관측
$st = Call admin POST "/test-api/simulations/$sid/start"
Log "재생 시작 $($st.state) ${Rate}×"
Expect-Fail admin POST "/test-api/simulations/$sid/start" 'ALREADY_RUNNING'
$seen = [System.Collections.Generic.HashSet[string]]::new()
$t0 = Get-Date
$paused = $false
while ($true) {
    Start-Sleep -Milliseconds 900
    $st = Call admin GET "/test-api/simulations/$sid"
    $tr = (Call cust GET "/api/customer/orders/$($order.orderId)/tracking").deliveries[0]
    if ($tr.rider) { [void]$seen.Add("$($tr.rider.latitude),$($tr.rider.longitude)") }
    if (-not $paused -and $st.progressFraction -gt 0.4) {
        $p = Call admin POST "/test-api/simulations/$sid/pause"; $sent = $p.counters.sent
        Start-Sleep -Seconds 3
        $after = (Call admin GET "/test-api/simulations/$sid").counters.sent
        Log "일시정지 3초: 전송 수 $sent → $after (변화 없어야 함)"
        if ($after -ne $sent) { throw '일시정지 중 위치가 전송됨' }
        Call admin POST "/test-api/simulations/$sid/resume" | Out-Null
        Log '재개'
        $paused = $true
    }
    if ($st.state -ne 'RUNNING') { break }
    if (((Get-Date) - $t0).TotalSeconds -gt 900) { throw '재생이 15분 내 끝나지 않음' }
}
Log "재생 종료 상태=$($st.state) 진행 $([math]::Round($st.progressedMeters))/$([math]::Round($st.totalMeters))m 가상경과 $([math]::Round($st.virtualElapsedMs/1000))s 실경과 $([math]::Round(((Get-Date)-$t0).TotalSeconds))s"
Log "전송 $($st.counters.sent) / 최신반영 $($st.counters.acceptedLatest) / 고객 API 에서 관측한 서로 다른 위치 $($seen.Count)개"
$tr = (Call cust GET "/api/customer/orders/$($order.orderId)/tracking").deliveries[0]
Log "고객 조회: 상태 $($tr.status) simulated=$($tr.simulated) 출처 $($tr.rider.source) ETA=$($tr.eta.status) $($tr.eta.message)"
if ($tr.status -ne 'IN_TRANSIT') { throw "경로 끝에서 상태가 바뀜(자동완료 의심): $($tr.status)" }
Log '경로 끝 도달 후에도 배송 상태 IN_TRANSIT 유지 (자동 완료 없음) 확인'

# 6. 라이더 고객 도착 → 사진 증빙 완료
Call rider POST "/api/rider/deliveries/$d/arrive-destination" | Out-Null
$jpg = Join-Path $env:TEMP "proof-$stamp.jpg"
[IO.File]::WriteAllBytes($jpg, [byte[]](0xFF, 0xD8, 0xFF, 0xE0) + [byte[]]::new(60) + [byte[]](0xFF, 0xD9))
$form = @{ requestId = "e2e-$stamp"; method = 'NON_FACE_TO_FACE'; photo = Get-Item $jpg }
$done = Invoke-RestMethod -Method Post "$Base/api/rider/deliveries/$d/complete" -Headers @{ Authorization = "Bearer $($tok.rider)" } -Form $form
$dup = Invoke-RestMethod -Method Post "$Base/api/rider/deliveries/$d/complete" -Headers @{ Authorization = "Bearer $($tok.rider)" } -Form $form
Remove-Item $jpg
Log "전달 완료 $($done.result) proof=$($done.proofId) / 같은 요청 재전송 → $($dup.result)"

# 7. Outbox → Mock OMS 주문 완료
$os = $null
for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Seconds 1; $os = Call cust GET "/test-api/mock-oms/orders/$($order.orderId)"; if ($os.status -eq 'COMPLETED') { break } }
Log "Mock OMS 주문 상태: $($os.status) (DeliveryCompleted outbox 전달)"
$after = (Call cust GET "/api/customer/orders/$($order.orderId)/tracking").deliveries[0]
Log "완료 후 고객 위치 공개=$($after.riderLocationVisible) rider=$($after.rider)"

$ok = ($os.status -eq 'COMPLETED' -and $done.result -eq 'COMPLETED' -and $dup.result -eq 'ALREADY_COMPLETED' -and -not $after.riderLocationVisible)
$lines.Add('')
$lines.Add("## 판정: $(if ($ok) { '모의 E2E 통과 (SIMULATED/' + $RouteSource + ', Mock OMS/MFC)' } else { '실패' })")
$lines.Add("- 시뮬레이터 증거 로그: reports/runs/simulations/$sid.jsonl")
$lines | Out-File $report -Encoding utf8
Write-Host "보고서: $report"
if (-not $ok) { exit 1 }
