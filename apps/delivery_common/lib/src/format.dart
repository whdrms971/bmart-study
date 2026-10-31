/// 표시용 포맷 도우미.
String ago(int? seconds) {
  if (seconds == null) return '—';
  if (seconds < 60) return '$seconds초 전';
  if (seconds < 3600) return '${seconds ~/ 60}분 전';
  return '${seconds ~/ 3600}시간 전';
}

String hhmm(String? iso) {
  if (iso == null) return '—';
  final t = DateTime.tryParse(iso)?.toLocal();
  if (t == null) return '—';
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(t.hour)}:${two(t.minute)}:${two(t.second)}';
}

String meters(num? m) {
  if (m == null) return '—';
  return m >= 1000 ? '${(m / 1000).toStringAsFixed(1)}km' : '${m.round()}m';
}

/// ISO 시각 비교 (a 가 b 보다 이전이면 true). 늦게 도착한 과거 메시지로 화면을 되돌리지 않기 위해 사용.
bool isOlder(String? a, String? b) {
  if (a == null || b == null) return false;
  final ta = DateTime.tryParse(a), tb = DateTime.tryParse(b);
  if (ta == null || tb == null) return false;
  return ta.isBefore(tb);
}
