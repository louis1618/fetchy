import 'package:flutter/services.dart';

/// Mirrors the Android channel contract. Maps preserve native engine metadata.
class NativeBridge {
  static const methods = MethodChannel('com.luverse.fetchy/native');
  static const events = EventChannel('com.luverse.fetchy/events');

  Future<dynamic> call(String method, [Map<String, dynamic>? arguments]) =>
      methods.invokeMethod<dynamic>(method, arguments);

  Stream<Map<String, dynamic>> get snapshots =>
      events.receiveBroadcastStream().map(asMap);
}

Map<String, dynamic> asMap(dynamic value) => value is Map
    ? value.map((key, value) => MapEntry(key.toString(), value))
    : <String, dynamic>{};

List<Map<String, dynamic>> asMaps(dynamic value) => value is List
    ? value.whereType<Map>().map(asMap).toList()
    : <Map<String, dynamic>>[];

String bytesLabel(dynamic value) {
  final bytes = value is num ? value.toDouble() : 0.0;
  if (bytes <= 0) return '크기 미정';
  if (bytes < 1024) return '${bytes.toInt()} B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
}

const defaultSettings = <String, dynamic>{
  'quality': 1080,
  'audioFormat': 'm4a',
  'videoFormat': 'mp4',
  'platformFolders': true,
  'nameRule': 'title_id',
  'duplicates': 'rename',
  'wifiOnly': false,
  'mobileData': true,
  'concurrency': 2,
  'retries': 3,
  'resume': true,
  'notifications': true,
  'theme': 'system',
  'audioMetadata': true,
  'audioCover': true,
};
