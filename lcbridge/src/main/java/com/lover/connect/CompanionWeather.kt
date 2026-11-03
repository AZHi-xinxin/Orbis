package com.lover.connect

import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

internal val weatherProblems = mapOf(
    "weather_city_missing" to "尚未设置天气城市。请到北斗导航→手机与陪伴→本地资料→天气城市，填写并保存。不会按出口 IP 猜测位置。",
    "weather_city_invalid" to "天气城市格式不正确。请在手机与陪伴→本地资料修改城市名称，或填写纬度,经度。",
    "weather_city_not_found" to "备用天气源未找到该城市。请核对拼写，可加省份或国家，例如 Paris, France。",
    "weather_city_ambiguous" to "备用天气源找到多个同名地点，尚未查询其中任意地点。请在天气城市后加省份，或填写纬度,经度。",
    "weather_timeout" to "天气服务等待超时，不代表手机与陪伴的其他工具不可用。",
    "weather_network_failed" to "天气服务连接失败。检查当前网络能否访问天气服务；VPN 开启不等于该站点可达。",
    "weather_rate_limited" to "天气服务暂时限流，请稍后再查。",
    "weather_upstream_failed" to "天气服务返回异常状态，未取得可用天气。",
    "weather_response_invalid" to "天气服务的数据不完整或格式异常，没有把错误页面当成天气。",
    "weather_sources_unavailable" to "主、备用天气源均未返回可用天气。请稍后再查；无需重填模型密钥或重新授权手机权限。",
)

