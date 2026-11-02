// Delivery(local-test) 서버 API 클라이언트. 토큰은 sessionStorage 에만 둔다(탭 종료 시 폐기).

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

const TOKEN_KEY = 'sim-admin-token';

export function getToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(t: string | null): void {
  try {
    if (t) sessionStorage.setItem(TOKEN_KEY, t);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage 불가 환경 무시 */
  }
}

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError(0, 'NETWORK', `서버 연결 실패: ${(e as Error).message}`);
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const d = (data ?? {}) as { code?: string; message?: string };
    if (res.status === 401) setToken(null);
    throw new ApiError(res.status, d.code ?? `HTTP_${res.status}`, d.message ?? (typeof data === 'string' ? data : res.statusText));
  }
  return data as T;
}

// ---------------- 타입 (서버 DTO 와 동일 필드명)

export type SimState = 'CREATED' | 'READY' | 'RUNNING' | 'PAUSED' | 'FINISHED' | 'STOPPED' | 'FAILED';
export type FaultType = 'STOP' | 'DROP' | 'DELAY' | 'DUPLICATE' | 'REORDER' | 'ANOMALY' | 'DISCONNECT';

export interface FaultSpec {
  type: FaultType;
  atFraction: number;
  durationTicks?: number | null;
  durationSeconds?: number | null;
}

export interface LogEntry {
  realAt: string;
  virtualMs: number;
  type: string;
  detail: string;
}

export interface SimStatus {
  scenarioId: string;
  deliveryId: string;
  riderId: string;
  state: SimState;
  routeSource: 'FIXTURE' | 'KAKAO_DIRECTIONS';
  fixtureId: string | null;
  rate: number;
  speedMps: number;
  seed: number;
  progressedMeters: number;
  totalMeters: number;
  progressFraction: number;
  virtualElapsedMs: number;
  createdAt: string;
  startedAt: string | null;
  lastSentAt: string | null;
  lastPosition: [number, number] | null;
  connected: boolean;
  stopped: boolean;
  lastError: string | null;
  deliveryStatus: string;
  counters: {
    ticks: number;
    generated: number;
    sent: number;
    acceptedLatest: number;
    history: number;
    duplicate: number;
    rejected: number;
    stale: number;
    /** 경로 끝 도달 후 배송지 정차 위치 전송 수 */
    dwellSent: number;
  };
  plannedFaults: FaultSpec[];
  recentLog: LogEntry[];
  label: string;
  /** 경로 끝(FINISHED) 이후 배송지 정차 위치를 보내는 중 (고객 도착 판정의 30초 최신성 유지) */
  dwelling: boolean;
}

export type ArrivalExceptionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface ArrivalExceptionView {
  exceptionId: string;
  deliveryId: string;
  orderId: string | null;
  riderId: string;
  assignmentVersion: number;
  reasonCode: 'GPS_ERROR' | 'ADDRESS_COORDINATE_ERROR' | 'COMPLEX_ENTRY' | 'OTHER';
  riderNote: string | null;
  checkCode: string;
  distanceMeters: number | null;
  accuracyMeters: number | null;
  locationAgeSeconds: number | null;
  locationSource: string | null;
  locationMeasuredAt: string | null;
  status: ArrivalExceptionStatus;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  result: string | null;
}

export interface RoutePreview {
  source: string;
  routeRef: string;
  pointCount: number;
  totalMeters: number;
  providerDurationSeconds: number | null;
  fetchedAt: string | null;
  note: string;
  points: [number, number][];
}

export interface Fixture {
  id: string;
  description: string;
  provenance: string;
  mfcId: string;
  destinationLabel: string;
  destinationAddress: string;
  destLat: number;
  destLng: number;
  defaultSpeedMps: number;
}

export interface DeliveryRow {
  deliveryId: string;
  orderId: string;
  customerId: string;
  mfcId: string;
  status: string;
  locationMode: 'DEVICE_GPS' | 'SIMULATED_ROUTE';
  riderId: string | null;
  assignmentVersion: number;
  packagesReady: boolean;
  dispatchNote: string | null;
  dispatchStalled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Eta {
  status: string;
  etaSeconds: number | null;
  distanceMeters: number | null;
  computedAt: string | null;
  estimatedArrivalAt: string | null;
  provider: string | null;
  message: string;
}

export interface CustomerView {
  deliveryId: string;
  status: string;
  statusLabel: string;
  simulated: boolean;
  destination: { latitude: number; longitude: number; address: string; addressDetail: string | null };
  riderLocationVisible: boolean;
  rider: { latitude: number; longitude: number; receivedAt: string; source: string; ageSeconds: number } | null;
  eta: Eta;
}

export interface RiderView {
  mfc: { mfcId: string; name: string; latitude: number; longitude: number; virtualSite: boolean };
  packages: { packageId: string; status: string }[];
}

export interface DeliveryDetail {
  delivery: DeliveryRow;
  rider: RiderView;
  customer: CustomerView;
  history: { id: number; fromStatus: string | null; toStatus: string; actor: string; reason: string; at: string }[];
  attempts: { id: string; attemptNo: number; status: string; reason: string | null; createdAt: string }[];
  offers: { id: string; riderId: string; status: string; reason: string | null; offeredAt: string; expiresAt: string }[];
  locationVerdicts: Record<string, number>;
  recentLocations: Record<string, string | number>[];
  outbox: { eventId: string; eventType: string; publishedAt: string | null; attempts: number; lastError: string | null }[];
}

export interface MockPackage {
  packageId: string;
  deliveryId: string;
  orderId: string;
  status: string;
  qrCode: string;
  handedOverRider: string | null;
  handedOverAt: string | null;
}

export interface OrderView {
  orderId: string;
  customerId: string;
  mfcId: string;
  status: string;
  totalWeightGrams: number;
  address: string;
  split: boolean;
  createdAt: string;
  deliveries: { deliveryId: string; packageCount: number; weightGrams: number; status: string }[];
}

export interface MfcSite {
  id: string;
  name: string;
  virtual: boolean;
  latitude: number;
  longitude: number;
  locationMode: string;
  radiusPolicy: string;
  radiusMeters: number | null;
}
