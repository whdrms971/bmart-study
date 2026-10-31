// 지도 SDK 없이 동작하는 경로 개요(SVG). 지도 API 접근이 안 돼도 제어·관측 가능해야 한다.
interface Props {
  points: [number, number][];
  rider: [number, number] | null;
  mfc: [number, number] | null;
  destination: [number, number] | null;
  customerSeen: [number, number] | null;
}

export function RouteMap({ points, rider, mfc, destination, customerSeen }: Props) {
  const all: [number, number][] = [...points];
  for (const p of [rider, mfc, destination, customerSeen]) if (p) all.push(p);
  if (all.length === 0) {
    return <div className="map-empty">경로를 불러오면 여기에 표시됩니다 (검증/미리보기)</div>;
  }
  const lats = all.map((p) => p[0]);
  const lngs = all.map((p) => p[1]);
  const minLat = Math.min(...lats), maxLat = Math.max(...lats);
  const minLng = Math.min(...lngs), maxLng = Math.max(...lngs);
  const W = 640, H = 360, pad = 24;
  // 위도 보정: 경도 1도 거리 ≈ cos(lat) × 위도 1도 거리
  const midLat = (minLat + maxLat) / 2;
  const kx = Math.cos((midLat * Math.PI) / 180);
  const spanX = Math.max((maxLng - minLng) * kx, 1e-6);
  const spanY = Math.max(maxLat - minLat, 1e-6);
  const s = Math.min((W - 2 * pad) / spanX, (H - 2 * pad) / spanY);
  const xy = (p: [number, number]) => {
    const x = pad + (p[1] - minLng) * kx * s + ((W - 2 * pad) - spanX * s) / 2;
    const y = H - pad - (p[0] - minLat) * s - ((H - 2 * pad) - spanY * s) / 2;
    return [x, y] as const;
  };
  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${xy(p)[0].toFixed(1)},${xy(p)[1].toFixed(1)}`).join(' ');
  const dot = (p: [number, number] | null, cls: string, r: number, title: string) => {
    if (!p) return null;
    const [x, y] = xy(p);
    return (
      <circle className={cls} cx={x} cy={y} r={r}>
        <title>{`${title} ${p[0].toFixed(6)}, ${p[1].toFixed(6)}`}</title>
      </circle>
    );
  };
  return (
    <svg className="route-map" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="경로 개요">
      <path d={path} className="route-line" />
      {dot(mfc, 'pt-mfc', 7, 'MFC')}
      {dot(destination, 'pt-dest', 7, '배송지')}
      {dot(customerSeen, 'pt-customer', 9, '고객 API 기준 라이더 위치')}
      {dot(rider, 'pt-rider', 5, '시뮬레이터 최근 전송 위치')}
    </svg>
  );
}
