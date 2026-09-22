# C.O.R.E.

Central Operating & Response Engine — asistente personal estilo Jarvis para DMCore.
Wake word "C.O.R.E.", overlay flotante en Android, pipeline de STT + LLM + TTS.

- Especificación original: [`docs/especificacion.md`](docs/especificacion.md)
- Diseño y decisiones de arquitectura: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md)
- Cómo entrenar el wake word: [`docs/ENTRENAR_WAKE_WORD.md`](docs/ENTRENAR_WAKE_WORD.md)
- Backend (el "cerebro"): [`backend/`](backend/README.md)
- App Android (Kotlin nativo): [`android/`](android)

## Estado actual

Backend instalado y verificado end-to-end (solo falta pegar una `GEMINI_API_KEY` real en
`backend/.env`). App Android con permisos, foreground service y overlay de prueba armados,
todavía sin el modelo de wake word "core" entrenado (ver `docs/ENTRENAR_WAKE_WORD.md`).
