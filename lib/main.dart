import 'dart:convert';
import 'package:flutter/material.dart';
import 'bridge_channel.dart';

void main() => runApp(const BridgeApp());

class BridgeApp extends StatelessWidget {
  const BridgeApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'M4T Bridge',
        theme: ThemeData.dark(useMaterial3: true),
        home: const HomePage(),
      );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _ws = TextEditingController(text: 'ws://192.168.1.10:8080/telemetry/m4t-001');
  bool _control = false;
  final _rtmp = TextEditingController(text: 'rtmp://192.168.1.10:1935/m4t');
  bool _running = false;
  String _lens = 'rgb';
  String _sdk = '-';
  Map<String, dynamic> _tele = {};
  final List<String> _logs = [];

  @override
  void initState() {
    super.initState();
    BridgeChannel.events.listen(_onEvent);
    BridgeChannel.status().then((s) => setState(
        () => _sdk = 'registered=${s['registered']} drone=${s['connected']}'));
  }

  void _onEvent(Map<String, dynamic> e) {
    setState(() {
      switch (e['type']) {
        case 'telemetry':
          _tele = jsonDecode(e['data'] as String) as Map<String, dynamic>;
          break;
        case 'sdk':
          _sdk = e['msg'] as String;
          break;
        default:
          _logs.insert(0, e['msg']?.toString() ?? e.toString());
          if (_logs.length > 40) _logs.removeLast();
      }
    });
  }

  Future<void> _toggle() async {
    if (_running) {
      await BridgeChannel.stop();
    } else {
      await BridgeChannel.start(_ws.text.trim(), _rtmp.text.trim(), control: _control);
    }
    setState(() => _running = !_running);
  }

  @override
  Widget build(BuildContext context) {
    final pos = (_tele['pos'] ?? {}) as Map;
    return Scaffold(
      appBar: AppBar(title: const Text('M4T Bridge · PASUPASASTRA')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text('SDK: $_sdk'),
        const SizedBox(height: 12),
        TextField(
            controller: _ws,
            decoration: const InputDecoration(labelText: 'WebSocket URL')),
        TextField(
            controller: _rtmp,
            decoration: const InputDecoration(labelText: 'RTMP URL')),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Izinkan kendali dari GCS'),
          subtitle: const Text(
              'Lepas landas, mendarat, RTH, dan misi waypoint dari PASUPASASTRA. '
              'Berlaku saat bridge dimulai. Matikan bila tidak diawasi pilot.'),
          value: _control,
          onChanged: _running ? null : (v) => setState(() => _control = v),
        ),
        const SizedBox(height: 12),
        FilledButton(
            onPressed: _toggle,
            child: Text(_running ? 'STOP BRIDGE' : 'START BRIDGE')),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 6),
          child: Text('Stop hanya menghentikan bridge, drone tidak terpengaruh.',
              style: TextStyle(fontSize: 12, color: Colors.grey)),
        ),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'rgb', label: Text('RGB')),
            ButtonSegment(value: 'thermal', label: Text('Thermal')),
          ],
          selected: {_lens},
          onSelectionChanged: (s) {
            setState(() => _lens = s.first);
            BridgeChannel.setLens(s.first);
          },
        ),
        const Divider(height: 32),
        Text('Lat ${pos['lat'] ?? '-'}  Lon ${pos['lon'] ?? '-'}  Alt ${pos['alt'] ?? '-'}'),
        Text('Heading ${_tele['heading'] ?? '-'}  Battery ${_tele['battery'] ?? '-'}%'),
        Text('Sats ${_tele['sats'] ?? '-'}  Flying ${_tele['flying'] ?? '-'}'),
        const Divider(height: 32),
        ..._logs.map((l) => Text(l, style: const TextStyle(fontSize: 12))),
      ]),
    );
  }
}
