
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';

class NeuroModelManager {
  static const String vadModelUrl =
      'https://github.com/k2-fsa/sherpa-onnx/releases/download/vad-models/silero_vad.onnx';

  static const String vadModelName = 'silero_vad.onnx';

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 30),
      receiveTimeout: const Duration(minutes: 5),
    ),
  );

  Future<Directory> get _modelDirectory async {
    final appDir = await getApplicationSupportDirectory();

    final directory = Directory(
      '${appDir.path}/Neuro/models',
    );

    if (!await directory.exists()) {
      await directory.create(recursive: true);
    }

    return directory;
  }

  Future<String> ensureVadModel({
    required void Function(double progress) onProgress,
  }) async {
    final directory = await _modelDirectory;

    final destination = File(
      '${directory.path}/$vadModelName',
    );

    if (await destination.exists() &&
        await destination.length() > 0) {
      onProgress(1.0);
      return destination.path;
    }

    final temporary = File('${destination.path}.part');

    if (await temporary.exists()) {
      await temporary.delete();
    }

    try {
      await _dio.download(
        vadModelUrl,
        temporary.path,
        deleteOnError: true,
        onReceiveProgress: (received, total) {
          if (total > 0) {
            onProgress(received / total);
          }
        },
      );

      if (!await temporary.exists() ||
          await temporary.length() == 0) {
        throw Exception('Downloaded VAD model is empty.');
      }

      if (await destination.exists()) {
        await destination.delete();
      }

      await temporary.rename(destination.path);

      onProgress(1.0);

      return destination.path;
    } catch (e) {
      if (await temporary.exists()) {
        await temporary.delete();
      }

      rethrow;
    }
  }
}
