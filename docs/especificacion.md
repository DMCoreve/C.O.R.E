# Especificación del Proyecto: C.O.R.E. (Asistente Móvil de Escucha Continua)

---

## 1. Identidad del Asistente

* **Nombre Oficial:** C.O.R.E.
* **Acrónimo Suggested:** *Central Operating & Response Engine* (o *Digital Manager & Central Operating Response Engine*)
* **Vinculación:** Alineado directamente con la marca de la empresa **DMCore**.
* **Palabra de Activación (*Wake Word*):** `C.O.R.E.` (Pronunciada con la fonética directa castellana `/k-o-r-e/`).
* **Enfoque de Invocar:** Una sola palabra directa (sin muletillas del tipo *"Hey"* u *"Ok"*).

---

## 2. Visión del Proyecto y Experiencia de Usuario (UX)

* **Formato:** Asistente personal móvil estilo *JARVIS* de escucha continua 24/7.
* **Interfaz de Usuario (UI):**
  * Al activarse, despliega una **pantalla/tarjeta flotante semi-transparente (*Overlay Window*)** sobre cualquier app activa (al estilo *Google Assistant* o *Siri*).
  * Incluye una animación visual (orbe u ondas de voz) y el texto transcrito en tiempo real.
  * No abre la aplicación a pantalla completa a menos que sea explícitamente necesario.

---

## 3. Arquitectura Técnica (3 Capas)

### Capa 1: Detección Local de Palabra de Activación (*Wake Word*)
* **Módulo:** Escucha continua en segundo plano mediante un servicio prioritario (*Foreground Service* en Android).
* **Herramienta Sugerida:** *Picovoice Porcupine* u *openWakeWord*.
* **Requisito Técnico:**
  * Procesamiento **100% local** en el dispositivo para garantizar privacidad y bajo consumo de batería.
  * Modelo entrenado o configurado específicamente para la firma acústica fonética en español (`/k-o-r-e/`).
  * Umbral de sensibilidad calibrado (entre `0.3` y `0.4`) para evitar activaciones accidentales en conversaciones sobre la empresa *DMCore*.

### Capa 2: Transcripción de Voz a Texto (*Speech-to-Text / STT*)
* **Procesamiento:** Captura del audio tras la detección de la *Wake Word*.
* **Herramientas:** *Whisper* (local vía `whisper.cpp` o API) / *Deepgram* / *Google Speech-to-Text*.

### Capa 3: Inteligencia y Síntesis de Voz (*LLM + TTS*)
* **Cerebro / Razonamiento:** API de *Gemini*, *Claude* o modelos locales vía *Llama.cpp*.
* **Síntesis de Voz (*Text-to-Speech*):** *ElevenLabs*, *Piper* o *Coqui TTS* para respuestas de voz naturales y fluidas.

---

## 4. Estrategia de Desarrollo e Instalación

### Herramienta de Desarrollo
* **Claude Code:** Agente principal para el desarrollo, generación de código, estructura de carpetas, manejo de dependencias e integración nativa del proyecto.
* *(Claude Cowork se descarta para código, reservándolo únicamente para documentación técnica o lógica de negocio).*

### Plataforma Objetivo y Permisos (Android)
* **Framework:** Flutter / Kotlin.
* **Permisos Críticos en Android:**
  * `RECORD_AUDIO` (Acceso al micrófono).
  * `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (Para evitar que el sistema operativo cierre el servicio de escucha).
  * `SYSTEM_ALERT_WINDOW` (Permiso para dibujar la ventana flotante sobre otras aplicaciones).
  * Exención de **Optimización de Batería** en la configuración del teléfono.
* **Instalación:** Compilación a archivo ejecutable `.apk` e instalación directa en el dispositivo activando *"Orígenes desconocidos"*.

---

## 5. Próximos Pasos Recomendados

1. Ejecutar **Claude Code** en tu entorno local.
2. Usar un prompt de inicialización para construir la estructura base del proyecto móvil.
3. Entrenar / Configurar la *Wake Word* **C.O.R.E.** en la consola de Picovoice o en openWakeWord con el fonema español.