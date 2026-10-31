import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, DeliveryDetail, DeliveryRow } from './api';

/** 실제 Delivery 원장 조회 (상태 이력·배차 제안·위치 판정·outbox). */
export function DeliveriesPanel() {
  const [rows, setRows] = useState<DeliveryRow[]>([]);
  const [sel, setSel] = useState<string | null>(null);
  const [detail, setDetail] = useState<DeliveryDetail | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRows(await api<DeliveryRow[]>('GET', '/test-api/admin/deliveries'));
      if (sel) setDetail(await api<DeliveryDetail>('GET', `/test-api/admin/deliveries/${sel}`));
    } catch (e) {
      setMsg((e as Error).message);
    }
  }, [sel]);

  useEffect(() => {
    void load();
    const h = window.setInterval(load, 3000);
    return () => window.clearInterval(h);
  }, [load]);

  const act = async (label: string, path: string) => {
    try {
      await api('POST', path, { reason: '관리자 테스트 조작' });
      setMsg(`${label} 완료`);
      await load();
    } catch (e) {
      setMsg(`${label} 실패 — ${e instanceof ApiError ? `${e.code}: ${e.message}` : (e as Error).message}`);
    }
  };

  return (
    <div className="grid">
      <section className="card">
        <h2>배송 원장 (Delivery)</h2>
        <table className="list">
          <thead><tr><th>배송</th><th>상태</th><th>모드</th><th>라이더</th><th>비고</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.deliveryId} className={r.deliveryId === sel ? 'sel' : ''} onClick={() => setSel(r.deliveryId)}>
                <td>{r.deliveryId}</td><td>{r.status}</td><td>{r.locationMode === 'SIMULATED_ROUTE' ? 'SIM' : 'GPS'}</td>
                <td>{r.riderId ?? '—'}</td><td>{r.dispatchStalled ? '배차 소진' : r.dispatchNote ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {msg && <div className="msg">{msg}</div>}
      </section>
      <section className="card wide">
        {!detail ? <p className="muted">왼쪽에서 배송을 선택하세요</p> : (
          <>
            <h2>{detail.delivery.deliveryId} <span className="badge">{detail.delivery.status}</span></h2>
            <div className="buttons">
              <button onClick={() => act('재배정', `/test-api/admin/deliveries/${detail.delivery.deliveryId}/reassign`)}>라이더 재배정(픽업 전)</button>
              <button onClick={() => act('재배차 재개', `/test-api/admin/deliveries/${detail.delivery.deliveryId}/resume-dispatch`)}>배차 재개</button>
              <button onClick={() => act('ETA 재계산', `/test-api/admin/deliveries/${detail.delivery.deliveryId}/eta/recompute`)}>ETA 재계산(카카오 1회)</button>
            </div>
            <p>ETA: {detail.customer.eta.status === 'OK' ? `${Math.round((detail.customer.eta.etaSeconds ?? 0) / 60)}분` : detail.customer.eta.message}</p>
            <h3>위치 판정 집계</h3>
            <p>{Object.entries(detail.locationVerdicts).map(([k, v]) => `${k} ${v}`).join(' · ')}</p>
            <div className="cols">
              <div>
                <h3>상태 이력</h3>
                <ol className="log">{detail.history.map((h) => <li key={h.id}><code>{new Date(h.at).toLocaleTimeString()}</code> {h.fromStatus ?? '∅'}→{h.toStatus} [{h.actor}] {h.reason}</li>)}</ol>
              </div>
              <div>
                <h3>배차 제안</h3>
                <ol className="log">{detail.offers.map((o) => <li key={o.id}>{o.riderId} {o.status} {o.reason ?? ''}</li>)}</ol>
                <h3>Outbox</h3>
                <ol className="log">{detail.outbox.map((o) => <li key={o.eventId}>{o.eventType} {o.publishedAt ? '발행됨' : '미발행'} (시도 {o.attempts}) {o.lastError ?? ''}</li>)}</ol>
              </div>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
