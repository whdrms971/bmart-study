/**
 * 관리자 UI 실제 조작 검증 (사용자 조작을 브라우저로 재현).
 * - 설치된 Chrome 을 playwright-core 로 구동해 "버튼 클릭" → 서버 API → 상태 변경을 확인한다.
 * - 배송 준비(주문·배차 수락·인계·출발)는 테스트 API 로 수행하고, 시뮬레이터 조작은 전부 UI 버튼으로만 한다.
 * - 고객 앱이 보는 것과 같은 고객 추적 API 로 위치·상태·ETA 반영을 확인한다.
 * 실행: npx tsx e2e/ui-smoke.ts   (환경변수 DELIVERY_TEST_PASSCODE, 선택 UI_URL/API_URL/HEADED=1)
 */
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const UI = process.env.UI_URL ?? 'http://127.0.0.1:5173';
const API = process.env.API_URL ?? 'http://127.0.0.1:8080';
const PASS = process.env.DELIVERY_TEST_PASSCODE ?? '';
const CHROME = process.env.CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const OUT = join(process.cwd(), '..', 'reports', 'runs');
const SHOTS = join(OUT, 'screens');
const RIDER = process.env.RIDER ?? 'R-TEST-03';
const CUSTOMER = process.env.CUSTOMER ?? 'C-TEST-02';

const log: string[] = [];
const say = (m: string) => {
  const line = `${new Date().toISOString().slice(11, 23)} ${m}`;
  console.log(line);
  log.push(`- \`${line}\``);
};
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

