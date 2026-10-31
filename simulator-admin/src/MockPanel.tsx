import { useCallback, useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { api, ApiError, Fixture, MfcSite, MockPackage, OrderView } from './api';

function Qr({ text }: { text: string }) {
  const [src, setSrc] = useState('');
  useEffect(() => {
    QRCode.toDataURL(text, { margin: 1, width: 180 }).then(setSrc).catch(() => setSrc(''));
  }, [text]);
  return src ? <img src={src} alt={text} width={180} height={180} /> : <code>{text}</code>;
}

/** [Mock] OMS/MFC 조작: 테스트 주문·포장 완료·QR 표시·취소·장애 주입. 결과는 Mock 이며 실제 OMS/MFC 연동이 아니다. */
export function MockPanel() {
  const [mfcs, setMfcs] = useState<MfcSite[]>([]);
  const [fixtures, setFixtures] = useState<Fixture[]>([]);
  const [orders, setOrders] = useState<OrderView[]>([]);
  const [packages, setPackages] = useState<Record<string, MockPackage[]>>({});
  const [form, setForm] = useState({
    customerId: 'C-TEST-01', mfcId: 'MFC-TEST-REMOTE-01', lat: 35.1621, lng: 129.1602,
    address: '부산 해운대구 중동 (테스트 가상 주소 T1)', weight: 3000, packageCount: 2,
  });
  const [near, setNear] = useState({ lat: '', lng: '' });
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [usage, setUsage] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      const [m, o, f, u] = await Promise.all([
        api<MfcSite[]>('GET', '/test-api/mfcs'),
        api<OrderView[]>('GET', '/test-api/mock-oms/orders'),
        api<Fixture[]>('GET', '/test-api/simulations/fixtures').catch(() => [] as Fixture[]),
        api<unknown>('GET', '/test-api/admin/kakao-usage'),
      ]);
      setMfcs(m);
      setOrders(o);
      setFixtures(f);
      setUsage(u);
    } catch (e) {
      setMsg((e as Error).message);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const act = async (label: string, fn: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    setMsg(null);
    try {
      const r = await fn();
      setMsg(`${label} 완료: ${JSON.stringify(r).slice(0, 300)}`);
      await load();
    } catch (e) {
      setMsg(`${label} 실패 — ${e instanceof ApiError ? `${e.code}: ${e.message}` : (e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const loadPackages = async (deliveryId: string) => {
    try {
      const p = await api<MockPackage[]>('GET', `/test-api/mock-mfc/deliveries/${deliveryId}/packages`);
      setPackages((x) => ({ ...x, [deliveryId]: p }));
    } catch (e) {
      setMsg((e as Error).message);
    }
  };

  const applyFixtureDest = (f: Fixture) =>
    setForm({ ...form, mfcId: f.mfcId, lat: f.destLat, lng: f.destLng, address: f.destinationAddress });

  return (
    <div className="grid">
      <section className="card">
        <h2>[Mock OMS] 테스트 주문</h2>
        <label>고객 ID <input value={form.customerId} onChange={(e) => setForm({ ...form, customerId: e.target.value })} /></label>
        <label>MFC
          <select value={form.mfcId} onChange={(e) => setForm({ ...form, mfcId: e.target.value })}>
            {mfcs.map((m) => <option key={m.id} value={m.id}>{m.id} · {m.locationMode} · {m.name}</option>)}
          </select>
        </label>
        <div className="row">
          {fixtures.map((f) => <button key={f.id} onClick={() => applyFixtureDest(f)}>배송지 = {f.destinationLabel}</button>)}
        </div>
        <label>주소 <input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} /></label>
        <div className="row">
          <label className="inline">위도 <input type="number" step="0.000001" value={form.lat} onChange={(e) => setForm({ ...form, lat: Number(e.target.value) })} /></label>
          <label className="inline">경도 <input type="number" step="0.000001" value={form.lng} onChange={(e) => setForm({ ...form, lng: Number(e.target.value) })} /></label>
        </div>
        <div className="row">
          <label className="inline">총중량(g) <input type="number" value={form.weight} onChange={(e) => setForm({ ...form, weight: Number(e.target.value) })} /></label>
          <label className="inline">포장 수 <input type="number" min={1} max={10} value={form.packageCount} onChange={(e) => setForm({ ...form, packageCount: Number(e.target.value) })} /></label>
        </div>
        <p className="muted">10,000g 초과 시에만 분할(정확히 10,000g 은 미분할).</p>
        <button disabled={busy} onClick={() => act('주문 생성', () => api('POST', '/test-api/mock-oms/orders', {
          customerId: form.customerId,
          order: {
            mfcId: form.mfcId, latitude: form.lat, longitude: form.lng, address: form.address, addressDetail: '',
            totalWeightGrams: form.weight, packageCount: form.packageCount, autoSeal: false,
          },
        }))}>테스트 주문 생성</button>

        <h2>장애 주입 (Mock)</h2>
        <div className="buttons">
          <button disabled={busy} onClick={() => act('MFC 인계 응답 지연', () => api('PUT', '/test-api/mock-mfc/fault', { fault: 'TIMEOUT_AFTER_COMMIT_NEXT' }))}>다음 인계: 확정 후 응답 지연</button>
          <button disabled={busy} onClick={() => act('MFC 인계 보류', () => api('PUT', '/test-api/mock-mfc/fault', { fault: 'REJECT_NEXT' }))}>다음 인계: 거절</button>
          <button disabled={busy} onClick={() => act('OMS 수신 장애', () => api('PUT', '/test-api/mock-oms/event-fault', { fault: 'FAIL_BEFORE_APPLY', times: 1 }))}>다음 이벤트: OMS 장애</button>
          <button disabled={busy} onClick={() => act('OMS 응답 유실', () => api('PUT', '/test-api/mock-oms/event-fault', { fault: 'FAIL_AFTER_APPLY', times: 1 }))}>다음 이벤트: 적용 후 응답 유실</button>
        </div>

        <h2>근거리 실GPS 거점 (MFC-TEST-01)</h2>
        <p className="muted">실제 테스트 장소 근처 좌표로 재설정 (원거리 가상 거점과 별도 ID). 서버 재기동 시 설정값으로 돌아갑니다.</p>
        <div className="row">
          <input placeholder="위도" value={near.lat} onChange={(e) => setNear({ ...near, lat: e.target.value })} />
          <input placeholder="경도" value={near.lng} onChange={(e) => setNear({ ...near, lng: e.target.value })} />
          <button disabled={busy || !near.lat || !near.lng} onClick={() => act('MFC-TEST-01 재설정', () => api('PUT', '/test-api/admin/mfcs/MFC-TEST-01', { latitude: Number(near.lat), longitude: Number(near.lng) }))}>저장</button>
        </div>
        <h3>카카오 API 사용량 (서버 집계, 재기동 시 초기화)</h3>
        <pre className="pre">{JSON.stringify(usage, null, 1)}</pre>
        {msg && <div className="msg">{msg}</div>}
      </section>

      <section className="card wide">
        <h2>[Mock] 주문 · 배송 · 포장</h2>
        <button onClick={() => void load()}>새로고침</button>
        {orders.map((o) => (
          <div key={o.orderId} className="order">
            <div className="row">
              <b>{o.orderId}</b> <span className="badge">{o.status}</span> {o.customerId} · {o.mfcId} · {o.totalWeightGrams}g {o.split && <span className="badge warn">분할</span>}
              <button disabled={busy} onClick={() => act('주문 취소', () => api('POST', `/test-api/mock-oms/orders/${o.orderId}/cancel`))}>주문 취소</button>
            </div>
            {o.deliveries.map((d) => (
              <div key={d.deliveryId} className="delivery">
                <div className="row">
                  {d.deliveryId} · 포장 {d.packageCount} · OMS 배송상태 {d.status}
                  <button disabled={busy} onClick={() => act('포장 완료(SEALED)', () => api('POST', `/test-api/mock-mfc/deliveries/${d.deliveryId}/seal`))}>포장 완료</button>
                  <button onClick={() => void loadPackages(d.deliveryId)}>QR 보기</button>
                  <button disabled={busy} onClick={() => act('배송요청 재전송', () => api('POST', `/test-api/mock-oms/deliveries/${d.deliveryId}/resend-request`))}>요청 재전송(멱등)</button>
                </div>
                <div className="qrs">
                  {(packages[d.deliveryId] ?? []).map((p) => (
                    <figure key={p.packageId}>
                      <Qr text={p.qrCode} />
                      <figcaption>{p.packageId} · {p.status}{p.handedOverRider ? ` → ${p.handedOverRider}` : ''}</figcaption>
                    </figure>
                  ))}
                </div>
              </div>
            ))}
          </div>
        ))}
      </section>
    </div>
  );
}
