import 'dart:async';
import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';

import 'config.dart';

enum RealtimeState { connecting, connected, disconnected }

/// STOMP 실시간 구독. 연결이 (재)수립될 때마다 [onConnected] 를 호출해 앱이 최신 상태를 재조회하게 한다
/// (끊긴 동안의 메시지를 신뢰하지 않음 — D-23). 메시지 순서 역행 방지는 서버 판정 + 화면의 updatedAt 비교로 한다.
class RealtimeClient {
  RealtimeClient({required this.baseUrl, required this.token, required this.topic, required this.onMessage, this.onConnected});

  final String baseUrl;
  final String token;
  final String topic;
  final void Function(Map<String, dynamic> message) onMessage;
  final void Function()? onConnected;

  final _state = StreamController<RealtimeState>.broadcast();
  Stream<RealtimeState> get states => _state.stream;
  RealtimeState current = RealtimeState.disconnected;
  String? lastError;
  StompClient? _client;
  StompUnsubscribe? _unsub;

  void connect() {
    _set(RealtimeState.connecting);
    _client = StompClient(
      config: StompConfig(
        url: AppConfig.wsUrl(baseUrl),
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        reconnectDelay: const Duration(seconds: 3),
        heartbeatIncoming: const Duration(seconds: 10),
        heartbeatOutgoing: const Duration(seconds: 10),
        onConnect: (frame) {
          _unsub?.call();
          _unsub = _client?.subscribe(
            destination: topic,
            callback: (f) {
              final body = f.body;
              if (body == null) return;
              try {
                onMessage(jsonDecode(body) as Map<String, dynamic>);
              } catch (_) {/* 형식 오류 메시지는 무시 */}
            },
          );
          lastError = null;
          _set(RealtimeState.connected);
          onConnected?.call();
        },
        onStompError: (f) {
          lastError = f.headers['message'] ?? 'STOMP 오류';
          _set(RealtimeState.disconnected);
        },
        onWebSocketError: (e) {
          lastError = '$e';
          _set(RealtimeState.disconnected);
        },
        onWebSocketDone: () => _set(RealtimeState.disconnected),
        onDisconnect: (_) => _set(RealtimeState.disconnected),
      ),
    );
    _client!.activate();
  }

  void _set(RealtimeState s) {
    current = s;
    if (!_state.isClosed) _state.add(s);
  }

  /// 배송 완료·권한 종료·화면 이탈 시 구독 해제.
  void dispose() {
    try {
      _unsub?.call();
    } catch (_) {}
    _client?.deactivate();
    _client = null;
    _state.close();
  }
}
