/// 외부 길안내(카카오맵) 링크.
/// - 앱: `kakaomap://route` — 출발지를 비우면 카카오맵 앱이 기기의 실시간 위치를 출발지로 쓴다.
/// - 웹(앱 미설치): 출발지를 넘기지 않으면 라이더가 출발지를 직접 입력해야 하므로, 알고 있는 위치가 있으면 함께 넘긴다.
Uri kakaoMapAppRoute(double lat, double lng) => Uri.parse('kakaomap://route?ep=$lat,$lng&by=CAR');

Uri kakaoMapWebRoute({required String destName, required double lat, required double lng, double? startLat, double? startLng}) {
  final to = '${Uri.encodeComponent(destName)},$lat,$lng';
  if (startLat == null || startLng == null) {
    return Uri.parse('https://map.kakao.com/link/to/$to');
  }
  return Uri.parse('https://map.kakao.com/link/from/${Uri.encodeComponent('현위치')},$startLat,$startLng/to/$to');
}
