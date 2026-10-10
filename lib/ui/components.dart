import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';

class MediaCover extends StatelessWidget {
  const MediaCover({
    super.key,
    this.source,
    this.height,
    this.icon = Icons.video_library_outlined,
    this.onImageReady,
  });
  final String? source;
  final double? height;
  final IconData icon;
  final VoidCallback? onImageReady;
  @override
  Widget build(BuildContext context) {
    final fallback = ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerLow,
      child: Center(
        child: Icon(
          icon,
          size: 34,
          color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .35),
        ),
      ),
    );
    Widget child = fallback;
    if (source != null && source!.isNotEmpty) {
      child = source!.startsWith('/')
          ? Image.file(
              File(source!),
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => fallback,
            )
          : Image.network(
              source!,
              fit: BoxFit.cover,
              frameBuilder: (_, child, frame, _) {
                if (frame != null && onImageReady != null) {
                  WidgetsBinding.instance.addPostFrameCallback(
                    (_) => onImageReady?.call(),
                  );
                }
                return child;
              },
              errorBuilder: (_, _, _) => fallback,
            );
    }
    return SizedBox(
      height: height,
      width: double.infinity,
      child: ClipRRect(borderRadius: BorderRadius.circular(12), child: child),
    );
  }
}

class PlatformMark extends StatelessWidget {
  const PlatformMark(this.platform, {super.key, this.size = 38});
  final String platform;
  final double size;
  @override
  Widget build(BuildContext context) {
    final p = platform.toLowerCase();
    return SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: _PlatformPainter(p)),
    );
  }
}

class _PlatformPainter extends CustomPainter {
  _PlatformPainter(this.platform);
  final String platform;
  @override
  void paint(Canvas canvas, Size size) {
    final r = size.width;
    final rect = Rect.fromLTWH(0, 0, r, r);
    final p = Paint();
    switch (platform) {
      case 'youtube':
        p.color = const Color(0xffff0033);
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(0, r * .16, r, r * .68),
            Radius.circular(r * .2),
          ),
          p,
        );
        p.color = Colors.white;
        canvas.drawPath(
          Path()
            ..moveTo(r * .42, r * .32)
            ..lineTo(r * .42, r * .68)
            ..lineTo(r * .72, r * .5)
            ..close(),
          p,
        );
      case 'instagram':
        p.shader = const LinearGradient(
          colors: [Color(0xffffc65c), Color(0xfff1288a), Color(0xff8c36ed)],
          begin: Alignment.bottomLeft,
          end: Alignment.topRight,
        ).createShader(rect);
        canvas.drawRRect(
          RRect.fromRectAndRadius(rect, Radius.circular(r * .25)),
          p,
        );
        p
          ..shader = null
          ..color = Colors.white
          ..style = PaintingStyle.stroke
          ..strokeWidth = r * .07;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            rect.deflate(r * .2),
            Radius.circular(r * .18),
          ),
          p,
        );
        canvas.drawCircle(Offset(r * .5, r * .5), r * .145, p);
        p.style = PaintingStyle.fill;
        canvas.drawCircle(Offset(r * .7, r * .3), r * .045, p);
      case 'tiktok':
        p.color = Colors.black;
        canvas.drawRRect(
          RRect.fromRectAndRadius(rect, Radius.circular(r * .23)),
          p,
        );
        void note(Color color, double dx) {
          p
            ..color = color
            ..style = PaintingStyle.stroke
            ..strokeWidth = r * .1
            ..strokeCap = StrokeCap.round;
          canvas.drawPath(
            Path()
              ..moveTo(r * .57 + dx, r * .22)
              ..lineTo(r * .57 + dx, r * .62)
              ..cubicTo(
                r * .56 + dx,
                r * .9,
                r * .22 + dx,
                r * .83,
                r * .3 + dx,
                r * .62,
              )
              ..quadraticBezierTo(r * .35 + dx, r * .56, r * .43 + dx, r * .59),
            p,
          );
          canvas.drawPath(
            Path()
              ..moveTo(r * .57 + dx, r * .25)
              ..quadraticBezierTo(r * .65 + dx, r * .43, r * .77 + dx, r * .4),
            p,
          );
        }
        note(const Color(0xff25f4ee), -r * .025);
        note(const Color(0xfffe2c55), r * .025);
        note(Colors.white, 0);
      case 'x':
        p.color = Colors.black;
        canvas.drawRRect(
          RRect.fromRectAndRadius(rect, Radius.circular(r * .23)),
          p,
        );
        p
          ..color = Colors.white
          ..style = PaintingStyle.stroke
          ..strokeWidth = r * .065;
        canvas.drawPath(
          Path()
            ..moveTo(r * .24, r * .2)
            ..lineTo(r * .76, r * .8)
            ..lineTo(r * .65, r * .8)
            ..lineTo(r * .15, r * .2)
            ..close(),
          p,
        );
        canvas.drawLine(Offset(r * .76, r * .2), Offset(r * .24, r * .8), p);
      case 'reddit':
        p
          ..shader = null
          ..color = const Color(0xffff4500)
          ..style = PaintingStyle.fill;
        canvas.drawCircle(Offset(r / 2, r / 2), r / 2, p);
        p.color = Colors.white;
        canvas.drawOval(Rect.fromLTWH(r * .2, r * .37, r * .6, r * .38), p);
        canvas.drawCircle(Offset(r * .2, r * .46), r * .09, p);
        canvas.drawCircle(Offset(r * .8, r * .46), r * .09, p);
        p
          ..style = PaintingStyle.stroke
          ..strokeWidth = r * .045;
        canvas.drawPath(
          Path()
            ..moveTo(r * .52, r * .37)
            ..lineTo(r * .58, r * .2)
            ..lineTo(r * .75, r * .24),
          p,
        );
        p.style = PaintingStyle.fill;
        canvas.drawCircle(Offset(r * .78, r * .24), r * .055, p);
        p.color = const Color(0xffff4500);
        canvas.drawCircle(Offset(r * .38, r * .52), r * .055, p);
        canvas.drawCircle(Offset(r * .62, r * .52), r * .055, p);
        p
          ..style = PaintingStyle.stroke
          ..strokeWidth = r * .035;
        canvas.drawArc(
          Rect.fromLTWH(r * .37, r * .53, r * .26, r * .13),
          0,
          math.pi,
          false,
          p,
        );
      default:
        final colors = {
          'reddit': const Color(0xffff4500),
          'pinterest': const Color(0xffe60023),
          'facebook': const Color(0xff0866ff),
        };
        p
          ..shader = null
          ..color = colors[platform] ?? const Color(0xff818894)
          ..style = PaintingStyle.fill;
        canvas.drawCircle(Offset(r / 2, r / 2), r / 2, p);
        final text = TextPainter(
          text: TextSpan(
            text:
                {'reddit': 'r', 'pinterest': 'P', 'facebook': 'f'}[platform] ??
                '•••',
            style: TextStyle(
              color: Colors.white,
              fontSize: r * .72,
              fontWeight: FontWeight.w800,
            ),
          ),
          textDirection: TextDirection.ltr,
        )..layout();
        text.paint(canvas, Offset((r - text.width) / 2, (r - text.height) / 2));
    }
  }

  @override
  bool shouldRepaint(_PlatformPainter old) => old.platform != platform;
}

