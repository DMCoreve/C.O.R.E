# Entrenar el wake word "core"

openWakeWord no trae "core" como modelo preentrenado (solo trae palabras como "alexa",
"hey jarvis", "hey mycroft", etc.), así que hay que entrenar uno custom. Como se decidió
usar la pronunciación en inglés (ver `docs/ARQUITECTURA.md`), el flujo estándar y
documentado de openWakeWord funciona sin adaptar nada.

## Paso a paso (Google Colab, ~1 hora, sin experiencia previa)

1. Abre el notebook oficial de entrenamiento simple:
   https://colab.research.google.com/drive/1q1oe2zOyZp7UsB3jJiQ1IFn8z5YfjwEb?usp=sharing
2. Inicia sesión con una cuenta de Google (Colab lo pide para ejecutar celdas).
3. Donde el notebook pide la palabra/frase objetivo, escribe `core`.
4. Corre las celdas en orden — el notebook genera automáticamente cientos de muestras
   sintéticas de "core" con distintas voces en inglés, las mezcla con ruido/negativos, y
   entrena un modelo pequeño.
5. Al final el notebook exporta un archivo `.onnx` (y/o `.tflite`) del modelo entrenado.
   Descárgalo.
6. Prueba el modelo localmente antes de meterlo al proyecto: el propio notebook trae una
   celda de prueba con el micrófono, o se puede probar con `openwakeword` instalado en
   `backend/venv` (aunque ese venv es para el backend, no para Android, sirve para probar
   rápido en la PC):
   ```bash
   cd backend
   venv\Scripts\activate
   pip install openwakeword
   python -c "from openwakeword.model import Model; m = Model(wakeword_models=['ruta/al/core.onnx']); print('cargado ok')"
   ```

## Siguiente paso: conectarlo a Android

El modelo `.onnx`/`.tflite` resultante va en
`android/app/src/main/assets/` (carpeta que todavía no existe — créala al llegar aquí), y
`CoreForegroundService.kt` (ver el `TODO(wake word)` en ese archivo) es el lugar marcado
para:
1. Cargar el modelo con un runtime de inferencia en Android (ONNX Runtime Mobile o
   TensorFlow Lite, según el formato que exporte el notebook).
2. Capturar audio del micrófono en frames de 80ms (formato que espera openWakeWord).
3. Cuando la predicción supere el umbral (probar entre 0.3 y 0.5), lanzar
   `OverlayService` y empezar a capturar el audio siguiente para mandarlo al backend.

Esto todavía no está implementado — es el siguiente slice de trabajo una vez tengas el
modelo entrenado y descargado.
