import 'package:flutter/material.dart';

import 'services/tools.dart';

void main() {
  runApp(const ToolsTestApp());
}

class ToolsTestApp extends StatelessWidget {
  const ToolsTestApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Instagram Reels Test',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: const ToolsTestPage(),
    );
  }
}

class ToolsTestPage extends StatefulWidget {
  const ToolsTestPage({super.key});

  @override
  State<ToolsTestPage> createState() => _ToolsTestPageState();
}

class _ToolsTestPageState extends State<ToolsTestPage> {
  String _status = 'Ready';
  bool _isLoading = false;

  Future<void> _openAccessibilitySettings() async {
    setState(() {
      _isLoading = true;
      _status = 'Opening Accessibility Settings...';
    });

    final ok = await openAccessibilitySettings();

    if (!mounted) return;
    setState(() {
      _isLoading = false;
      _status = ok
          ? 'Opened Accessibility Settings. Enable "Neuro Assistant".'
          : 'Failed: could not open Accessibility Settings';
    });
  }

  Future<void> _runOpenInstagramReels() async {
    setState(() {
      _isLoading = true;
      _status = 'Launching Instagram & navigating to Reels...';
    });

    final ok = await openInstagramReels();

    if (!mounted) return;
    setState(() {
      _isLoading = false;
      _status = ok
          ? 'Success: Instagram Reels navigation verified'
          : 'Failed: Reels navigation did not succeed (see logs)';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Instagram Reels Test')),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: _isLoading ? null : _openAccessibilitySettings,
                child: const Text('Open Accessibility Settings'),
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: _isLoading ? null : _runOpenInstagramReels,
                child: const Text('Test openInstagramReels'),
              ),
            ),
            const SizedBox(height: 16),
            Text(_status, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
