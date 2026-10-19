/// One second of the uplink while streaming, reported about once a second.
///
/// Both platforms lower the video bitrate on their own when the uplink cannot
/// keep up, and climb back once it can: [videoBitrate] below
/// [maxVideoBitrate] means the picture is being sent at reduced quality.
class BandwidthStats {
  /// Whether the uplink keeps up with what the encoders produce. False while
  /// frames are queueing (iOS) or being dropped (Android) for want of
  /// bandwidth.
  final bool sufficient;

  /// What actually left the phone in the last second, in bits.
  final int sentBitsPerSecond;

  /// The video encoder's current bitrate, in bits/s.
  final int videoBitrate;

  /// The video bitrate the stream was configured with, in bits/s.
  final int maxVideoBitrate;

  const BandwidthStats({
    required this.sufficient,
    required this.sentBitsPerSecond,
    required this.videoBitrate,
    required this.maxVideoBitrate,
  });

  factory BandwidthStats.fromMap(Map<dynamic, dynamic> map) => BandwidthStats(
        sufficient: map['sufficient'] as bool,
        sentBitsPerSecond: (map['sentBitsPerSecond'] as num).toInt(),
        videoBitrate: (map['videoBitrate'] as num).toInt(),
        maxVideoBitrate: (map['maxVideoBitrate'] as num).toInt(),
      );

  @override
  String toString() =>
      'BandwidthStats(sufficient: $sufficient, sent: $sentBitsPerSecond, '
      'video: $videoBitrate/$maxVideoBitrate)';
}
