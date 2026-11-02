import 'package:delivery_common/delivery_common.dart';
import 'package:kakao_flutter_sdk_navi/kakao_flutter_sdk_navi.dart';
import 'package:url_launcher/url_launcher.dart';

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

/// 카카오내비 앱 길안내(공식 kakao_flutter_sdk_navi). 음성 턴바이턴은 카카오내비 앱이 수행하고, 출발지는 휴대폰의 실제 위치다.
/// 차종은 이륜차(VehicleType.twoWheel). 카카오내비 미설치 시 설치 페이지를 연다.
/// 반환: 사용자에게 보여줄 안내 문구(성공 시 null).
Future<String?> launchKakaoNavi({required String name, required double lat, required double lng}) async {
  if (!AppConfig.hasKakaoKey) return 'KAKAO_NATIVE_APP_KEY 가 없어 카카오내비를 실행할 수 없습니다';
  try {
    if (await NaviApi.instance.isKakaoNaviInstalled()) {
      await NaviApi.instance.navigate(
        destination: Location(name: name, x: '$lng', y: '$lat'),
        option: NaviOption(coordType: CoordType.wgs84, vehicleType: VehicleType.twoWheel),
      );
      return null;
    }
    await launchUrl(Uri.parse(NaviApi.webNaviInstall), mode: LaunchMode.externalApplication);
    return '카카오내비가 설치되어 있지 않아 설치 페이지를 열었습니다';
  } catch (e) {
    return '카카오내비 실행 실패: $e';
  }
}
