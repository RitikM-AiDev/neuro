import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';

class ModelPackagePaths {
  final String vadModelPath;
  final String sttModelPath;
  final String sttTokensPath;

  const ModelPackagePaths({
    required this.vadModelPath,
    required this.sttModelPath,
    required this.sttTokensPath,
  });

  bool get isComplete =>
      vadModelPath.isNotEmpty &&
      sttModelPath.isNotEmpty &&
      sttTokensPath.isNotEmpty;
}

class ModelDownloadInfo {
  final String name;
  final String primaryUrl;
  final String? mirrorUrl;
  final String assetPath;

  const ModelDownloadInfo({
    required this.name,
    required this.primaryUrl,
    this.mirrorUrl,
    required this.assetPath,
  });
}

class NeuroModelManager {
  static const String vadModelName = 'silero_vad.onnx';
  static const String sttModelName = 'model.int8.onnx';
  static const String sttTokensName = 'tokens.txt';

  static const List<ModelDownloadInfo> requiredModels = [
    ModelDownloadInfo(
      name: vadModelName,
      primaryUrl:
          'https://github.com/k2-fsa/sherpa-onnx/releases/download/vad-models/silero_vad.onnx',
      mirrorUrl:
          'https://huggingface.co/csukuangfj/sherpa-onnx-silero-vad/resolve/main/silero_vad.onnx',
      assetPath: 'assets/models/silero_vad.onnx',
    ),
    ModelDownloadInfo(
      name: sttModelName,
      primaryUrl:
          'https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx',
      mirrorUrl:
          'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/model.int8.onnx',
      assetPath: 'assets/models/model.int8.onnx',
    ),
    ModelDownloadInfo(
      name: sttTokensName,
      primaryUrl:
          'https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt',
      assetPath: 'assets/models/tokens.txt',
    ),
  ];

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 30),
      receiveTimeout: const Duration(minutes: 10),
    ),
  );

  Future<Directory> get modelDirectory async {
    final appDir = await getApplicationSupportDirectory();
    final directory = Directory('${appDir.path}/Neuro/models');

    if (!await directory.exists()) {
      await directory.create(recursive: true);
    }

    return directory;
  }

  /// Checks if all required models (VAD + STT) are already downloaded/available.
  Future<bool> areModelsReady() async {
    final directory = await modelDirectory;
    for (final model in requiredModels) {
      final file = File('${directory.path}/${model.name}');
      if (!await file.exists() || await file.length() == 0) {
        return false;
      }
    }
    return true;
  }

  /// Ensures both VAD and STT models are available.
  /// 1. If present in app storage, uses it directly.
  /// 2. If present in packaged assets (assets/models/), extracts it.
  /// 3. Otherwise, downloads directly from official Sherpa-ONNX repositories.
  Future<ModelPackagePaths> ensureAllModels({
    void Function(String modelName, double progress)? onProgress,
  }) async {
    final directory = await modelDirectory;
    final Map<String, String> resolvedPaths = {};

    for (final model in requiredModels) {
      final targetFile = File('${directory.path}/${model.name}');

      // 1. Already exists in local storage
      if (await targetFile.exists() && await targetFile.length() > 0) {
        onProgress?.call(model.name, 1.0);
        resolvedPaths[model.name] = targetFile.path;
        continue;
      }

      // 2. Try copying from bundled APK assets (if developer bundled them)
      bool extractedFromAsset = false;
      try {
        final byteData = await rootBundle.load(model.assetPath);
        if (byteData.lengthInBytes > 0) {
          final buffer = byteData.buffer;
          await targetFile.writeAsBytes(
            buffer.asUint8List(byteData.offsetInBytes, byteData.lengthInBytes),
            flush: true,
          );
          extractedFromAsset = true;
          onProgress?.call(model.name, 1.0);
          resolvedPaths[model.name] = targetFile.path;
          debugPrint('Loaded ${model.name} from bundled assets.');
        }
      } catch (_) {
        // Asset not bundled in APK, proceed to network download
        extractedFromAsset = false;
      }

      if (extractedFromAsset) continue;

      // 3. Download from network (first-time launch setup)
      final downloadedPath = await _downloadModelFile(
        model: model,
        destinationFile: targetFile,
        onProgress: (progress) => onProgress?.call(model.name, progress),
      );

      resolvedPaths[model.name] = downloadedPath;
    }

    return ModelPackagePaths(
      vadModelPath: resolvedPaths[vadModelName] ?? '',
      sttModelPath: resolvedPaths[sttModelName] ?? '',
      sttTokensPath: resolvedPaths[sttTokensName] ?? '',
    );
  }

  /// Downloads a single model file using primary URL, falling back to mirror URL on failure.
  Future<String> _downloadModelFile({
    required ModelDownloadInfo model,
    required File destinationFile,
    required void Function(double progress) onProgress,
  }) async {
    final temporary = File('${destinationFile.path}.part');

    if (await temporary.exists()) {
      await temporary.delete();
    }

    // Try primary URL first
    try {
      debugPrint('Downloading ${model.name} from ${model.primaryUrl}...');
      await _dio.download(
        model.primaryUrl,
        temporary.path,
        deleteOnError: true,
        onReceiveProgress: (received, total) {
          if (total > 0) {
            onProgress(received / total);
          }
        },
      );
    } catch (primaryError) {
      debugPrint(
        'Primary download failed for ${model.name}: $primaryError. Trying mirror...',
      );

      // If mirror URL is available, retry with mirror
      if (model.mirrorUrl != null) {
        if (await temporary.exists()) {
          await temporary.delete();
        }
        await _dio.download(
          model.mirrorUrl!,
          temporary.path,
          deleteOnError: true,
          onReceiveProgress: (received, total) {
            if (total > 0) {
              onProgress(received / total);
            }
          },
        );
      } else {
        rethrow;
      }
    }

    if (!await temporary.exists() || await temporary.length() == 0) {
      throw Exception('Downloaded file ${model.name} is empty or incomplete.');
    }

    if (await destinationFile.exists()) {
      await destinationFile.delete();
    }

    await temporary.rename(destinationFile.path);
    onProgress(1.0);
    debugPrint('Completed downloading ${model.name} (${await destinationFile.length()} bytes)');
    return destinationFile.path;
  }
}
