import 'package:flutter/services.dart';

/// Jembatan Dart <-> Kotlin. Nama channel harus sama dengan di MainActivity.kt
class BridgeChannel {
  static const _method = MethodChannel('m4t/bridge');
  static const _events = EventChannel('m4t/events');

  static Stream<Map<String, dynamic>> get events => _events
      .receiveBroadcastStream()
      .map((e) => Map<String, dynamic>.from(e as Map));

  /// [control] = interlock: allow PASUPATASTRA to send flight commands and missions.
  static Future<void> start(String ws, String rtmp, {bool control = false}) =>
      _method.invokeMethod('start', {'ws': ws, 'rtmp': rtmp, 'control': control});

  static Future<void> stop() => _method.invokeMethod('stop');

  /// lens: "rgb" atau "thermal"
  static Future<void> setLens(String lens) =>
      _method.invokeMethod('setLens', {'lens': lens});

  static Future<Map<String, dynamic>> status() async =>
      Map<String, dynamic>.from(await _method.invokeMethod('status') as Map);
}
