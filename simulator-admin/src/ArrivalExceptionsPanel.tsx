import { useCallback, useEffect, useState } from 'react';
import { api, ArrivalExceptionStatus, ArrivalExceptionView } from './api';

const REASON: Record<ArrivalExceptionView['reasonCode'], string> = {
  GPS_ERROR: 'GPS 오류',
  ADDRESS_COORDINATE_ERROR: '주소 좌표 오류',
  COMPLEX_ENTRY: '아파트·단지 진입 문제',
  OTHER: '기타',
};

const fmt = (v: number | null, unit: string) => (v == null ? '-' : `${Math.round(v * 10) / 10}${unit}`);
const time = (s: string | null) => (s ? new Date(s).toLocaleString() : '-');

/**
 * 고객 도착 예외 처리 (운영 API /api/admin/arrival-exceptions — local-test 전용 아님).
 * 승인 시 처리 사유가 필수이며 처리자·시각·요청 당시 거리·정확도가 감사 기록으로 남는다.
 */
export function ArrivalExceptionsPanel() {
  const [filter, setFilter] = useState<ArrivalExceptionStatus | 'ALL'>('PENDING');
  const [rows, setRows] = useState<ArrivalExceptionView[]>([]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const q = filter === 'ALL' ? '' : `?status=${filter}`;
      setRows(await api<ArrivalExceptionView[]>('GET', `/api/admin/arrival-exceptions${q}`));
    } catch (e) {
      setMsg((e as Error).message);
    }
  }, [filter]);

  useEffect(() => {
    void load();
    const h = window.setInterval(load, 3000);
    return () => window.clearInterval(h);
  }, [load]);

  const decide = async (r: ArrivalExceptionView, action: 'approve' | 'reject') => {
    const note = (notes[r.exceptionId] ?? '').trim();
    if (!note) {
      setMsg('처리 사유를 입력하세요');
      return;
    }
    setBusy(true);
    try {
      const res = await api<ArrivalExceptionView>('POST', `/api/admin/arrival-exceptions/${r.exceptionId}/${action}`, { note });
      setMsg(`${r.exceptionId} → ${res.status} (${res.result})`);
      setNotes((n) => ({ ...n, [r.exceptionId]: '' }));
      await load();
    } catch (e) {
      setMsg((e as Error).message);
      await load();
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card wide">
      <h2>고객 도착 예외</h2>
      <p className="muted">배송지 150m 초과·위치 불량으로 정상 도착 판정이 불가능할 때 라이더가 요청합니다. 승인하면 배송이 '배송지 도착'으로 바뀌며, 처리 사유·처리자·시각이 기록됩니다.</p>
      <div className="row">
        {(['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'ALL'] as const).map((s) => (
          <button key={s} className={filter === s ? 'on' : ''} onClick={() => setFilter(s)}>{s === 'ALL' ? '전체(최근 100)' : s}</button>
        ))}
      </div>
      {msg && <div className="msg">{msg}</div>}
      {rows.length === 0 && <p className="muted">해당 요청이 없습니다.</p>}
      {rows.map((r) => (
        <div key={r.exceptionId} className="order">
          <div>
            <b>{r.exceptionId}</b> <span className="badge">{r.status}</span> 배송 {r.deliveryId} · 주문 {r.orderId ?? '-'} · 라이더 {r.riderId}
          </div>
          <div className="muted">
            사유 {REASON[r.reasonCode]}{r.riderNote ? ` — "${r.riderNote}"` : ''} · 요청 {time(r.requestedAt)}
          </div>
          <div className="muted">
            요청 당시 판정 {r.checkCode} · 직선거리 {fmt(r.distanceMeters, 'm')} · 정확도 {fmt(r.accuracyMeters, 'm')} · 위치 {r.locationAgeSeconds == null ? '-' : `${r.locationAgeSeconds}초 전`} · 출처 {r.locationSource ?? '-'}
          </div>
          {r.status === 'PENDING' ? (
            <div className="row">
              <input style={{ flex: 1, minWidth: 200 }} placeholder="처리 사유 (필수, 예: 고객 통화로 도착 확인)" value={notes[r.exceptionId] ?? ''}
                onChange={(e) => setNotes((n) => ({ ...n, [r.exceptionId]: e.target.value }))} />
              <button disabled={busy} onClick={() => void decide(r, 'approve')}>승인</button>
              <button disabled={busy} onClick={() => void decide(r, 'reject')}>반려</button>
            </div>
          ) : (
            <div className="muted">처리 {r.decidedBy ?? '-'} · {time(r.decidedAt)} · {r.decisionNote ?? ''}</div>
          )}
        </div>
      ))}
    </section>
  );
}