type Json = Record<string, any>;
async function call(token: string | null, method: string, path: string, body?: unknown): Promise<Json> {
  const res = await fetch(API + path, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return data;
}
const login = async (u: string) => (await call(null, 'POST', '/api/auth/test-login', { userId: u, passcode: PASS })).accessToken as string;

function assert(cond: unknown, msg: string): asserts cond {
  if (!cond) throw new Error(`검증 실패: ${msg}`);
}

async function uiState(page: Page): Promise<string> {
  const b = page.locator('.badges .badge[class*="state-"]');
  await b.first().waitFor({ timeout: 10_000 });
  return (await b.first().textContent())?.trim() ?? '';
}

async function waitUiState(page: Page, expected: string) {
  for (let i = 0; i < 40; i++) {
    if ((await uiState(page)) === expected) return;
    await sleep(250);
  }
  throw new Error(`UI 상태가 ${expected} 가 되지 않음 (현재 ${await uiState(page)})`);
}

async function click(page: Page, name: string) {
  const btn = page.getByRole('button', { name, exact: true });
  assert(await btn.isEnabled(), `버튼 '${name}' 이 활성 상태여야 함`);
  await btn.click();
  say(`UI 클릭: ${name}`);
}

async function main() {
  assert(PASS.length >= 8, 'DELIVERY_TEST_PASSCODE 필요');
  mkdirSync(SHOTS, { recursive: true });
  const admin = await login('ADMIN-TEST');
  const cust = await login(CUSTOMER);
  const rider = await login(RIDER);

  // ---- 배송 준비 (실제 Delivery 배차·인계 API)
  const fx = (await call(admin, 'GET', '/test-api/simulations/fixtures'))[0];
  const order = await call(cust, 'POST', '/test-api/mock-oms/orders', { order: { mfcId: fx.mfcId, latitude: fx.destLat, longitude: fx.destLng,
    address: fx.destinationAddress, addressDetail: 'UI 테스트', totalWeightGrams: 2500, packageCount: 1, autoSeal: false } });
  const d: string = order.deliveries[0].deliveryId;
  say(`준비: 주문 ${order.orderId} 배송 ${d} (고객 ${CUSTOMER}, 라이더 ${RIDER})`);
  await call(rider, 'POST', '/api/rider/me/duty', { dutyStatus: 'ON_DUTY_ACCEPTING' });
  let offer: Json | undefined;
  for (let i = 0; i < 30 && !offer; i++) {
    await sleep(700);
    offer = (await call(rider, 'GET', '/api/rider/offers') as unknown as Json[]).find((o) => o.deliveryId === d);
  }
  assert(offer, '배차 제안 수신');
  await call(rider, 'POST', `/api/rider/offers/${offer.offerId}/accept`);
  await call(rider, 'POST', `/api/rider/deliveries/${d}/arrive-mfc`);
  await call(admin, 'POST', `/test-api/mock-mfc/deliveries/${d}/seal`);
  for (const p of await call(admin, 'GET', `/test-api/mock-mfc/deliveries/${d}/packages`) as unknown as Json[]) {
    await call(rider, 'POST', `/api/rider/deliveries/${d}/handover-scans`, { qrCode: p.qrCode });
  }
  await call(rider, 'POST', `/api/rider/deliveries/${d}/start`);
  say('준비 완료: 배차 수락 → 인계 → 배송 출발 (IN_TRANSIT)');

  // ---- 관리자 UI 조작 (실제 Chrome)
  const browser = await chromium.launch({ executablePath: CHROME, headless: process.env.HEADED !== '1' });
  const page = await browser.newPage({ viewport: { width: 1400, height: 950 } });
  await page.goto(UI);
  await page.getByLabel('패스코드').fill(PASS);
  await page.getByRole('button', { name: '로그인' }).click();
  await page.getByRole('button', { name: 'SIMULATED_ROUTE 제어' }).waitFor();
  say('UI 로그인 성공 (ADMIN-TEST)');
  await page.getByRole('button', { name: '목록 새로고침' }).click();
  const select = page.locator('label', { hasText: '테스트 배송' }).locator('select');
  await select.locator(`option[value="${d}"]`).waitFor({ state: 'attached', timeout: 10_000 });
  await select.selectOption(d);
  await page.getByRole('button', { name: '5×', exact: true }).click();

  await click(page, '생성');
  await waitUiState(page, 'CREATED');
  const sims = await call(admin, 'GET', '/test-api/simulations') as unknown as Json[];
  const sim = sims.find((s) => s.deliveryId === d && s.state === 'CREATED');
  assert(sim, '서버에 CREATED 시나리오 생성');
  const id: string = sim.scenarioId;
  const server = async () => call(admin, 'GET', `/test-api/simulations/${id}`);
  say(`서버 확인: 시나리오 ${id} CREATED, 배속 ${sim.rate}×`);
  assert(sim.rate === 5, '선택한 배속 5× 가 서버에 반영');

  await click(page, '검증·미리보기');
  await waitUiState(page, 'READY');
  assert(await page.locator('svg.route-map path.route-line').count() === 1, '경로 미리보기 SVG 표시');
  assert(await page.getByRole('button', { name: '일시정지', exact: true }).isDisabled(), 'READY 에서 일시정지 비활성');
  say(`서버 확인: ${(await server()).state}, UI 경로 미리보기 표시`);
  await page.screenshot({ path: join(SHOTS, 'ui-01-ready.png') });

  await click(page, '시작');
  await waitUiState(page, 'RUNNING');
  assert(await page.getByRole('button', { name: '시작', exact: true }).isDisabled(), 'RUNNING 에서 시작 버튼 비활성(중복 시작 차단)');
  await sleep(4500);
  const track1 = (await call(cust, 'GET', `/api/customer/orders/${order.orderId}/tracking`)).deliveries[0];
  assert(track1.rider && track1.rider.source === 'SIMULATED_ROUTE', '고객 추적 API 에 SIMULATED 위치 반영');
  say(`고객 API: 상태 ${track1.status}, 위치 ${track1.rider.latitude.toFixed(6)},${track1.rider.longitude.toFixed(6)}, ETA ${track1.eta.status} ${track1.eta.etaSeconds ?? ''}`);
  await page.screenshot({ path: join(SHOTS, 'ui-02-running.png') });

  await click(page, '일시정지');
  await waitUiState(page, 'PAUSED');
  const sentPaused = (await server()).counters.sent;
  await sleep(3000);
  const sentAfter = (await server()).counters.sent;
  assert(sentAfter === sentPaused, `일시정지 중 전송 없음 (${sentPaused} → ${sentAfter})`);
  say(`서버 확인: PAUSED, 3초간 전송 ${sentPaused} → ${sentAfter}`);

  await click(page, '재개');
  await waitUiState(page, 'RUNNING');
  await click(page, '10×');
  for (let i = 0; i < 20 && (await server()).rate !== 10; i++) await sleep(200);
  assert((await server()).rate === 10, '배속 10× 서버 반영');
  say('서버 확인: RUNNING, 배속 10×');
  await sleep(4000);
  const track2 = (await call(cust, 'GET', `/api/customer/orders/${order.orderId}/tracking`)).deliveries[0];
  assert(track2.rider.latitude !== track1.rider.latitude || track2.rider.longitude !== track1.rider.longitude, '재개 후 고객 위치 계속 갱신');
  say(`고객 API: 위치 ${track2.rider.latitude.toFixed(6)},${track2.rider.longitude.toFixed(6)} (갱신됨)`);

  await click(page, '종료');
  await waitUiState(page, 'STOPPED');
  const sentStop = (await server()).counters.sent;
  await sleep(2500);
  assert((await server()).counters.sent === sentStop, '종료 후 전송 없음');
  say(`서버 확인: STOPPED, 이후 전송 없음 (${sentStop})`);
  const afterStop = (await call(cust, 'GET', `/api/customer/orders/${order.orderId}/tracking`)).deliveries[0];
  assert(afterStop.status === 'IN_TRANSIT', '재생 종료는 배송 상태를 바꾸지 않음');

  await click(page, '초기화');
  await waitUiState(page, 'READY');
  const reset = await server();
  assert(reset.counters.sent === 0 && reset.progressedMeters === 0, '초기화 후 카운터·진행 0');
  say('서버 확인: 초기화 → READY (진행 0, 전송 0)');

  // 마지막으로 다시 시작해 경로 끝까지 재생 (10×) — 자동완료 없음 확인
  await click(page, '시작');
  await waitUiState(page, 'RUNNING');
  for (let i = 0; i < 120 && (await server()).state === 'RUNNING'; i++) await sleep(1000);
  await waitUiState(page, 'FINISHED');
  const fin = (await call(cust, 'GET', `/api/customer/orders/${order.orderId}/tracking`)).deliveries[0];
  assert(fin.status === 'IN_TRANSIT', '경로 끝 도달 후에도 자동완료 없음');
  say(`서버 확인: FINISHED, 고객 상태 ${fin.status} (자동완료 없음), ETA ${fin.eta.status}`);
  await page.screenshot({ path: join(SHOTS, 'ui-03-finished.png') });

  // 배송은 라이더 앱/API 로 완료되지 않은 채 남겨 둔다 (사용자가 라이더 앱에서 직접 완료해 볼 수 있음)
  await browser.close();
  const report = join(OUT, `ui-smoke-${Date.now()}.md`);
  writeFileSync(report, [`# 관리자 UI 조작 검증 (Chrome, playwright-core)`, '', `- 배송 ${d} / 시나리오 ${id} / 주문 ${order.orderId}`,
    '- 출처: SIMULATED_ROUTE / FIXTURE. Mock OMS·MFC. Android 실기기 아님', '', ...log, '', '## 판정: 통과'].join('\n'), 'utf8');
  console.log(`보고서: ${report}`);
  console.log(`남겨 둔 배송(라이더 앱에서 완료 가능): ${d} / 라이더 ${RIDER} / 고객 ${CUSTOMER}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