/** Only the configured city is sent. No GPS, IP-location fallback, cache, credentials or retries. */
internal class CompanionWeather(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private class Problem(val code: String) : Exception()
    private fun failed(code: String, content: String? = null, started: Boolean = true) =
        CompanionToolResult(false, content, code, if (started) "failed" else "not_started")

    fun lookup(configuredCity: String): CompanionToolResult {
        val city = configuredCity.trim()
        if (city.isEmpty()) return failed("weather_city_missing", started = false)
        if (city.length > 160 || city in setOf(".", "..") || city.any { it.isISOControl() }) return failed("weather_city_invalid", started = false)
        val coordinates = city.split(',').map { it.trim().toDoubleOrNull() }
        if (coordinates.size == 2 && coordinates.all { it != null } &&
            (!coordinates[0]!!.isFinite() || !coordinates[1]!!.isFinite() ||
                coordinates[0]!! !in -90.0..90.0 || coordinates[1]!! !in -180.0..180.0))
            return failed("weather_city_invalid", started = false)
        return try {
            CompanionToolResult(true, wttr(city))
        } catch (first: Problem) {
            try {
                CompanionToolResult(true, meteo(city))
            } catch (second: Problem) {
                if (second.code in setOf("weather_city_not_found", "weather_city_ambiguous", "weather_city_invalid"))
                    failed(second.code)
                else failed("weather_sources_unavailable", "wttr.in：${first.code}；Open-Meteo：${second.code}")
            }
        }
    }

    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun read(url: String): JSONObject {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        var connection: HttpURLConnection? = null
        try {
            val conn = open(URL(url)).also { connection = it }
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Orbis-Weather/1.0")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 429) throw Problem("weather_rate_limited")
            if (conn.responseCode != 200) throw Problem("weather_upstream_failed")
            val bytes = conn.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    if (System.nanoTime() > deadline) throw Problem("weather_timeout")
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 512 * 1024) throw Problem("weather_response_invalid")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (e: Problem) { throw e
        } catch (_: SocketTimeoutException) { throw Problem("weather_timeout")
        } catch (e: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted) throw e
            throw Problem("weather_network_failed")
        } catch (_: IOException) { throw Problem("weather_network_failed")
        } catch (_: org.json.JSONException) { throw Problem("weather_response_invalid")
        } finally { connection?.disconnect() }
    }

    private fun number(json: JSONObject, key: String): String {
        val value = json.opt(key)?.toString()?.toDoubleOrNull()
        if (value == null || !value.isFinite()) throw Problem("weather_response_invalid")
        return value.toString()
    }

    private fun wttr(city: String): String = parse {
        val json = read("https://wttr.in/${encoded(city)}?format=j1&lang=zh")
        val current = json.getJSONArray("current_condition").getJSONObject(0)
        val today = json.getJSONArray("weather").getJSONObject(0)
        val desc = (current.optJSONArray("lang_zh")?.takeIf { it.length() > 0 }
            ?: current.getJSONArray("weatherDesc")).getJSONObject(0).getString("value")
        val place = json.optJSONArray("nearest_area")?.optJSONObject(0)
        val resolved = listOf("areaName", "region", "country").mapNotNull {
            place?.optJSONArray(it)?.optJSONObject(0)?.optString("value")?.takeIf(String::isNotBlank)
        }.distinct().joinToString(" / ")
        buildString {
            appendLine("查询城市：$city${if (resolved.isBlank()) "" else "；匹配地点：$resolved"}")
            appendLine("来源：wttr.in；数据日期：${today.getString("date")}")
            appendLine("当前：$desc ${number(current, "temp_C")}°C；体感：${number(current, "FeelsLikeC")}°C")
            appendLine("湿度：${number(current, "humidity")}%；风速：${number(current, "windspeedKmph")}km/h")
            append("今日：${number(today, "mintempC")}°C ~ ${number(today, "maxtempC")}°C")
        }
    }

    private fun meteo(city: String): String = parse {
        val coordinates = city.split(',').map { it.trim().toDoubleOrNull() }
        val lat: Double
        val lon: Double
        val place: String
        if (coordinates.size == 2 && coordinates.all { it != null }) {
            lat = coordinates[0]!!; lon = coordinates[1]!!; place = city
        } else {
            val language = if (city.any { it in '\u4e00'..'\u9fff' }) "zh" else "en"
            val locations = read("https://geocoding-api.open-meteo.com/v1/search?name=${encoded(city)}&count=10&language=$language&format=json")
                .optJSONArray("results") ?: throw Problem("weather_city_not_found")
            val qualifier = city.substringAfter(',', "").trim()
            val qualified = (0 until locations.length()).map { locations.getJSONObject(it) }.filter { location ->
                qualifier.isEmpty() || listOf("admin1", "country", "country_code").any {
                    location.optString(it).equals(qualifier, ignoreCase = true)
                }
            }
            // The search also returns prefix matches (airports/stations/districts). Prefer an
            // exact city name within the requested region, but still reject multiple exact matches.
            val requestedName = city.substringBefore(',').trim()
            val exact = qualified.filter { it.optString("name").equals(requestedName, ignoreCase = true) }
            val matches = exact.ifEmpty { qualified }
            if (matches.isEmpty()) throw Problem("weather_city_not_found")
            // Never silently pick a different same-named city when the main provider fails.
            if (matches.size != 1) throw Problem("weather_city_ambiguous")
            val location = matches.single()
            lat = location.getDouble("latitude"); lon = location.getDouble("longitude")
            place = listOf("name", "admin1", "country").mapNotNull {
                location.optString(it).takeIf(String::isNotBlank)
            }.distinct().joinToString(" / ")
        }
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            throw Problem("weather_city_invalid")
        val json = read("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1&temperature_unit=celsius&wind_speed_unit=kmh")
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val temperatures = JSONObject().put("min", daily.getJSONArray("temperature_2m_min").get(0))
            .put("max", daily.getJSONArray("temperature_2m_max").get(0))
        buildString {
            appendLine("查询城市：$city；匹配地点：$place")
            appendLine("来源：Open-Meteo（备用天气源，当前为模型估算）；时间：${current.getString("time")} ${json.getString("timezone")}")
            appendLine("当前：${weatherDescription(current.getInt("weather_code"))} ${number(current, "temperature_2m")}°C；体感：${number(current, "apparent_temperature")}°C")
            appendLine("湿度：${number(current, "relative_humidity_2m")}%；风速：${number(current, "wind_speed_10m")}km/h")
            append("今日：${number(temperatures, "min")}°C ~ ${number(temperatures, "max")}°C")
        }
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (_: org.json.JSONException) { throw Problem("weather_response_invalid") }
}

private fun weatherDescription(code: Int): String = when (code) {
    0 -> "晴"; 1 -> "大部晴朗"; 2 -> "多云"; 3 -> "阴"; 45, 48 -> "雾"
    51, 53, 55 -> "毛毛雨"; 56, 57 -> "冻毛毛雨"; 61, 63, 65 -> "雨"; 66, 67 -> "冻雨"
    71, 73, 75, 77 -> "雪"; 80, 81, 82 -> "阵雨"; 85, 86 -> "阵雪"; 95 -> "雷雨"; 96, 99 -> "雷雨伴冰雹"
    else -> "天气现象未识别（WMO代码 $code）"
}
