import 'dart:math';
import 'package:flutter/material.dart';
import '../models/assistant_state.dart';
import '../models/neural_models.dart';
import '../painters/neural_network_painter.dart';

class NeuralCircle extends StatefulWidget {
  final AssistantState state;
  final double amplitude;
  final double size;

  const NeuralCircle({
    super.key,
    required this.state,
    required this.amplitude,
    this.size = 100,
  });

  @override
  State<NeuralCircle> createState() => _NeuralCircleState();
}

class _NeuralCircleState extends State<NeuralCircle>
    with TickerProviderStateMixin {
  late AnimationController _mainController;
  late AnimationController _nucleusController;
  late AnimationController _signalController;
  late AnimationController _waveController;

  final List<NeuronNode> nodes = [];
  final List<NeuronConnection> connections = [];
  final List<NeuralParticle> particles = [];

  double _prevMainValue = 0.0;

  @override
  void initState() {
    super.initState();

    _mainController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 4),
    )..repeat();

    _nucleusController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat(reverse: true);

    _signalController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2500),
    )..repeat();

    _waveController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 3),
    )..repeat();

    _mainController.addListener(_onMainTick);

    _initNeuralNetwork();
  }

  void _onMainTick() {
    final current = _mainController.value;
    double dt = current - _prevMainValue;
    if (dt < 0) dt += 1.0; // handle wrap
    _prevMainValue = current;
    _advanceParticles(dt);
  }

  void _advanceParticles(double dt) {
    for (final p in particles) {
      double speedMultiplier = 1.0;
      switch (widget.state) {
        case AssistantState.thinking:
          speedMultiplier = 2.5;
          break;
        case AssistantState.listening:
          speedMultiplier = 1.5 + widget.amplitude;
          break;
        case AssistantState.speaking:
          speedMultiplier = 1.8;
          break;
        case AssistantState.idle:
          speedMultiplier = 1.0;
          break;
      }

      p.progress += p.speed * speedMultiplier * dt * 60;

      while (p.progress >= 1.0) {
        p.progress -= 1.0;
        p.currentSegment++;
        if (p.currentSegment >= p.pathNodes.length - 1) {
          p.currentSegment = 0;
        }
      }
    }

    // Advance connection signals
    for (int ci = 0; ci < connections.length; ci++) {
      final conn = connections[ci];
      if (conn.signalActive) {
        conn.signalProgress += 0.008 * dt * 60;
        if (conn.signalProgress >= 1.0) {
          conn.signalProgress = 0.0;
          conn.signalActive = false;
        }
      } else {
        // Randomly fire signals based on state
        final fireChance = switch (widget.state) {
          AssistantState.thinking => 0.004,
          AssistantState.speaking => 0.003,
          AssistantState.listening => 0.002,
          AssistantState.idle => 0.001,
        };
        if (Random().nextDouble() < fireChance) {
          conn.signalActive = true;
          conn.signalProgress = 0.0;
        }
      }
    }
  }

  void _initNeuralNetwork() {
    final rng = Random(42);
    nodes.clear();
    connections.clear();
    particles.clear();

    // Center cluster: 5 nodes within radius 0.2
    for (int i = 0; i < 5; i++) {
      final angle = (i / 5) * 2 * pi;
      final r = rng.nextDouble() * 0.2;
      nodes.add(NeuronNode(
        position: Offset(cos(angle) * r, sin(angle) * r),
        baseRadius: 3.5,
        layer: 0,
        connections: [],
      ));
    }

    // Mid layer: 15 nodes within radius 0.3–0.6
    for (int i = 0; i < 15; i++) {
      final angle = rng.nextDouble() * 2 * pi;
      final r = 0.3 + rng.nextDouble() * 0.3;
      nodes.add(NeuronNode(
        position: Offset(cos(angle) * r, sin(angle) * r),
        baseRadius: 2.5,
        layer: 1,
        connections: [],
      ));
    }

    // Outer layer: 20 nodes within radius 0.6–0.85
    for (int i = 0; i < 20; i++) {
      final angle = rng.nextDouble() * 2 * pi;
      final r = 0.6 + rng.nextDouble() * 0.25;
      nodes.add(NeuronNode(
        position: Offset(cos(angle) * r, sin(angle) * r),
        baseRadius: 2.0,
        layer: 2,
        connections: [],
      ));
    }

    _buildConnections(rng);
    _buildParticles(rng);
  }

  void _buildConnections(Random rng) {
    final connectionColors = [
      const Color(0xFF00E5FF), // cyan
      const Color(0xFF7B2FBE), // violet
      const Color(0xFF00BFA5), // teal
    ];

    // Connect center cluster nodes to each other
    for (int i = 0; i < 5; i++) {
      for (int j = i + 1; j < 5; j++) {
        _addConnection(i, j, connectionColors[rng.nextInt(connectionColors.length)]);
      }
    }

    // For each node, connect to nearest 2–4 neighbors
    for (int i = 0; i < nodes.length; i++) {
      final numConns = 2 + rng.nextInt(3); // 2–4
      final distances = <MapEntry<int, double>>[];

      for (int j = 0; j < nodes.length; j++) {
        if (i == j) continue;
        final d = (nodes[i].position - nodes[j].position).distance;
        distances.add(MapEntry(j, d));
      }

      distances.sort((a, b) => a.value.compareTo(b.value));

      int added = 0;
      for (final entry in distances) {
        if (added >= numConns) break;
        _addConnection(
          i,
          entry.key,
          connectionColors[rng.nextInt(connectionColors.length)],
        );
        added++;
      }
    }
  }

  void _addConnection(int a, int b, Color color) {
    // Avoid duplicate connections
    final alreadyExists = connections.any(
      (c) => (c.nodeA == a && c.nodeB == b) || (c.nodeA == b && c.nodeB == a),
    );
    if (alreadyExists) return;

    connections.add(NeuronConnection(
      nodeA: a,
      nodeB: b,
      color: color,
      signalActive: false,
      signalProgress: 0.0,
    ));
    nodes[a].connections.add(b);
    nodes[b].connections.add(a);
  }

  void _buildParticles(Random rng) {
    for (int p = 0; p < 25; p++) {
      // Build a path: start at a random node, follow connections
      final startNode = rng.nextInt(nodes.length);
      final path = <int>[startNode];
      var current = startNode;

      for (int step = 0; step < 4; step++) {
        final conns = nodes[current].connections;
        if (conns.isEmpty) break;
        final next = conns[rng.nextInt(conns.length)];
        if (!path.contains(next)) {
          path.add(next);
          current = next;
        } else {
          break;
        }
      }

      if (path.length < 2) {
        // fallback: just use two connected nodes
        if (connections.isNotEmpty) {
          final conn = connections[rng.nextInt(connections.length)];
          path
            ..clear()
            ..add(conn.nodeA)
            ..add(conn.nodeB);
        }
      }

      particles.add(NeuralParticle(
        pathNodes: path,
        progress: rng.nextDouble(),
        currentSegment: 0,
        speed: 0.003 + rng.nextDouble() * 0.004,
        size: 1.5 + rng.nextDouble() * 1.0,
      ));
    }
  }

  @override
  void dispose() {
    _mainController.removeListener(_onMainTick);
    _mainController.dispose();
    _nucleusController.dispose();
    _signalController.dispose();
    _waveController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([
        _mainController,
        _nucleusController,
        _signalController,
        _waveController,
      ]),
      builder: (context, _) {
        return SizedBox(
          width: widget.size,
          height: widget.size,
          child: ClipOval(
            child: CustomPaint(
              painter: NeuralNetworkPainter(
                nodes: nodes,
                connections: connections,
                particles: particles,
                time: _mainController.value,
                state: widget.state,
                amplitude: widget.amplitude,
                nucleusPulse: _nucleusController.value,
                signalProgress: _signalController.value,
              ),
            ),
          ),
        );
      },
    );
  }
}
