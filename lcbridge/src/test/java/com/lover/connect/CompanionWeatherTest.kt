package com.lover.connect

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class CompanionWeatherTest {
    private val wttr = """{"current_condition":[{"temp_C":"20","humidity":"60","FeelsLikeC":"19","windspeedKmph":"3","weatherDesc":[{"value":"Clear"}],"lang_zh":[{"value":"晴"}]}],"weather":[{"date":"2026-11-03","maxtempC":"22","mintempC":"10"}]}"""
    private val location = """{"name":"测试城","admin1":"测试省","country":"测试国家","latitude":39.9,"longitude":116.4}"""
    private val forecast = """{"timezone":"Asia/Shanghai","current":{"time":"2026-11-03T10:00","temperature_2m":20,"relative_humidity_2m":60,"apparent_temperature":19,"weather_code":0,"wind_speed_10m":3},"daily":{"temperature_2m_min":[10],"temperature_2m_max":[22]}}"""
    private class Connection(url: URL, private val body: String, private val status: Int = 200,
        private val failure: IOException? = null) : HttpURLConnection(url) {
        var disconnected = false
        var closed = false
        var read = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getInputStream(): ByteArrayInputStream {
            read = true
            return object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
                override fun close() { closed = true; super.close() }
            }
        }
    }
    private class Fixture(vararg val responses: Triple<String, Int, IOException?>) {
        val connections = mutableListOf<Connection>()
        val client = CompanionWeather { url ->
            val (body, status, failure) = responses[connections.size]
            Connection(url, body, status, failure).also { connections.add(it) }
        }
    }
    private fun body(text: String) = Triple(text, 200, null)
    private fun unavailable() = Triple("SECRET_UPSTREAM_BODY", 503, null)

    @Test fun missingOrInvalidCityDoesNotContactAnyProvider() {
        val client = CompanionWeather { error("must not connect") }
        for (city in listOf("", "  ")) {
            assertEquals("weather_city_missing", client.lookup(city).errorCode)
            assertEquals("not_started", client.lookup(city).outcome)
        }
        for (city in listOf("a\nb", "a".repeat(161))) assertEquals("weather_city_invalid", client.lookup(city).errorCode)
    }

    @Test fun primarySuccessEncodesLocationAndClosesStreamWithoutFallback() {
        val f = Fixture(body(wttr))
        val result = f.client.lookup("  测试城 / A?x=1#b  ")
        assertTrue(result.ok)
        assertTrue(result.content!!.contains("晴"))
        val c = f.connections.single()
        assertEquals("format=j1&lang=zh", c.url.query)
        assertNull(c.url.ref)
        assertTrue(c.url.path.contains("%2F"))
        assertTrue(c.disconnected && c.closed)
        assertFalse(c.instanceFollowRedirects)
    }

    @Test fun networkFailureUsesOneBoundedFallbackWithAttribution() {
        val f = Fixture(Triple("", 200, SocketTimeoutException("SECRET")), body("""{"results":[$location]}"""), body(forecast))
        val result = f.client.lookup("UniqueCity")
        assertTrue(result.ok)
        assertTrue(result.content!!.contains("Open-Meteo"))
        assertTrue(result.content!!.contains("模型估算"))
        assertTrue(result.content!!.contains("测试省"))
        assertTrue(result.content!!.contains("Asia/Shanghai"))
        assertFalse(result.toJson().contains("SECRET"))
        assertEquals(3, f.connections.size)
        assertTrue(f.connections.all { it.disconnected })
    }

    @Test fun ambiguousCityNeverQueriesAnArbitraryForecast() {
        val f = Fixture(unavailable(), body("""{"results":[$location,$location]}"""))
        assertEquals("weather_city_ambiguous", f.client.lookup("SameName").errorCode)
        assertEquals(2, f.connections.size)
        assertFalse(f.connections.first().read)
    }

    @Test fun qualifierFiltersSameNamedPlacesEvenWhenProviderDoesNotFilter() {
        val other = location.replace("测试省", "另一个省")
        val f = Fixture(unavailable(), body("""{"results":[$location,$other]}"""), body(forecast))
        assertTrue(f.client.lookup("测试城,测试省").ok)
        assertEquals(3, f.connections.size)
        val absent = Fixture(unavailable(), body("""{"results":[$location,$other]}"""))
        assertEquals("weather_city_not_found", absent.client.lookup("测试城,不存在省").errorCode)
    }

    @Test fun exactCityExcludesPrefixStationsButDoesNotDisambiguateIdenticalCityNames() {
        val station = location.replace("测试城", "测试城车站")
        val f = Fixture(unavailable(), body("""{"results":[$location,$station]}"""), body(forecast))
        assertTrue(f.client.lookup("测试城,测试省").ok)
        val sameName = Fixture(unavailable(), body("""{"results":[$location,$location,$station]}"""))
        assertEquals("weather_city_ambiguous", sameName.client.lookup("测试城,测试省").errorCode)
    }

    @Test fun missingCityInProviderIsNotSuccessfulEmptyWeather() {
        for (body in listOf("{}", """{"results":[]}""")) {
            val f = Fixture(unavailable(), body(body))
            assertEquals("weather_city_not_found", f.client.lookup("NotAPlace").errorCode)
        }
    }

    @Test fun suppliedCoordinatesSkipGeocodingAndInvalidCoordinatesFail() {
        val f = Fixture(unavailable(), body(forecast))
        assertTrue(f.client.lookup("39.9,116.4").ok)
        assertEquals("api.open-meteo.com", f.connections[1].url.host)
        val invalid = Fixture()
        assertEquals("weather_city_invalid", invalid.client.lookup("91,181").errorCode)
    }

    @Test fun brokenJsonOversizeAndMissingRequiredValueAreNotSuccess() {
        for (broken in listOf("<html>error</html>", "x".repeat(512 * 1024 + 1), wttr.replace("\"20\"", "null"), wttr.replace("\"20\"", "\"NaN\""))) {
            val f = Fixture(body(broken), unavailable())
            val result = f.client.lookup("UniqueCity")
            assertFalse(result.ok)
            assertEquals("weather_sources_unavailable", result.errorCode)
            assertTrue(result.content!!.contains("weather_response_invalid"))
            assertFalse(result.toJson().contains("SECRET_UPSTREAM_BODY"))
            assertTrue(f.connections.all { it.disconnected })
        }
    }

    @Test fun rateLimitsAndRedirectsAreNotReadAsWeatherOrRetried() {
        for (status in listOf(429, 301, 401, 503)) {
            val f = Fixture(Triple("SECRET", status, null), unavailable())
            val result = f.client.lookup("UniqueCity")
            assertFalse(result.ok)
            assertTrue(result.content!!.contains(if (status == 429) "weather_rate_limited" else "weather_upstream_failed"))
            assertEquals(2, f.connections.size)
            assertFalse(f.connections.any { it.read })
        }
    }

    @Test fun forecastMissingTemperatureDoesNotTurnNullIntoZero() {
        val f = Fixture(unavailable(), body(forecast.replace("[10]", "[null]")))
        assertFalse(f.client.lookup("39.9,116.4").ok)
    }

    @Test fun interruptionDoesNotStartAnotherProvider() {
        val f = Fixture(body("{}"))
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { f.client.lookup("UniqueCity") }
            assertTrue(f.connections.isEmpty())
        } finally { Thread.interrupted() }
    }

}
