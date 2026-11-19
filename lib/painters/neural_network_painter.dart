import 'dart:math';
import 'package:flutter/material.dart';
import '../models/assistant_state.dart';
import '../models/neural_models.dart';

class NeuralNetworkPainter extends CustomPainter {
  final List<NeuronNode> nodes;
  final List<NeuronConnection> connections;
  final List<NeuralParticle> particles;
  final double time; // 0.0–1.0 loops
  final AssistantState state;
  final double amplitude; // 0.0–1.0
  final double nucleusPulse; // 0.0–1.0
  final double signalProgress; // 0.0–1.0

  // Brand colors
  static const Color _cyan = Color(0xFF00E5FF);
  static const Color _teal = Color(0xFF00BFA5);
  static const Color _violet = Color(0xFF7B2FBE);
  static const Color _purple = Color(0xFFCE93D8);
  static const Color _bg = Color(0xFF050810);
  static const Color _nodeCyan = Color(0xFF00BCD4);
  static const Color _particle = Color(0xFFE0F7FA);

  NeuralNetworkPainter({
    required this.nodes,
    required this.connections,
    required this.particles,
    required this.time,
    required this.state,
    required this.amplitude,
    required this.nucleusPulse,
    required this.signalProgress,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2;

    _drawBackground(canvas, center, radius);
    _drawGlowRings(canvas, center, radius);
    _drawConnections(canvas, center, radius);
    _drawParticles(canvas, center, radius);
    _drawNodes(canvas, center, radius);
    _drawNucleus(canvas, center, radius);
    _drawBorder(canvas, center, radius);
  }

  void _drawBackground(Canvas canvas, Offset center, double radius) {
    final paint = Paint()..color = _bg;
    canvas.drawCircle(center, radius, paint);
  }

  void _drawGlowRings(Canvas canvas, Offset center, double radius) {
    final ring1 = Paint()
      ..shader = RadialGradient(
        colors: [
          _cyan.withValues(alpha: 0.08),
          _violet.withValues(alpha: 0.04),
          Colors.transparent,
        ],
        stops: const [0.0, 0.4, 1.0],
      ).createShader(Rect.fromCircle(center: center, radius: radius * 0.5));
    canvas.drawCircle(center, radius * 0.5, ring1);

    final ring2 = Paint()
      ..shader = RadialGradient(
        colors: [
          Colors.transparent,
          _violet.withValues(alpha: 0.06),
          _cyan.withValues(alpha: 0.03),
          Colors.transparent,
        ],
        stops: const [0.0, 0.5, 0.75, 1.0],
      ).createShader(Rect.fromCircle(center: center, radius: radius * 0.75));
    canvas.drawCircle(center, radius * 0.75, ring2);

    final outerOpacity = state == AssistantState.listening
        ? 0.06 + amplitude * 0.08
        : 0.03;
    final ring3 = Paint()
      ..shader = RadialGradient(
        colors: [
          Colors.transparent,
          _teal.withValues(alpha: outerOpacity),
          Colors.transparent,
        ],
        stops: const [0.6, 0.85, 1.0],
      ).createShader(Rect.fromCircle(center: center, radius: radius));
    canvas.drawCircle(center, radius, ring3);
  }

  Offset _nodePos(NeuronNode node, Offset center, double radius, int index) {
    final wobble = sin(time * 2 * pi + index * 0.7) * 0.5;
    return Offset(
      center.dx + (node.position.dx + wobble / (radius == 0 ? 1 : radius)) * radius * 0.9,
      center.dy + (node.position.dy + wobble / (radius == 0 ? 1 : radius)) * radius * 0.9,
    );
  }

  void _drawConnections(Canvas canvas, Offset center, double radius) {
    for (int ci = 0; ci < connections.length; ci++) {
      final conn = connections[ci];
      if (conn.nodeA >= nodes.length || conn.nodeB >= nodes.length) continue;

      final nodeA = nodes[conn.nodeA];
      final nodeB = nodes[conn.nodeB];
      final posA = _nodePos(nodeA, center, radius, conn.nodeA);
      final posB = _nodePos(nodeB, center, radius, conn.nodeB);

      double extraOpacity = 0.0;
      Color baseColor = conn.color;

      switch (state) {
        case AssistantState.listening:
          if (nodeA.layer == 2 || nodeB.layer == 2) {
            final pulse = sin(time * 2 * pi + ci * 0.3) * 0.5 + 0.5;
            extraOpacity = amplitude * pulse * 0.4;
          }
          break;
        case AssistantState.thinking:
          final phase = (ci / connections.length);
          final dist = (signalProgress - phase).abs();
          if (dist < 0.15) {
            extraOpacity = (1 - dist / 0.15) * 0.5;
            baseColor = _cyan;
          }
          break;
        case AssistantState.speaking:
          final distA = nodeA.position.distance;
          final distB = nodeB.position.distance;
          final avgDist = (distA + distB) / 2;
          final waveFront = signalProgress;
          final diff = (avgDist - waveFront).abs();
          if (diff < 0.2) {
            extraOpacity = (1 - diff / 0.2) * 0.4;
          }
          break;
        case AssistantState.idle:
          extraOpacity = sin(time * 2 * pi * 0.5 + ci * 0.5) * 0.08;
          break;
      }

      // Glow line
      final glowPaint = Paint()
        ..color = baseColor.withValues(alpha: 0.12 + extraOpacity * 0.5)
        ..strokeWidth = 2.5
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 3)
        ..strokeCap = StrokeCap.round;
      canvas.drawLine(posA, posB, glowPaint);

      // Core line
      final corePaint = Paint()
        ..color = baseColor.withValues(alpha: (0.28 + extraOpacity).clamp(0.0, 1.0))
        ..strokeWidth = 0.8
        ..strokeCap = StrokeCap.round;
      canvas.drawLine(posA, posB, corePaint);

      // Signal dot
      if (conn.signalActive) {
        final signalPos =
            Offset.lerp(posA, posB, conn.signalProgress.clamp(0.0, 1.0))!;
        final signalPaint = Paint()
          ..color = Colors.white.withValues(alpha: 0.9)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 4);
        canvas.drawCircle(signalPos, 2.5, signalPaint);
      }
    }
  }

  void _drawParticles(Canvas canvas, Offset center, double radius) {
    for (final particle in particles) {
      if (particle.pathNodes.length < 2) continue;
      final seg =
          particle.currentSegment.clamp(0, particle.pathNodes.length - 2);
      final idxA = particle.pathNodes[seg];
      final idxB = particle.pathNodes[seg + 1];
      if (idxA >= nodes.length || idxB >= nodes.length) continue;

      final posA = _nodePos(nodes[idxA], center, radius, idxA);
      final posB = _nodePos(nodes[idxB], center, radius, idxB);
      final pos = Offset.lerp(posA, posB, particle.progress.clamp(0.0, 1.0))!;

      // Glow
      final glowPaint = Paint()
        ..color = _particle.withValues(alpha: 0.35)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 3);
      canvas.drawCircle(pos, particle.size + 1.5, glowPaint);

      // Core dot
      final dotPaint = Paint()..color = _particle.withValues(alpha: 0.85);
      canvas.drawCircle(pos, particle.size, dotPaint);
    }
  }

  void _drawNodes(Canvas canvas, Offset center, double radius) {
    for (int i = 0; i < nodes.length; i++) {
      final node = nodes[i];
      final pos = _nodePos(node, center, radius, i);

      final pulse = sin(time * 2 * pi + i * 0.9) * 0.4 + 1.0;
      final r = node.baseRadius * pulse;

      final baseColor = node.layer == 0 ? _purple : _nodeCyan;

      // Glow
      final glowPaint = Paint()
        ..color = baseColor.withValues(alpha: 0.35)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 4);
      canvas.drawCircle(pos, r + 3, glowPaint);

      // Core
      final corePaint = Paint()..color = baseColor.withValues(alpha: 0.85);
      canvas.drawCircle(pos, r, corePaint);
    }
  }

  void _drawNucleus(Canvas canvas, Offset center, double radius) {
    double intensity = 1.0;
    switch (state) {
      case AssistantState.thinking:
        intensity = 1.0 + nucleusPulse * 1.2;
        break;
      case AssistantState.speaking:
        intensity = 1.0 + nucleusPulse * 0.8;
        break;
      case AssistantState.listening:
        intensity = 1.0 + amplitude * 0.6;
        break;
      case AssistantState.idle:
        intensity = 0.7 + nucleusPulse * 0.4;
        break;
    }

    final innerR = (8 + nucleusPulse * 4) * intensity;

    // Outer halo
    final haloPaint = Paint()
      ..color = _cyan.withValues(alpha: (0.12 * intensity).clamp(0.0, 1.0))
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 18);
    canvas.drawCircle(center, innerR * 2.5, haloPaint);

    // Mid glow
    final midPaint = Paint()
      ..color = _violet.withValues(alpha: (0.3 * intensity).clamp(0.0, 1.0))
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 8);
    canvas.drawCircle(center, innerR * 1.4, midPaint);

    // Outer ring (cyan)
    final outerRingPaint = Paint()
      ..color = _cyan.withValues(alpha: 0.3)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;
    canvas.drawCircle(center, innerR * 1.6, outerRingPaint);

    // Inner violet core
    final innerPaint = Paint()
      ..color = _violet.withValues(alpha: 0.9)
      ..maskFilter = MaskFilter.blur(BlurStyle.normal, (3 * intensity).clamp(0.5, 20.0));
    canvas.drawCircle(center, innerR, innerPaint);

    // Bright core center
    final brightPaint = Paint()..color = _purple.withValues(alpha: 0.95);
    canvas.drawCircle(center, innerR * 0.5, brightPaint);
  }

  void _drawBorder(Canvas canvas, Offset center, double radius) {
    final borderPaint = Paint()
      ..color = _cyan.withValues(alpha: 0.4)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;
    canvas.drawCircle(center, radius - 0.5, borderPaint);
  }

  @override
  bool shouldRepaint(NeuralNetworkPainter oldDelegate) => true;
}
