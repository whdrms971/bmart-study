import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// 포장 QR 스캔. 인식된 값은 서버로 보내 MFC 가 판정한다 (앱은 형식만 거른다).
class ScanScreen extends StatefulWidget {
  const ScanScreen({super.key});

  @override
  State<ScanScreen> createState() => _ScanScreenState();
}

class _ScanScreenState extends State<ScanScreen> {
  final _controller = MobileScannerController(detectionSpeed: DetectionSpeed.noDuplicates);
  final _manual = TextEditingController();
  bool _done = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _finish(String v) {
    if (_done) return;
    _done = true;
    Navigator.of(context).pop(v.trim());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('포장 QR 스캔')),
      body: Column(children: [
        Expanded(
          child: MobileScanner(
            controller: _controller,
            onDetect: (capture) {
              for (final b in capture.barcodes) {
                final v = b.rawValue;
                if (v != null && v.startsWith('BPKG|')) return _finish(v);
              }
            },
            errorBuilder: (context, error) => Center(child: Text('카메라 오류: ${error.errorCode.name}\n아래에 코드를 직접 입력하세요')),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            Expanded(child: TextField(controller: _manual, decoration: const InputDecoration(labelText: 'QR 인식 실패 시 코드 직접 입력 (BPKG|…)'))),
            IconButton(onPressed: () => _manual.text.isNotEmpty ? _finish(_manual.text) : null, icon: const Icon(Icons.check)),
          ]),
        ),
      ]),
    );
  }
}
