import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  api,
  ApiError,
  DeliveryDetail,
  DeliveryRow,
  FaultSpec,
  FaultType,
  Fixture,
  RoutePreview,
  SimState,
  SimStatus,
} from './api';
import { RouteMap } from './RouteMap';

const FAULT_TYPES: FaultType[] = ['STOP', 'DROP', 'DELAY', 'DUPLICATE', 'REORDER', 'ANOMALY', 'DISCONNECT'];
const NEEDS_TICKS: FaultType[] = ['DROP', 'DELAY', 'DISCONNECT'];

const can = (s: SimState | undefined, ...allowed: SimState[]) => !!s && allowed.includes(s);

interface HistoryItem {
  at: string;
  action: string;
  ok: boolean;
  detail: string;
}

export function SimulatorPanel() {
  const [deliveries, setDeliveries] = useState<DeliveryRow[]>([]);
  const [fixtures, setFixtures] = useState<Fixture[]>([]);
  const [scenarios, setScenarios] = useState<SimStatus[]>([]);
  const [deliveryId, setDeliveryId] = useState('');
  const [riderId, setRiderId] = useState('');
  const [routeSource, setRouteSource] = useState<'FIXTURE' | 'KAKAO_DIRECTIONS'>('FIXTURE');
  const [fixtureId, setFixtureId] = useState('');
  const [rate, setRate] = useState(1);
  const [seed, setSeed] = useState(42);
  const [faults, setFaults] = useState<FaultSpec[]>([]);
  const [newFault, setNewFault] = useState<FaultSpec>({ type: 'STOP', atFraction: 0.3, durationSeconds: 30, durationTicks: 3 });
  const [liveFault, setLiveFault] = useState<{ type: FaultType; ticks: number; seconds: number }>({ type: 'DROP', ticks: 3, seconds: 20 });

  const [scenarioId, setScenarioId] = useState<string | null>(null);
  const [status, setStatus] = useState<SimStatus | null>(null);
  const [route, setRoute] = useState<RoutePreview | null>(null);
  const [detail, setDetail] = useState<DeliveryDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const pollSeq = useRef(0);
  const appliedSeq = useRef(0);

  const refreshLists = useCallback(async () => {
    try {
      const [ds, fx, sc] = await Promise.all([
        api<DeliveryRow[]>('GET', '/test-api/admin/deliveries'),
        api<Fixture[]>('GET', '/test-api/simulations/fixtures'),
        api<SimStatus[]>('GET', '/test-api/simulations'),
      ]);
      setDeliveries(ds.filter((d) => d.locationMode === 'SIMULATED_ROUTE'));
      setFixtures(fx);
      setScenarios(sc);
      if (!fixtureId && fx.length > 0) setFixtureId(fx[0].id);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [fixtureId]);

  useEffect(() => {
    void refreshLists();
  }, [refreshLists]);

  const selectedDelivery = useMemo(() => deliveries.find((d) => d.deliveryId === deliveryId) ?? null, [deliveries, deliveryId]);

  useEffect(() => {
    if (selectedDelivery?.riderId) setRiderId(selectedDelivery.riderId);
  }, [selectedDelivery]);

  // 1초 폴링 — 늦게 도착한 이전 응답은 버린다(pollSeq/appliedSeq)
  useEffect(() => {
    if (!scenarioId) return;
    let stop = false;
    const tick = async () => {
      const my = ++pollSeq.current;
      try {
        const s = await api<SimStatus>('GET', `/test-api/simulations/${scenarioId}`);
        const d = await api<DeliveryDetail>('GET', `/test-api/admin/deliveries/${s.deliveryId}`);
        if (stop || my < appliedSeq.current) return;
        appliedSeq.current = my;
        setStatus(s);
        setDetail(d);
      } catch (e) {
        if (!stop) setError(`상태 조회 실패: ${(e as Error).message}`);
      }
    };
    void tick();
    const h = window.setInterval(tick, 1000);
    return () => {
      stop = true;
      window.clearInterval(h);
    };
  }, [scenarioId]);

  const run = async (action: string, fn: () => Promise<SimStatus | void>) => {
    if (busy) return; // 중복 클릭 방지
    setBusy(true);
    setError(null);
    appliedSeq.current = ++pollSeq.current; // 명령 결과가 이전 폴링 응답보다 우선
    try {
      const r = await fn();
      if (r) setStatus(r);
      setHistory((h) => [{ at: new Date().toLocaleTimeString(), action, ok: true, detail: r ? r.state : 'OK' }, ...h].slice(0, 50));
    } catch (e) {
      const msg = e instanceof ApiError ? `${e.code}: ${e.message}` : (e as Error).message;
      setError(`${action} 실패 — ${msg}`);
      setHistory((h) => [{ at: new Date().toLocaleTimeString(), action, ok: false, detail: msg }, ...h].slice(0, 50));
    } finally {
      setBusy(false);
    }
  };

  const create = () =>
    run('생성', async () => {
      const s = await api<SimStatus>('POST', '/test-api/simulations', {
        deliveryId,
        riderId,
        routeSource,
        fixtureId: routeSource === 'FIXTURE' ? fixtureId : null,
        rate,
        seed,
        faults,
      });
      setScenarioId(s.scenarioId);
      setRoute(null);
      void refreshLists();
      return s;
    });

  const cmd = (label: string, path: string) =>
    run(label, async () => {
      const s = await api<SimStatus>('POST', `/test-api/simulations/${scenarioId}/${path}`);
      if (path === 'prepare') setRoute(await api<RoutePreview>('GET', `/test-api/simulations/${scenarioId}/route`));
      return s;
    });

  const changeRate = (r: number) => {
    setRate(r);
    if (scenarioId && can(status?.state, 'CREATED', 'READY', 'RUNNING', 'PAUSED')) {
      void run(`배속 ${r}×`, () => api<SimStatus>('PUT', `/test-api/simulations/${scenarioId}/rate`, { rate: r }));
    }
  };

  const inject = () =>
    run(`장애 주입 ${liveFault.type}`, () =>
      api<SimStatus>('POST', `/test-api/simulations/${scenarioId}/faults`, {
        type: liveFault.type,
        durationTicks: NEEDS_TICKS.includes(liveFault.type) ? liveFault.ticks : null,
        durationSeconds: liveFault.type === 'STOP' ? liveFault.seconds : null,
      }),
    );

  const remove = () =>
    run('삭제', async () => {
      await api('DELETE', `/test-api/simulations/${scenarioId}`);
      setScenarioId(null);
      setStatus(null);
      setRoute(null);
      setDetail(null);
      void refreshLists();
    });

  const pick = async (id: string) => {
    setScenarioId(id);
    setRoute(null);
    try {
      setRoute(await api<RoutePreview>('GET', `/test-api/simulations/${id}/route`));
    } catch {
      /* 아직 경로 미로드 */
    }
  };

  const st = status?.state;
  const terminal = can(st, 'FINISHED', 'STOPPED', 'FAILED');
  const mfc = detail ? ([detail.rider.mfc.latitude, detail.rider.mfc.longitude] as [number, number]) : null;
  const dest = detail ? ([detail.customer.destination.latitude, detail.customer.destination.longitude] as [number, number]) : null;
  const seen = detail?.customer.rider ? ([detail.customer.rider.latitude, detail.customer.rider.longitude] as [number, number]) : null;
  const eta = detail?.customer.eta;

  return (
    <div className="grid">
      <section className="card">
        <h2>시나리오 입력</h2>
        <label>
          테스트 배송 (SIMULATED_ROUTE)
          <select value={deliveryId} onChange={(e) => setDeliveryId(e.target.value)}>
            <option value="">— 선택 —</option>
            {deliveries.map((d) => (
              <option key={d.deliveryId} value={d.deliveryId}>
                {d.deliveryId} · {d.orderId} · {d.status} · {d.riderId ?? '미배정'}
              </option>
            ))}
          </select>
        </label>
        {selectedDelivery && (
          <p className="muted">
            MFC {selectedDelivery.mfcId} (가상 거점) → 고객 {selectedDelivery.customerId} 배송지 스냅샷
          </p>
        )}
        <label>
          라이더 ID
          <input value={riderId} onChange={(e) => setRiderId(e.target.value)} placeholder="배정 후 자동 입력" />
        </label>
        <label>
          경로 출처
          <select value={routeSource} onChange={(e) => setRouteSource(e.target.value as 'FIXTURE' | 'KAKAO_DIRECTIONS')}>
            <option value="FIXTURE">FIXTURE (자체 작성, 키 불필요)</option>
            <option value="KAKAO_DIRECTIONS">KAKAO_DIRECTIONS (서버 실호출)</option>
          </select>
        </label>
        {routeSource === 'FIXTURE' && (
          <label>
            FIXTURE
            <select value={fixtureId} onChange={(e) => setFixtureId(e.target.value)}>
              {fixtures.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.id} — {f.destinationLabel}
                </option>
              ))}
            </select>
          </label>
        )}
        <div className="row">
          <span>배속</span>
          {[1, 5, 10].map((r) => (
            <button key={r} className={(status?.rate ?? rate) === r ? 'on' : ''} onClick={() => changeRate(r)} disabled={busy}>
              {r}×
            </button>
          ))}
          <label className="inline">
            seed <input type="number" value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
          </label>
        </div>
        <details>
          <summary>계획 장애 시나리오 ({faults.length})</summary>
          <div className="row">
            <select value={newFault.type} onChange={(e) => setNewFault({ ...newFault, type: e.target.value as FaultType })}>
              {FAULT_TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <label className="inline">
              진행률 <input type="number" step="0.05" min={0} max={1} value={newFault.atFraction}
                onChange={(e) => setNewFault({ ...newFault, atFraction: Number(e.target.value) })} />
            </label>
            {NEEDS_TICKS.includes(newFault.type) && (
              <label className="inline">
                틱 <input type="number" min={1} value={newFault.durationTicks ?? 3}
                  onChange={(e) => setNewFault({ ...newFault, durationTicks: Number(e.target.value) })} />
              </label>
            )}
            {newFault.type === 'STOP' && (
              <label className="inline">
                초 <input type="number" min={1} value={newFault.durationSeconds ?? 30}
                  onChange={(e) => setNewFault({ ...newFault, durationSeconds: Number(e.target.value) })} />
              </label>
            )}
            <button onClick={() => setFaults([...faults, {
              type: newFault.type,
              atFraction: newFault.atFraction,
              durationTicks: NEEDS_TICKS.includes(newFault.type) ? newFault.durationTicks : null,
              durationSeconds: newFault.type === 'STOP' ? newFault.durationSeconds : null,
            }])}>추가</button>
          </div>
          <ul>
            {faults.map((f, i) => (
              <li key={i}>
                {f.type} @ {(f.atFraction * 100).toFixed(0)}% {f.durationTicks ? `${f.durationTicks}틱` : ''} {f.durationSeconds ? `${f.durationSeconds}초` : ''}
                <button className="link" onClick={() => setFaults(faults.filter((_, j) => j !== i))}>삭제</button>
              </li>
            ))}
          </ul>
        </details>

        <h3>조작</h3>
        <div className="buttons">
          <button onClick={create} disabled={busy || !deliveryId || !riderId || (!!status && !terminal)}>생성</button>
          <button onClick={() => cmd('검증/미리보기', 'prepare')} disabled={busy || !can(st, 'CREATED', 'READY')}>검증·미리보기</button>
          <button onClick={() => cmd('시작', 'start')} disabled={busy || !can(st, 'READY')}>시작</button>
          <button onClick={() => cmd('일시정지', 'pause')} disabled={busy || !can(st, 'RUNNING')}>일시정지</button>
          <button onClick={() => cmd('재개', 'resume')} disabled={busy || !can(st, 'PAUSED')}>재개</button>
          <button onClick={() => cmd('종료', 'stop')} disabled={busy || !can(st, 'CREATED', 'READY', 'RUNNING', 'PAUSED')}>종료</button>
          <button onClick={() => cmd('초기화', 'reset')} disabled={busy || !can(st, 'READY', 'FINISHED', 'STOPPED', 'FAILED')}>초기화</button>
          <button onClick={remove} disabled={busy || !scenarioId || can(st, 'RUNNING')}>삭제</button>
        </div>
        <div className="row">
          <select value={liveFault.type} onChange={(e) => setLiveFault({ ...liveFault, type: e.target.value as FaultType })}>
            {FAULT_TYPES.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
          {NEEDS_TICKS.includes(liveFault.type) && (
            <label className="inline">틱 <input type="number" min={1} value={liveFault.ticks} onChange={(e) => setLiveFault({ ...liveFault, ticks: Number(e.target.value) })} /></label>
          )}
          {liveFault.type === 'STOP' && (
            <label className="inline">초 <input type="number" min={1} value={liveFault.seconds} onChange={(e) => setLiveFault({ ...liveFault, seconds: Number(e.target.value) })} /></label>
          )}
          <button onClick={inject} disabled={busy || !can(st, 'RUNNING', 'PAUSED')}>지금 장애 주입</button>
        </div>
        {error && <div className="error" role="alert">{error}</div>}

        <h3>시나리오 목록</h3>
        <ul className="scenarios">
          {scenarios.map((s) => (
            <li key={s.scenarioId}>
              <button className={`link ${s.scenarioId === scenarioId ? 'sel' : ''}`} onClick={() => void pick(s.scenarioId)}>
                {s.scenarioId}
              </button>{' '}
              {s.state} · {s.deliveryId}
            </li>
          ))}
          <li><button className="link" onClick={() => void refreshLists()}>목록 새로고침</button></li>
        </ul>
      </section>

      <section className="card wide">
        <div className="badges">
          <span className="badge sim">SIMULATED</span>
          {status && <span className={`badge ${status.routeSource === 'FIXTURE' ? 'fixture' : 'kakao'}`}>{status.routeSource}</span>}
          {status && <span className={`badge state-${status.state}`}>{status.state}</span>}
          {status && !status.connected && <span className="badge warn">연결 끊김(장애 주입)</span>}
          {status?.stopped && <span className="badge warn">정차 중</span>}
        </div>
        <RouteMap points={route?.points ?? []} rider={status?.lastPosition ?? null} mfc={mfc} destination={dest} customerSeen={seen} />
        <p className="legend">
          <span className="lg mfc" /> MFC <span className="lg dest" /> 배송지 <span className="lg rider" /> 시뮬레이터 전송 <span className="lg cust" /> 고객 API 응답 위치
        </p>
        {route && (
          <p className="muted">
            경로 {route.source} · 점 {route.pointCount}개 · {(route.totalMeters / 1000).toFixed(2)}km
            {route.providerDurationSeconds ? ` · 카카오 예상 ${Math.round(route.providerDurationSeconds / 60)}분` : ''} · {route.note}
          </p>
        )}
        {status && (
          <table className="kv">
            <tbody>
              <tr><th>시나리오</th><td>{status.scenarioId} (seed {status.seed})</td><th>배송 상태</th><td>{status.deliveryStatus}</td></tr>
              <tr><th>진행</th><td>{status.progressedMeters.toFixed(0)} / {status.totalMeters.toFixed(0)} m ({(status.progressFraction * 100).toFixed(1)}%)</td>
                <th>가상 경과</th><td>{(status.virtualElapsedMs / 1000).toFixed(0)}s (실시간 아님)</td></tr>
              <tr><th>배속·속도</th><td>{status.rate}× · {status.speedMps.toFixed(1)} m/s</td>
                <th>마지막 전송(실시각)</th><td>{status.lastSentAt ? new Date(status.lastSentAt).toLocaleTimeString() : '—'}</td></tr>
              {status.state === 'FINISHED' && (
                <tr><th>배송지 정차</th><td colSpan={3}>{status.dwelling ? '정차 위치 전송 중' : '정차 위치 전송 종료'} ({status.counters.dwellSent}건) — 고객 도착은 배송지 150m 이내·위치 30초 이내에서만 허용</td></tr>
              )}
              <tr><th>전송/최신반영</th><td>{status.counters.sent} / {status.counters.acceptedLatest}</td>
                <th>역순·중복·거부·지연</th><td>{status.counters.history} · {status.counters.duplicate} · {status.counters.rejected} · {status.counters.stale}</td></tr>
              <tr><th>고객 화면 위치</th><td colSpan={3}>{detail?.customer.rider
                ? `${detail.customer.rider.latitude.toFixed(6)}, ${detail.customer.rider.longitude.toFixed(6)} (${detail.customer.rider.source}, ${detail.customer.rider.ageSeconds}초 전)`
                : detail?.customer.riderLocationVisible === false ? '공개 시점 전/종료' : '—'}</td></tr>
              <tr><th>ETA</th><td colSpan={3}>{eta ? (eta.status === 'OK' ? `${Math.round((eta.etaSeconds ?? 0) / 60)}분 (${eta.provider}, ${eta.computedAt ? new Date(eta.computedAt).toLocaleTimeString() : ''})` : `${eta.message}${eta.reasonCode ? ` [${eta.reasonCode}]` : ''}`) : '—'}</td></tr>
              {status.lastError && <tr><th>오류</th><td colSpan={3} className="error">{status.lastError}</td></tr>}
            </tbody>
          </table>
        )}
        <div className="cols">
          <div>
            <h3>엔진 로그 (최근)</h3>
            <ol className="log">
              {(status?.recentLog ?? []).slice().reverse().map((l, i) => (
                <li key={i}><code>{new Date(l.realAt).toLocaleTimeString()} v{(l.virtualMs / 1000).toFixed(0)}s</code> {l.type} {l.detail}</li>
              ))}
            </ol>
          </div>
          <div>
            <h3>조작 이력</h3>
            <ol className="log">
              {history.map((h, i) => (
                <li key={i} className={h.ok ? '' : 'error'}><code>{h.at}</code> {h.action} → {h.detail}</li>
              ))}
            </ol>
          </div>
        </div>
      </section>
    </div>
  );
}
