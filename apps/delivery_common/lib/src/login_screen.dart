import 'package:flutter/material.dart';

import 'api_client.dart';
import 'config.dart';
import 'session.dart';

/// 테스트 계정 로그인 (local-test 서버 전용). 서버 주소는 PC 의 LAN IP (localhost 아님).
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.title, required this.expectedRole, required this.defaultUserId, required this.onLoggedIn});

  final String title;
  final String expectedRole;
  final String defaultUserId;
  final void Function(Session session) onLoggedIn;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _url = TextEditingController();
  late final _user = TextEditingController(text: widget.defaultUserId);
  final _pass = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    AppConfig.baseUrl().then((u) => setState(() => _url.text = u));
  }

  Future<void> _login() async {
    final url = AppConfig.normalize(_url.text);
    if (!url.startsWith('http')) {
      setState(() => _error = '서버 주소를 http://<PC LAN IP>:8080 형식으로 입력하세요');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = ApiClient(url);
      final r = await api.login(_user.text.trim(), _pass.text);
      if (r['role'] != widget.expectedRole) {
        throw ApiException(403, 'WRONG_ROLE', '${widget.expectedRole} 계정으로 로그인하세요 (현재 ${r['role']})');
      }
      await AppConfig.saveBaseUrl(url);
      _pass.clear();
      widget.onLoggedIn(Session(api: api, userId: r['userId'] as String, role: r['role'] as String, baseUrl: url));
    } on ApiException catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text('테스트 환경(local-test) 전용 로그인입니다.', style: TextStyle(color: Colors.grey)),
            const SizedBox(height: 12),
            TextField(controller: _url, decoration: const InputDecoration(labelText: '서버 주소 (예: http://192.168.0.10:8080)'),
                keyboardType: TextInputType.url),
            TextField(controller: _user, decoration: const InputDecoration(labelText: '테스트 계정 ID')),
            TextField(controller: _pass, decoration: const InputDecoration(labelText: '패스코드'), obscureText: true,
                onSubmitted: (_) => _login()),
            const SizedBox(height: 16),
            FilledButton(onPressed: _busy ? null : _login, child: Text(_busy ? '로그인 중…' : '로그인')),
            if (_error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_error!, style: const TextStyle(color: Colors.red))),
          ],
        ),
      ),
    );
  }
}