class FetchyLogo extends StatelessWidget {
  const FetchyLogo({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox(
    width: 220,
    height: 250,
    child: CustomPaint(painter: _LogoPainter()),
  );
}

class _LogoPainter extends CustomPainter {
  const _LogoPainter();
  @override
  void paint(Canvas canvas, Size size) {
    void oval(Offset center, double rotation, List<Color> colors) {
      canvas.save();
      canvas.translate(center.dx, center.dy);
      canvas.rotate(rotation);
      final rect = Rect.fromCenter(center: Offset.zero, width: 90, height: 190);
      final paint = Paint()
        ..shader = LinearGradient(
          colors: colors,
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ).createShader(rect);
      canvas.drawOval(rect, paint);
      canvas.drawOval(
        rect.deflate(2),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5
          ..color = Colors.white.withValues(alpha: .35),
      );
      canvas.restore();
    }

    oval(const Offset(99, 98), math.pi * .2, [
      const Color(0xffb8ebff),
      const Color(0xff1680ff),
      const Color(0xff155be8),
    ]);
    oval(const Offset(126, 157), math.pi * .24, [
      const Color(0xff83d8ff),
      const Color(0xff8676f5),
      const Color(0xfff88dce),
    ]);
  }

  @override
  bool shouldRepaint(_LogoPainter old) => false;
}

String durationLabel(dynamic value) {
  final seconds = value is num ? value.toInt() : 0;
  if (seconds <= 0) return '';
  final m = seconds ~/ 60;
  return '${m ~/ 60 > 0 ? '${m ~/ 60}:' : ''}${m ~/ 60 > 0 ? (m % 60).toString().padLeft(2, '0') : m}:${(seconds % 60).toString().padLeft(2, '0')}';
}

String platformName(dynamic value) => switch ('$value'.toLowerCase()) {
  'youtube' => 'YouTube',
  'instagram' => 'Instagram',
  'tiktok' => 'TikTok',
  'x' || 'twitter' || 'x (twitter)' => 'X',
  'reddit' => 'Reddit',
  'pinterest' => 'Pinterest',
  'facebook' => 'Facebook',
  'other' => 'Other',
  'null' => '',
  _ => '$value',
};
