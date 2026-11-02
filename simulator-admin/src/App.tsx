import { useEffect, useState } from 'react';
import { api, getToken, setToken } from './api';
import { SimulatorPanel } from './SimulatorPanel';
import { MockPanel } from './MockPanel';
import { DeliveriesPanel } from './DeliveriesPanel';
import { ArrivalExceptionsPanel } from './ArrivalExceptionsPanel';

type Tab = 'sim' | 'mock' | 'deliveries' | 'arrival';

export function App() {
  const [token, setTok] = useState<string | null>(getToken());
  const [userId, setUserId] = useState('ADMIN-TEST');
  const [passcode, setPasscode] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('sim');
  const [serverOk, setServerOk] = useState<boolean | null>(null);

  // 테스트 지원 API 존재 여부 확인 — 운영/비테스트 서버면 조작 화면을 열지 않는다
  useEffect(() => {
    if (!token) return;
    api('GET', '/test-api/mfcs')
      .then(() => setServerOk(true))
      .catch((e) => {
        setServerOk(false);
        setErr(`테스트 지원 API 접근 불가 (local-test 서버가 아님?): ${(e as Error).message}`);
      });
  }, [token]);

  const login = async () => {
    setErr(null);
    try {
      const r = await api<{ accessToken: string; role: string }>('POST', '/api/auth/test-login', { userId, passcode });
      if (r.role !== 'ADMIN') throw new Error('테스트 관리자 계정만 사용할 수 있습니다');
      setToken(r.accessToken);
      setTok(r.accessToken);
      setPasscode('');
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  if (!token) {
    return (
      <main className="login">
        <h1>배송 시뮬레이터 (TEST 전용)</h1>
        <p className="muted">local-test 프로필 서버에서만 동작합니다. 운영 서버에는 이 API 가 없습니다.</p>
        <label>관리자 ID <input value={userId} onChange={(e) => setUserId(e.target.value)} /></label>
        <label>패스코드 <input type="password" value={passcode} onChange={(e) => setPasscode(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && void login()} /></label>
        <button onClick={() => void login()}>로그인</button>
        {err && <div className="error">{err}</div>}
      </main>
    );
  }

  return (
    <main>
      <header>
        <h1>BARO-PICK 배송 시뮬레이터 <span className="badge sim">TEST / SIMULATED</span></h1>
        <nav>
          <button className={tab === 'sim' ? 'on' : ''} onClick={() => setTab('sim')}>SIMULATED_ROUTE 제어</button>
          <button className={tab === 'mock' ? 'on' : ''} onClick={() => setTab('mock')}>Mock OMS/MFC</button>
          <button className={tab === 'deliveries' ? 'on' : ''} onClick={() => setTab('deliveries')}>배송 원장</button>
          <button className={tab === 'arrival' ? 'on' : ''} onClick={() => setTab('arrival')}>도착 예외</button>
          <button onClick={() => { setToken(null); setTok(null); }}>로그아웃</button>
        </nav>
      </header>
      {err && <div className="error">{err}</div>}
      {serverOk && tab === 'sim' && <SimulatorPanel />}
      {serverOk && tab === 'mock' && <MockPanel />}
      {serverOk && tab === 'deliveries' && <DeliveriesPanel />}
      {serverOk && tab === 'arrival' && <ArrivalExceptionsPanel />}
    </main>
  );
}
