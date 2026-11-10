package io.github.kamradsmeshnyavy.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private fun now(t: Double, country: String = "") = Weather.Now(t, 0, true, "X", 0L, country)

    @Test
    fun unitsFollowThePlaceNotThePhone() {
        assertFalse(Weather.fahrenheit("auto", "RU"))
        assertFalse(Weather.fahrenheit("auto", ""))
        assertTrue(Weather.fahrenheit("auto", "us"))
        assertTrue(Weather.fahrenheit("f", "RU"))
        assertFalse(Weather.fahrenheit("c", "US"))
    }

    @Test
    fun formatsWithATrueMinus() {
        assertEquals("4°", Weather.format(now(3.8, "RU"), "auto"))
        assertEquals("−3°", Weather.format(now(-2.6), "auto"))
        assertEquals("39°F", Weather.format(now(3.8, "RU"), "f"))
    }

    @Test
    fun wmoCodes() {
        assertEquals(Weather.Kind.CLEAR, Weather.kindOf(0))
        assertEquals(Weather.Kind.PARTLY, Weather.kindOf(2))
        assertEquals(Weather.Kind.FOG, Weather.kindOf(48))
        assertEquals(Weather.Kind.RAIN, Weather.kindOf(81))
        assertEquals(Weather.Kind.SNOW, Weather.kindOf(73))
        assertEquals(Weather.Kind.STORM, Weather.kindOf(95))
        assertEquals(Weather.Kind.CLOUDY, Weather.kindOf(1234))
    }
}
