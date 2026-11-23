# Download Sherpa-ONNX STT & VAD models directly into assets/models/
$assetsDir = Join-Path $PSScriptRoot "..\assets\models"
if (!(Test-Path $assetsDir)) {
    New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null
}

$models = @(
    @{
        Name = "silero_vad.onnx"
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vad-models/silero_vad.onnx"
    },
    @{
        Name = "model.int8.onnx"
        Url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    },
    @{
        Name = "tokens.txt"
        Url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"
    }
)

foreach ($m in $models) {
    $dest = Join-Path $assetsDir $m.Name
    if (Test-Path $dest) {
        Write-Host "[$($m.Name)] already exists in assets/models/." -ForegroundColor Green
    } else {
        Write-Host "Downloading [$($m.Name)] to assets/models/..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $m.Url -OutFile $dest
        Write-Host "[$($m.Name)] downloaded successfully." -ForegroundColor Green
    }
}
Write-Host "All Sherpa-ONNX models are ready in assets/models/." -ForegroundColor Yellow
