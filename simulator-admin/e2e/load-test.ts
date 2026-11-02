/**
 * 부하 테스트 (local-test 서버 전용, 실기기·카카오 불필요).
 * N명 라이더·N명 고객 동시: 로그인 → 운행·대기 위치 → 주문 N건 → 배차 경합(전원 수락 시도) → 인계 → 배송 중 위치 1초 간격 전송
 * + 고객 추적 2초 폴링. 엔드포인트별 응답 시간(p50/p95/p99/max)·오류·최종 위치 일치(역행 없음)를 보고한다.
 *
 * 실행: API_URL=http://127.0.0.1:18082 DELIVERY_TEST_PASSCODE=... RIDERS=30 DURATION_S=60 npx tsx e2e/load-test.ts
 * 서버는 test-accounts 에 R-LOAD-001.., C-LOAD-001.. 계정이 있어야 한다 (scripts/load-test.ps1 이 준비).
 */
const API = process.env.API_URL ?? 'http://127.0.0.1:18082';
const PASS = process.env.DELIVERY_TEST_PASSCODE ?? '';
const N = Number(process.env.RIDERS ?? 30);
const DURATION_S = Number(process.env.DURATION_S ?? 60);
const MFC = { id: 'MFC-TEST-01', lat: 35.1796, lng: 129.0756 };

type Json = Record<string, any>;
const samples = new Map<string, number[]>();
const errors = new Map<string, number>();
const expected = new Map<string, number>(); // 기대한 거절(경합 409 등)

function key(method: string, path: string) {
  return method + ' ' + path.replace(/DLV-[A-Z0-9]+/g, '{d}').replace(/OFR-[A-Z0-9]+/g, '{o}').replace(/ORD-[A-Z0-9]+/g, '{order}');
}

async function call(token: string | null, method: string, path: string, body?: unknown, okCodes: number[] = []): Promise<{ status: number; data: Json }> {
  const k = key(method, path);
  const t0 = performance.now();
  let status = 0;
  let data: Json = {};
  try {
    const res = await fetch(API + path, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    status = res.status;
    const text = await res.text();
    data = text ? JSON.parse(text) : {};
  } catch (e) {
    status = -1;
    data = { code: 'NETWORK', message: String(e) };
  }
  const ms = performance.now() - t0;
  (samples.get(k) ?? samples.set(k, []).get(k)!).push(ms);
  if (status >= 400 || status < 0) {
    const bucket = okCodes.includes(status) ? expected : errors;
    const ek = `${k} → ${status} ${data.code ?? ''}`;
    bucket.set(ek, (bucket.get(ek) ?? 0) + 1);
  }
  return { status, data };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const pad = (i: number) => String(i).padStart(3, '0');
const login = async (u: string) => (await call(null, 'POST', '/api/auth/test-login', { userId: u, passcode: PASS })).data.accessToken as string;
const pct = (a: number[], p: number) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))]; };
const jitter = (m: number) => (Math.random() - 0.5) * 2 * (m / 111_000);

