import 'dart:ui';

class NeuronNode {
  final Offset position; // normalized -1..1
  final double baseRadius;
  final int layer; // 0=center, 1=mid, 2=outer
  List<int> connections;

  NeuronNode({
    required this.position,
    required this.baseRadius,
    required this.layer,
    required this.connections,
  });
}

class NeuronConnection {
  final int nodeA;
  final int nodeB;
  double signalProgress; // 0.0–1.0 signal dot position
  bool signalActive;
  Color color;

  NeuronConnection({
    required this.nodeA,
    required this.nodeB,
    this.signalProgress = 0.0,
    this.signalActive = false,
    required this.color,
  });
}

class NeuralParticle {
  List<int> pathNodes; // sequence of node indices
  double progress; // 0.0–1.0 along current segment
  int currentSegment;
  double speed;
  double size;

  NeuralParticle({
    required this.pathNodes,
    this.progress = 0.0,
    this.currentSegment = 0,
    required this.speed,
    required this.size,
  });
}
