package com.dmcore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class StripWakeWordTest {

    private fun strip(text: String) = OverlayService.stripWakeWord(text)

    @Test
    fun quitaElWakeWordAlInicio() {
        assertEquals("qué hora es", strip("Jupiter, qué hora es"))
        assertEquals("qué hora es", strip("Júpiter qué hora es"))
        assertEquals("abre WhatsApp", strip("yupiter abre WhatsApp"))
        assertEquals("pon música", strip("oye Jupiter: pon música"))
        assertEquals("pon música", strip("  hey jupiter!  pon música"))
    }

    @Test
    fun dejaIntactoLoDemas() {
        assertEquals("qué hora es", strip("qué hora es"))
        // Júpiter como tema de la pregunta, no como wake word al inicio.
        assertEquals("qué tan lejos está Júpiter", strip("qué tan lejos está Júpiter"))
        // Solo la palabra completa: no cortar una palabra que empieza igual.
        assertEquals("jupiterianos del futuro", strip("jupiterianos del futuro"))
    }

    @Test
    fun soloElWakeWordQuedaVacio() {
        assertEquals("", strip("Jupiter"))
        assertEquals("", strip("Júpiter."))
    }
}