async function main() {
  if (!PASS) throw new Error('DELIVERY_TEST_PASSCODE 필요');
  const tStart = performance.now();
  const admin = await login('ADMIN-TEST');
  const riders = await Promise.all(Array.from({ length: N }, async (_, i) => ({ id: `R-LOAD-${pad(i + 1)}`, token: await login(`R-LOAD-${pad(i + 1)}`) })));
  const customers = await Promise.all(Array.from({ length: N }, async (_, i) => ({ id: `C-LOAD-${pad(i + 1)}`, token: await login(`C-LOAD-${pad(i + 1)}`) })));
  if (riders.some((r) => !r.token) || customers.some((c) => !c.token)) throw new Error('부하 계정 로그인 실패 (서버 test-accounts 확인)');
  console.log(`로그인 ${N * 2 + 1}건 완료`);

  // 운행 시작 + 대기 위치(MFC 반경 500m 안)
  await Promise.all(riders.map(async (r) => {
    await call(r.token, 'POST', '/api/rider/me/duty', { dutyStatus: 'ON_DUTY_ACCEPTING' });
    await call(r.token, 'POST', '/api/rider/me/location', { latitude: MFC.lat + jitter(500), longitude: MFC.lng + jitter(500), accuracyMeters: 8, measuredAt: new Date().toISOString() });
  }));

  // 주문 N건 동시 (배송지: MFC 에서 약 1~1.5km)
  const orders = await Promise.all(customers.map(async (c, i) => {
    const angle = (i / N) * 2 * Math.PI;
    const dest = { lat: MFC.lat + Math.sin(angle) * 0.011, lng: MFC.lng + Math.cos(angle) * 0.013 };
    const r = await call(c.token, 'POST', '/test-api/mock-oms/orders', { order: { mfcId: MFC.id, latitude: dest.lat, longitude: dest.lng, address: `부하 테스트 배송지 ${i + 1}`, addressDetail: '1호', totalWeightGrams: 2000, packageCount: 1, autoSeal: false } });
    return { customer: c, orderId: r.data.orderId as string, deliveryId: r.data.deliveries?.[0]?.deliveryId as string, dest };
  }));
  const deliveryIds = new Set(orders.map((o) => o.deliveryId));
  console.log(`주문 ${orders.filter((o) => o.deliveryId).length}/${N}건 생성`);

  // 배차 경합: 각 라이더가 받은 제안을 수락 시도, 1건 배정되면 중단
  const assignedBy = new Map<string, string>(); // deliveryId → riderId
  const riderDelivery = new Map<string, string>();
  const deadline = performance.now() + 60_000;
  await Promise.all(riders.map(async (r) => {
    while (!riderDelivery.has(r.id) && performance.now() < deadline && assignedBy.size < deliveryIds.size) {
      const offers = (await call(r.token, 'GET', '/api/rider/offers')).data as unknown as Json[];
      for (const o of Array.isArray(offers) ? offers : []) {
        if (!deliveryIds.has(o.deliveryId) || assignedBy.has(o.deliveryId)) continue;
        const a = await call(r.token, 'POST', `/api/rider/offers/${o.offerId}/accept`, undefined, [409]);
        if (a.status === 200 && a.data.result === 'ASSIGNED') {
          if (assignedBy.has(o.deliveryId)) throw new Error(`이중 배정 발견: ${o.deliveryId}`);
          assignedBy.set(o.deliveryId, r.id);
          riderDelivery.set(r.id, o.deliveryId);
          break;
        }
      }
      if (!riderDelivery.has(r.id)) await sleep(500);
    }
  }));
  console.log(`배정 ${assignedBy.size}/${deliveryIds.size}건 (경합 거절은 정상 동작)`);

  // 인계·출발
  await Promise.all([...riderDelivery.entries()].map(async ([riderId, d]) => {
    const r = riders.find((x) => x.id === riderId)!;
    await call(r.token, 'POST', `/api/rider/deliveries/${d}/arrive-mfc`);
    await call(admin, 'POST', `/test-api/mock-mfc/deliveries/${d}/seal`);
    const pk = (await call(admin, 'GET', `/test-api/mock-mfc/deliveries/${d}/packages`)).data as unknown as Json[];
    for (const p of pk) await call(r.token, 'POST', `/api/rider/deliveries/${d}/handover-scans`, { qrCode: p.qrCode });
    await call(r.token, 'POST', `/api/rider/deliveries/${d}/start`);
  }));
  console.log(`인계·출발 완료, 위치 전송 ${DURATION_S}초 시작 (라이더 ${riderDelivery.size}명 × 1초, 고객 추적 2초 폴링)`);

  // 위치 1초 간격 전송 + 고객 추적 폴링
  const lastSent = new Map<string, { lat: number; lng: number }>();
  const phaseEnd = performance.now() + DURATION_S * 1000;
  const riderLoops = [...riderDelivery.entries()].map(async ([riderId, d]) => {
    const r = riders.find((x) => x.id === riderId)!;
    const o = orders.find((x) => x.deliveryId === d)!;
    let seq = 1;
    while (performance.now() < phaseEnd) {
      const f = Math.min(1, seq / (DURATION_S + 5));
      const p = { lat: MFC.lat + (o.dest.lat - MFC.lat) * f, lng: MFC.lng + (o.dest.lng - MFC.lng) * f };
      const res = await call(r.token, 'POST', `/api/rider/deliveries/${d}/locations`, { points: [{ seq, latitude: p.lat, longitude: p.lng, accuracyMeters: 6, measuredAt: new Date().toISOString() }] });
      if (res.status === 200 && res.data.points?.[0]?.verdict === 'ACCEPTED_LATEST') lastSent.set(d, p);
      seq++;
      await sleep(1000);
    }
  });
  const customerLoops = orders.filter((o) => assignedBy.has(o.deliveryId)).map(async (o) => {
    while (performance.now() < phaseEnd) {
      await call(o.customer.token, 'GET', `/api/customer/orders/${o.orderId}/tracking`);
      await sleep(2000);
    }
  });
  await Promise.all([...riderLoops, ...customerLoops]);

  // 최종 위치 일치(역행 없음) 확인
  let match = 0, mismatch = 0;
  for (const o of orders) {
    const want = lastSent.get(o.deliveryId);
    if (!want) continue;
    const tr = (await call(o.customer.token, 'GET', `/api/customer/orders/${o.orderId}/tracking`)).data;
    const got = tr.deliveries?.[0]?.rider;
    if (got && Math.abs(got.latitude - want.lat) < 1e-7 && Math.abs(got.longitude - want.lng) < 1e-7) match++; else mismatch++;
  }

  // 보고
  const totalReq = [...samples.values()].reduce((s, a) => s + a.length, 0);
  const elapsed = (performance.now() - tStart) / 1000;
  console.log(`\n== 결과: 요청 ${totalReq}건 / ${elapsed.toFixed(0)}초, 최종 위치 일치 ${match}건·불일치 ${mismatch}건, 이중 배정 0건`);
  console.log('엔드포인트'.padEnd(58) + '건수'.padStart(7) + '  p50    p95    p99    max (ms)');
  for (const [k, a] of [...samples.entries()].sort((x, y) => y[1].length - x[1].length)) {
    console.log(k.padEnd(58) + String(a.length).padStart(7) + `  ${pct(a, 50).toFixed(0).padStart(5)}  ${pct(a, 95).toFixed(0).padStart(5)}  ${pct(a, 99).toFixed(0).padStart(5)}  ${Math.max(...a).toFixed(0).padStart(5)}`);
  }
  console.log(`\n예상된 거절(배차 경합 등): ${[...expected.entries()].map(([k, v]) => `${k} ×${v}`).join(', ') || '없음'}`);
  console.log(`오류: ${[...errors.entries()].map(([k, v]) => `${k} ×${v}`).join(', ') || '없음'}`);
  if (errors.size > 0 || mismatch > 0) process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
