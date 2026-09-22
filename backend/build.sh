#!/usr/bin/env bash
set -e

pip install -r requirements.txt

mkdir -p models
if [ ! -f models/es_ES-davefx-medium.onnx ]; then
  curl -sL -o models/es_ES-davefx-medium.onnx \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx"
  curl -sL -o models/es_ES-davefx-medium.onnx.json \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx.json"
fi
