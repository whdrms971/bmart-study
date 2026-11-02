import 'package:flutter/material.dart';

import 'login_screen.dart';
import 'session.dart';

/// 앱 최상위 세션 관문: 로그인 유지 복원 → 로그인 화면 → 홈, 로그아웃(401 포함).
/// 고객·라이더 앱이 같은 규칙을 쓰도록 공통화했다.
/// - 401 은 [ApiClient.onUnauthorized] 로 이 세션에만 연결 → 예전 세션의 늦은 요청이 새 세션을 로그아웃시키지 않는다
/// - 로그아웃은 세션을 먼저 비워 동시에 여러 401 이 와도 한 번만 처리하고, 열린 상세 화면을 모두 닫는다
/// - 보안 저장소 삭제가 실패해도 화면은 로그인으로 돌아간다(다음 복원 시 만료·401 로 다시 정리됨)
class SessionGate extends StatefulWidget {
  const SessionGate({
    super.key,
    required this.role,
    required this.appTitle,
    required this.loginTitle,
    required this.defaultUserId,
    required this.theme,
    required this.home,
    this.store,
  });

  final String role;
  final String appTitle;
  final String loginTitle;
  final String defaultUserId;
  final ThemeData theme;
  final Widget Function(Session session, VoidCallback logout) home;

  /// 테스트에서 메모리 저장소 주입용. 기본은 기기 보안 저장소.
  final SessionStore? store;

  @override
  State<SessionGate> createState() => _SessionGateState();
}

class _SessionGateState extends State<SessionGate> {
  late final SessionStore _store = widget.store ?? SessionStore(widget.role);
  final _nav = GlobalKey<NavigatorState>();
  Session? _session;
  bool _restoring = true;

  @override
  void initState() {
    super.initState();
    _store.restore().then((s) {
      if (!mounted) return;
      setState(() {
        _restoring = false;
        if (s != null) _activate(s);
      });
    });
  }

  void _activate(Session s) {
    s.api.onUnauthorized = () => _logout(s);
    _session = s;
  }

  Future<void> _loggedIn(Session s) async {
    try {
      await _store.save(s);
    } catch (e) {
      debugPrint('세션 저장 실패(이번 실행 동안만 로그인 유지): $e');
    }
    if (!mounted) return;
    setState(() => _activate(s));
  }

  Future<void> _logout(Session s) async {
    if (!identical(_session, s)) return; // 이미 로그아웃했거나 다른(새) 세션
    s.api.onUnauthorized = null;
    setState(() => _session = null);
    _nav.currentState?.popUntil((r) => r.isFirst);
    try {
      await _store.clear();
    } catch (e) {
      debugPrint('세션 삭제 실패: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _session;
    return MaterialApp(
      navigatorKey: _nav,
      title: widget.appTitle,
      theme: widget.theme,
      home: _restoring
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : s == null
              ? LoginScreen(title: widget.loginTitle, expectedRole: widget.role, defaultUserId: widget.defaultUserId, onLoggedIn: _loggedIn)
              : widget.home(s, () => _logout(s)),
    );
  }
}
