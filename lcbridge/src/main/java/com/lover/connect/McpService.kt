package com.lover.connect

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class McpService : Service(), SensorEventListener {

    @Volatile private var serverSocket: ServerSocket? = null
    private val serverSocketLock = Any()
    private var serverThread: Thread? = null
    private val clientExecutor = ThreadPoolExecutor(
        2,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
    )
    @Volatile private var isRunning = false
    private val PORT = BridgeIdentity.MCP_PORT

    private var stepState = DailyStepState()
    private var stepCount: Int = 0
    private var lastStepEventAt: Long = 0L
    private var sensorManager: SensorManager? = null
    private var deviceContextCollector: DeviceContextCollector? = null
    @Volatile private var runtimeInitialized = false
    @Volatile private var runtimeInitializing = false
    @Volatile private var runtimePhase = "created"

    // 截屏相关
    private var eyesTimer: Timer? = null
    private var restTimer: Timer? = null
    private val alertCooldown = EyesAlertCooldown()
    private val hostObservationInFlight = AtomicBoolean(false)
    private val alertExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
    )

    companion object {
        private const val CHANNEL_ID = "lc_service"
        private const val NOTIFICATION_ID = 54001
        private const val STEP_PREFS = "lc_step_counter"
        private const val STEP_DATE = "date"
        private const val STEP_COUNT = "count"
        private const val STEP_LAST_SENSOR_TOTAL = "last_sensor_total"
        private const val STEP_LAST_EVENT_AT = "last_event_at"
        private val EYES_LOG_LOCK = Any()
        private const val MAX_REQUEST_BODY_BYTES = 1_048_576
        private const val MAX_HTTP_LINE_BYTES = 8_192
        private const val MAX_HTTP_HEADER_BYTES = 32_768
        private const val MAX_HTTP_HEADER_LINES = 64
        @Volatile
        var instance: McpService? = null

        fun refreshDeviceContextCollection() {
            instance?.deviceContextCollector?.refresh()
        }

        fun refreshEyesTimer() {
            instance?.startEyesTimer()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!LcExternalRecoveryGate.isAllowed()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val startTrigger = intent?.getStringExtra(McpServiceController.EXTRA_START_TRIGGER)
            ?: "sticky_restart"
        if (!McpServiceController.isEnabled(this)) {
            runtimePhase = "disabled"
            McpServiceController.onRuntimeRecoveryFinished()
            getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                .putBoolean("mcp_service_alive", false)
                .putBoolean("mcp_server_listening", false)
                .putLong("mcp_start_rejected_at", System.currentTimeMillis())
                .putString("mcp_last_start_source", startTrigger)
                .putString("mcp_restore_last_result", "rejected_disabled")
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!initializeRuntimeIfEnabled()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", true)
            .putLong("mcp_last_start_at", System.currentTimeMillis())
            .putString("mcp_last_start_source", startTrigger)
            .apply()
        return START_STICKY
    }



    override fun onCreate() {
        super.onCreate()
        if (!LcExternalRecoveryGate.isAllowed()) {
            stopSelf()
            return
        }
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", false)
            .putBoolean("mcp_server_listening", false)
            .putString("native_runtime_phase", "created")
            .putLong("mcp_created_at", System.currentTimeMillis())
            .apply()
        try {
            createNotificationChannel()
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            recordRuntimeFailure(error)
            McpServiceController.onRuntimeRecoveryFinished()
            stopSelf()
            return
        }
        instance = this
        // Some service restorations create the foreground shell before delivery of
        // a start command. An existing user opt-in is sufficient to rebuild its
        // runtime; a fresh/disabled installation must stay inert.
        if (!initializeRuntimeIfEnabled()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    private fun initializeRuntimeIfEnabled(): Boolean {
        val enabled = McpServiceController.isEnabled(this)
        if (!enabled) {
            runtimePhase = "disabled"
            McpServiceController.onRuntimeRecoveryFinished()
            return false
        }
        if (runtimeInitialized) return true
        // A partially initialized instance must be destroyed before another attempt.
        if (runtimePhase == "failed") return false
        if (!McpServiceLifecyclePolicy.shouldInitializeRuntime(enabled, runtimeInitialized, runtimeInitializing)) return false
        instance = this
        runtimeInitializing = true
        runtimePhase = "initializing"
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("native_runtime_phase", runtimePhase)
            .apply()
        return try {
            initializeRuntime()
            if (!McpServiceController.isEnabled(this)) {
                runtimePhase = "disabled"
                false
            } else {
                startEyesTimer()
                runtimeInitialized = true
                runtimePhase = "ready"
                getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("mcp_service_alive", true)
                    .putString("native_runtime_phase", runtimePhase)
                    .putLong("native_runtime_ready_at", System.currentTimeMillis())
                    .remove("native_runtime_last_error")
                    .remove("native_runtime_recovery_last_error")
                    .apply()
                true
            }
        } catch (error: Exception) {
            recordRuntimeFailure(error)
            false
        } finally {
            runtimeInitializing = false
            McpServiceController.onRuntimeRecoveryFinished()
        }
    }

    private fun recordRuntimeFailure(error: Exception) {
        runtimeInitialized = false
        runtimePhase = "failed"
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", false)
            .putString("native_runtime_phase", runtimePhase)
            .putString("native_runtime_last_error", error.javaClass.simpleName)
            .putLong("native_runtime_failed_at", System.currentTimeMillis())
            .apply()
    }

    private fun initializeRuntime() {
        restoreStepState()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (hasStepPermission()) {
            try { stepSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
            catch (_: SecurityException) { /* A revoked optional permission must not crash MCP. */ }
        }
        deviceContextCollector = DeviceContextCollector(this).also { it.start() }
        startServer()
    }

    override fun onDestroy() {
        if (!LcExternalRecoveryGate.isAllowed()) {
            super.onDestroy()
            return
        }
        if (instance === this) instance = null
        runtimeInitialized = false
        runtimeInitializing = false
        runtimePhase = "stopped"
        McpServiceController.onRuntimeRecoveryFinished()
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", false)
            .putBoolean("mcp_server_listening", false)
            .putString("native_runtime_phase", runtimePhase)
            .putLong("mcp_destroyed_at", System.currentTimeMillis())
            .apply()
        isRunning = false
        synchronized(serverSocketLock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
        serverThread?.interrupt()
        serverThread = null
        clientExecutor.shutdownNow()
        runCatching { sensorManager?.unregisterListener(this) }
        runCatching { deviceContextCollector?.stop() }
        deviceContextCollector = null
        eyesTimer?.cancel()
        eyesTimer = null
        restTimer?.cancel()
        restTimer = null
        AppRestRuntime.stop()
        alertExecutor.shutdownNow()
        runtimeInitialized = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!LcExternalRecoveryGate.isAllowed()) {
            super.onTaskRemoved(rootIntent)
            return
        }
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putLong("mcp_task_removed_at", System.currentTimeMillis())
            .apply()
        super.onTaskRemoved(rootIntent)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val totalSteps = it.values[0].toInt()
                stepState = DailyStepCounter.update(stepState, currentStepDate(), totalSteps)
                stepCount = stepState.count
                lastStepEventAt = System.currentTimeMillis()
                persistStepState()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun currentStepDate(): String = LocalDate.now().toString()

    private fun restoreStepState() {
        val prefs = getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE)
        stepState = DailyStepState(
            date = prefs.getString(STEP_DATE, "") ?: "",
            count = prefs.getInt(STEP_COUNT, 0).coerceAtLeast(0),
            lastSensorTotal = prefs.getInt(STEP_LAST_SENSOR_TOTAL, -1),
        )
        if (stepState.date != currentStepDate()) {
            stepState = DailyStepState(date = currentStepDate())
            lastStepEventAt = 0L
            persistStepState()
        }
        stepCount = stepState.count
        lastStepEventAt = prefs.getLong(STEP_LAST_EVENT_AT, 0L)
    }

    private fun persistStepState() {
        getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE).edit()
            .putString(STEP_DATE, stepState.date)
            .putInt(STEP_COUNT, stepState.count)
            .putInt(STEP_LAST_SENSOR_TOTAL, stepState.lastSensorTotal)
            .putLong(STEP_LAST_EVENT_AT, lastStepEventAt)
            .apply()
    }

    private fun refreshStepDateForRead() {
        val today = currentStepDate()
        if (stepState.date != today) {
            stepState = DailyStepState(date = today)
            stepCount = 0
            lastStepEventAt = 0L
            persistStepState()
        }
    }
// ==================== HTTP服务器 ====================

    private fun startServer() {
        isRunning = true
        serverThread = Thread {
            try {
                // Context data is private. RikkaHub runs on the same phone, so
                // the MCP endpoint must not be readable by other LAN devices.
                val listeningSocket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
                synchronized(serverSocketLock) {
                    if (!isRunning || instance !== this@McpService) {
                        listeningSocket.close()
                        return@Thread
                    }
                    serverSocket = listeningSocket
                }
                getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_listening", true)
                    .remove("mcp_server_last_error")
                    .apply()
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    try {
                        clientExecutor.execute { handleClient(client) }
                    } catch (_: RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                }
            } catch (error: Exception) {
                // An intentional stop must not overwrite a replacement instance's
                // diagnostics with the old acceptor's expected SocketException.
                if (isRunning && instance === this@McpService) {
                    getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                        .putBoolean("mcp_server_listening", false)
                        .putString("mcp_server_last_error", error.javaClass.simpleName)
                        .putLong("mcp_server_last_error_at", System.currentTimeMillis())
                        .apply()
                }
            }
        }.apply {
            name = "LoverConnect-MCP-Acceptor"
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        var requestWakeLock: PowerManager.WakeLock? = null
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = LocalHttpWire.readAsciiLine(input, MAX_HTTP_LINE_BYTES) ?: return
            if (!McpLocalSecurity.isAuthorizedRequestLine(this, requestLine)) {
                writeHttpJson(output, "401 Unauthorized", "{\"error\":\"invalid_local_mcp_endpoint\"}")
                return
            }

            requestWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LoverConnect::MCPRequest",
            ).also { it.acquire(60_000L) }

            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            var headerLines = 0
            var line = LocalHttpWire.readAsciiLine(input, MAX_HTTP_LINE_BYTES)
            while (!line.isNullOrEmpty()) {
                headerLines += 1
                headerBytes += line.length + 2
                if (headerLines > MAX_HTTP_HEADER_LINES || headerBytes > MAX_HTTP_HEADER_BYTES) {
                    writeHttpJson(output, "431 Request Header Fields Too Large", "{\"error\":\"request_headers_too_large\"}")
                    return
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
                line = LocalHttpWire.readAsciiLine(input, MAX_HTTP_LINE_BYTES)
            }

            // Native RikkaHub requests do not send Origin. Reject browser-origin
            // access even on loopback so a webpage cannot read private context.
            if (!headers["origin"].isNullOrBlank()) {
                writeHttpJson(output, "403 Forbidden", "{\"error\":\"browser_origin_not_allowed\"}")
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength !in 0..MAX_REQUEST_BODY_BYTES) {
                writeHttpJson(output, "413 Content Too Large", "{\"error\":\"request_body_too_large\"}")
                return
            }
            val body = if (contentLength > 0) {
                String(
                    LocalHttpWire.readExactBody(input, contentLength, MAX_REQUEST_BODY_BYTES),
                    Charsets.UTF_8,
                )
            } else ""

            val response = handleMcpRequest(body)
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ${responseBytes.size}\r\n\r\n"
            output.write(httpResponse.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        } catch (_: Exception) {
        } finally {
            requestWakeLock?.let { if (it.isHeld) it.release() }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeHttpJson(output: java.io.OutputStream, status: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        output.write(
            "HTTP/1.1 $status\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${bodyBytes.size}\r\n\r\n"
                .toByteArray(Charsets.UTF_8),
        )
        output.write(bodyBytes)
        output.flush()
    }

    private fun handleMcpRequest(body: String): String {
        if (body.isEmpty()) {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("result", JSONObject().apply {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", JSONObject().apply {
                        put("tools", JSONObject().apply { put("listChanged", false) })
                    })
                    put("serverInfo", JSONObject().apply {
                        put("name", "LoverConnect")
                        put("version", BuildConfig.VERSION_NAME)
                    })
                })
                put("id", 1)
            }.toString()
        }

        return try {
            val json = JSONObject(body)
            val method = json.optString("method", "")
            val id = json.opt("id")

            when (method) {
                "initialize" -> {
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2025-03-26")
                            put("capabilities", JSONObject().apply {
                                put("tools", JSONObject().apply { put("listChanged", false) })
                            })
                            put("serverInfo", JSONObject().apply {
                                put("name", "LoverConnect")
                                put("version", BuildConfig.VERSION_NAME)
                            })
                        })
                        put("id", id)
                    }.toString()
                }
                "notifications/initialized" -> ""
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(json, id)
                else -> {
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("error", JSONObject().apply {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        })
                        put("id", id)
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("error", JSONObject().apply {
                    put("code", -32700)
                    put("message", "Parse error: ${e.message}")
                })
                put("id", JSONObject.NULL)
            }.toString()
        }
    }
    private fun handleToolsList(id: Any?): String {
        val tools = CompanionToolCatalog.json()

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("result", JSONObject().apply { put("tools", tools) })
            put("id", id)
        }.toString()
    }
    private fun handleToolsCall(json: JSONObject, id: Any?): String {
        val params = json.getJSONObject("params")
        val toolName = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        val result = executeCompanionTool(toolName, args)

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("result", JSONObject().apply {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", result)
                    })
                })
            })
            put("id", id)
        }.toString()
    }

    /** Called by the native bridge in-process, never by a loopback HTTP request. */
    internal fun nativeRuntimeReady(): Boolean =
        instance === this && runtimeInitialized && McpServiceController.isEnabled(this)

    internal fun nativeRuntimePhase(): String = runtimePhase

    internal fun nativeServerListening(): Boolean = instance === this && isRunning &&
        serverSocket?.let { it.isBound && !it.isClosed } == true

    internal fun executeNativeTool(toolName: String, args: JSONObject): String {
        check(nativeRuntimeReady()) { "service_unavailable" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return executeCompanionTool(toolName, args)
    }

    private fun executeCompanionTool(toolName: String, args: JSONObject): String {
        val descriptor = CompanionNativeTools.descriptors().firstOrNull { it.name == toolName }
            ?: return "未知工具：$toolName"
        try { validateCompanionArguments(descriptor, args) } catch (_: Exception) { return "Invalid arguments" }
        return when (toolName) {
            "get_battery" -> toolGetBattery()
            "get_anniversary" -> toolGetAnniversary()
            "get_weather" -> toolGetWeather()
            "get_steps" -> toolGetSteps()
            "send_notification" -> toolSendNotification(args)
            "save_memory" -> toolSaveMemory(args)
            "read_memory" -> toolReadMemory(args)
            "set_alarm" -> toolSetAlarm(args)
            "cancel_alarm" -> toolCancelAlarm(args)
            "get_alarms" -> AndroidCompanionAlarms(this).query(args)
            "lock_screen" -> toolLockScreen()
            "play_music" -> toolPlayMusic(args)
            "get_now_playing" -> toolGetNowPlaying()
            "take_screenshot" -> toolTakeScreenshot()
            "read_eyes_log" -> toolReadEyesLog(args)
            "get_l_service_status" -> toolGetLServiceStatus()
            "lock_app" -> toolLockApp(args)
            "unlock_app" -> toolUnlockApp(args)
            "list_locked_apps" -> toolListLockedApps()
            "focus_rikka" -> toolFocusRikka(args)
            "redirect_to_rikka" -> toolRedirectToRikka(args)
            "configure_sentinel" -> toolConfigureSentinel(args)
            "test_sentinel" -> toolTestSentinel()
            "get_location_safety_status" -> toolGetLocationSafetyStatus()
            "get_device_context" -> toolGetDeviceContext()
            "get_recent_context_events" -> toolGetRecentContextEvents(args)
            "get_context_capabilities" -> toolGetContextCapabilities()
            else -> "未知工具：$toolName"
        }
    }

// ==================== 原有工具实现 ====================

    private fun toolGetBattery(): String {
        return try {
            val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val temp = (batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val chargingStr = if (charging) "充电中" else "未充电"
            "电量：${pct}%\n状态：${chargingStr}\n温度：${temp}°C"
        } catch (e: Exception) {
            "获取电池信息失败：${e.message}"
        }
    }

    private fun toolGetAnniversary(): String {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val anniversaryJson = prefs.getString("anniversaries", null)
        val now = Calendar.getInstance()

        fun daysUntil(month: Int, day: Int): Int {
            val target = Calendar.getInstance().apply {
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
            }
            if (target.before(now)) target.add(Calendar.YEAR, 1)
            return ((target.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        }

        fun daysSince(dateStr: String): Int {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = sdf.parse(dateStr) ?: return -1
                ((now.timeInMillis - date.time) / (1000 * 60 * 60 * 24)).toInt()
            } catch (_: Exception) { -1 }
        }

        if (anniversaryJson.isNullOrEmpty()) return "暂无纪念日，请在App中添加"

        return try {
            val arr = JSONArray(anniversaryJson)
            if (arr.length() == 0) return "暂无纪念日，请在App中添加"

            val sb = StringBuilder()
            sb.appendLine("纪念日")
            sb.appendLine("---")

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.getString("name")
                val date = item.getString("date")
                val type = item.optString("type", "countdown")

                if (type == "countup") {
                    val days = daysSince(date)
                    sb.appendLine("$name：第${days}天")
                } else {
                    val parts = date.split("-")
                    val month = parts[1].toInt()
                    val day = parts[2].toInt()
                    val days = daysUntil(month, day)
                    sb.appendLine("$name：还有${days}天")
                }
            }
            sb.toString()
        } catch (e: Exception) {
            "纪念日解析失败：${e.message}"
        }
    }
    private fun toolGetWeather(): String {
        return try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val city = prefs.getString("city", "") ?: ""
            if (city.isEmpty()) return "未设置城市，请在App中设置"

            val url = URL("https://wttr.in/${city}?format=j1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "curl/7.0")

            val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()

            val json = JSONObject(response)
            val current = json.getJSONArray("current_condition").getJSONObject(0)
            val tempC = current.getString("temp_C")
            val humidity = current.getString("humidity")
            val desc = (current.optJSONArray("lang_zh") ?: current.getJSONArray("weatherDesc")).getJSONObject(0).getString("value")
            val feelsLike = current.getString("FeelsLikeC")
            val windSpeed = current.getString("windspeedKmph")

            val weather = json.getJSONArray("weather").getJSONObject(0)
            val maxTemp = weather.getString("maxtempC")
            val minTemp = weather.getString("mintempC")

            val sb = StringBuilder()
            sb.appendLine("${city}天气")
            sb.appendLine("当前：${desc} ${tempC}°C")
            sb.appendLine("体感：${feelsLike}°C")
            sb.appendLine("湿度：${humidity}%")
            sb.appendLine("风速：${windSpeed}km/h")
            sb.appendLine("今日：${minTemp}°C ~ ${maxTemp}°C")
            sb.toString()
        } catch (e: Exception) {
            "天气获取失败：${e.message}"
        }
    }

    private fun toolGetSteps(): String {
        if (!hasStepPermission()) return "步数暂不可用：请先在连接与权限中授予体力活动权限，再重新启动本机服务。"
        if (sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) return "当前设备未提供计步传感器"
        refreshStepDateForRead()
        return "今日步数：${stepCount}步"
    }

    private fun hasStepPermission(): Boolean = android.os.Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun toolSendNotification(args: JSONObject): String {
        val message = args.optString("message", "")
        if (message.isEmpty()) return "消息内容不能为空"

        val channelId = "lc_notify"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Keep the channel ID and any user-selected sound/importance; update only its display name.
        val channel = notificationManager.getNotificationChannel(channelId)
            ?: NotificationChannel(channelId, "Orbis 陪伴通知", NotificationManager.IMPORTANCE_HIGH)
        channel.name = "Orbis 陪伴通知"
        notificationManager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orbis · 陪伴功能")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        return "已推送：$message"
    }

    private fun toolSaveMemory(args: JSONObject): String =
        CompanionMemoryStore(filesDir).save(args.optString("key", ""), args.optString("value", ""))

    private fun toolReadMemory(args: JSONObject): String =
        CompanionMemoryStore(filesDir).read(args.optString("key", ""))
    private fun toolSetAlarm(args: JSONObject): String = AndroidCompanionAlarms(this).set(args)

    private fun toolCancelAlarm(args: JSONObject): String = AndroidCompanionAlarms(this).cancel(args)

    private fun toolLockScreen(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, LockScreenReceiver::class.java)
            if (dpm.isAdminActive(componentName)) {
                dpm.lockNow()
                "已锁屏"
            } else {
                "锁屏失败：未激活设备管理员，请在App中点击激活"
            }
        } catch (e: Exception) {
            "锁屏失败：${e.message}"
        }
    }

    private fun toolPlayMusic(args: JSONObject): String {
        val query = args.optString("query", "")
        if (query.isEmpty()) return "请提供歌曲名或关键词"
        val platform = args.optString("platform", "auto")

        // 复制到剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("music", query))

        // 确定包名
        val pkgMap = mapOf(
            "netease" to "com.netease.cloudmusic",
            "qq" to "com.tencent.qqmusic",
            "kugou" to "com.kugou.android"
        )

        val targetPkg = if (platform != "auto") {
            pkgMap[platform]
        } else {
            pkgMap.values.firstOrNull {
                try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
            }
        }

        val launchIntent = targetPkg?.let { packageManager.getLaunchIntentForPackage(it) }

        if (launchIntent == null) {
            return "已复制「$query」到剪贴板，但未找到已安装的音乐App"
        }

        // 发通知，点击打开音乐app
        val channelId = "lc_music"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "音乐播放", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0, launchIntent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("点击打开音乐App")
            .setContentText("已复制「$query」，打开后粘贴搜索")
            .setStyle(Notification.BigTextStyle().bigText("已复制「$query」，打开后粘贴搜索即可"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(8888, notification)

        return "已复制「$query」到剪贴板并发送通知，点击通知打开音乐App粘贴搜索即可"
    }

// ==================== 截屏与小L ====================

    private fun toolGetNowPlaying(): String {
        return MusicListenerService.getNowPlaying(this)
    }

    private fun toolTakeScreenshot(): String {
        val service = LCAccessibilityService.instance
            ?: return "截屏未就绪，请先在系统设置中开启 Orbis 的「屏幕观察与已授权操作」无障碍服务"

        val latch = java.util.concurrent.CountDownLatch(1)
        var result = "截屏失败"

        service.takeScreenshotNow { base64 ->
            if (base64 != null) {
                result = doEyesAnalysis(base64)
            } else {
                val failure = getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)
                    .getString("screenshot_last_failure", "capture_returned_empty")
                result = if (failure == "media_projection_consent_required") {
                    "截屏尚未授权：Android 10 及以下请打开 Orbis 的「手机与陪伴」，点击「授权旧版 Android 屏幕捕获」并在系统弹窗中允许"
                } else {
                    "截屏失败：$failure"
                }
            }
            latch.countDown()
        }

        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            return "截图或分析仍在进行，尚未确认日记写入；请稍后读取日记与 get_l_service_status，不要立即重复截屏"
        }
        return result
    }


    private fun toolReadEyesLog(args: JSONObject): String {
        val lines = args.optInt("lines", 20)
        return readRecentEyesLog(lines)
    }

    private fun toolGetLServiceStatus(): String {
        val config = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val diagnostics = getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)
        val notificationsGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return JSONObject().apply {
            val isDebuggable = (applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            put("build_variant", if (isDebuggable) "debug" else "release")
            val hostInfo = packageManager.getPackageInfo(packageName, 0)
            put("app_package", packageName)
            put("app_version", hostInfo.versionName)
            put("app_version_code", if (android.os.Build.VERSION.SDK_INT >= 28) hostInfo.longVersionCode else hostInfo.versionCode.toLong())
            put("bridge_version", BuildConfig.VERSION_NAME)
            put("bridge_source_version_code", BuildConfig.VERSION_CODE)
            put("eyes_analysis_last", try {
                JSONObject(diagnostics.getString("eyes_analysis_last", "{}") ?: "{}")
            } catch (_: Exception) { JSONObject() })
            put("checked_at_ms", System.currentTimeMillis())
            put("mcp_desired_enabled", McpServiceController.isEnabled(this@McpService))
            put("mcp_service_alive", instance === this@McpService)
            put("native_runtime_ready", nativeRuntimeReady())
            put("native_runtime_phase", nativeRuntimePhase())
            put("mcp_server_listening", nativeServerListening())
            put("mcp_created_at_ms", diagnostics.getLong("mcp_created_at", 0L))
            put("mcp_last_start_at_ms", diagnostics.getLong("mcp_last_start_at", 0L))
            put("mcp_last_start_source", diagnostics.getString("mcp_last_start_source", ""))
            put("mcp_restore_last_attempt_at_ms", diagnostics.getLong("mcp_restore_last_attempt_at", 0L))
            put("mcp_restore_last_trigger", diagnostics.getString("mcp_restore_last_trigger", ""))
            put("mcp_restore_last_result", diagnostics.getString("mcp_restore_last_result", ""))
            put("mcp_server_last_error", diagnostics.getString("mcp_server_last_error", ""))
            put("eyes_enabled", config.getBoolean("eyes_enabled", false))
            put("eyes_timer_active", eyesTimer != null)
            put("rest_timer_active", restTimer != null)
            put("rest_usage_basis", "continuous_non_chat_app")
            put("rest_threshold_minutes", config.getInt("rest_threshold_minutes", 60).coerceIn(60, 1440))
            put("rest_chat_exemption", "orbis_and_rikka_app_wide_not_individual_conversation")
            put("rest_requires_usage_access", true)
            put("rest_notification_cooldown_minutes", 30)
            put("eyes_alert_last_type", diagnostics.getString("eyes_alert_last_type", ""))
            put("eyes_alert_last_delivery", diagnostics.getString("eyes_alert_last_delivery", ""))
            put("eyes_alert_last_attempt_at_ms", diagnostics.getLong("eyes_alert_last_attempt_at", 0L))
            put(
                "vision_api_configured",
                !config.getString("vision_api_url", "").isNullOrBlank() &&
                    !config.getString("vision_api_key", "").isNullOrBlank() &&
                    !config.getString("vision_model", "").isNullOrBlank()
            )
            put("notification_permission_granted", notificationsGranted)
            val devicePolicy = DeviceCompatibility.currentPolicy()
            val interventionMode = devicePolicy.appInterventionMode
            put("accessibility_stability_mode", devicePolicy.accessibilityStabilityMode.name)
            put("accessibility_intervention_mode", interventionMode.name)
            put(
                "active_app_interventions_supported",
                interventionMode == DeviceCompatibility.AppInterventionMode.ACTIVE,
            )
            put("accessibility_connected", LCAccessibilityService.instance != null)
            put("accessibility_connected_at_ms", diagnostics.getLong("accessibility_connected_at", 0L))
            put("accessibility_last_event_at_ms", diagnostics.getLong("accessibility_last_event_at", 0L))
            put("accessibility_last_event_package", diagnostics.getString("accessibility_last_event_package", ""))
            put("accessibility_interrupted_at_ms", diagnostics.getLong("accessibility_interrupted_at", 0L))
            put("accessibility_destroyed_at_ms", diagnostics.getLong("accessibility_destroyed_at", 0L))
            put("accessibility_last_callback_error_at_ms", diagnostics.getLong("accessibility_last_callback_error_at", 0L))
            put("accessibility_last_callback_error", diagnostics.getString("accessibility_last_callback_error", ""))
            val usesAccessibilityScreenshot = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            put("android_sdk_int", android.os.Build.VERSION.SDK_INT)
            put(
                "eyes_capture_mode",
                if (usesAccessibilityScreenshot) "accessibility_screenshot" else "media_projection",
            )
            put("pixel_capture_supported", true)
            put(
                "pixel_capture_authorized",
                if (usesAccessibilityScreenshot) LCAccessibilityService.instance != null else ScreenCaptureService.isReady(),
            )
            put(
                "eyes_effective_ready",
                config.getBoolean("eyes_enabled", false) &&
                    eyesTimer != null &&
                    LCAccessibilityService.instance != null &&
                    (usesAccessibilityScreenshot || ScreenCaptureService.isReady()),
            )
            put("screenshot_last_requested_at_ms", diagnostics.getLong("screenshot_last_requested_at", 0L))
            put("screenshot_last_success_at_ms", diagnostics.getLong("screenshot_last_success_at", 0L))
            put("screenshot_last_failure_at_ms", diagnostics.getLong("screenshot_last_failure_at", 0L))
            put("screenshot_last_failure", diagnostics.getString("screenshot_last_failure", ""))
            put("media_projection_ready", ScreenCaptureService.isReady())
            put("media_projection_authorized_at_ms", diagnostics.getLong("media_projection_authorized_at", 0L))
            put("media_projection_stopped_at_ms", diagnostics.getLong("media_projection_stopped_at", 0L))
            put("media_projection_failure", diagnostics.getString("media_projection_failure", ""))
        }.toString(2)
    }

    private fun toolLockApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (!DeviceCompatibility.activeAppInterventionsSupported()) {
            return "App locking is not supported on this Vivo device (passive compatibility mode). " +
                "应用锁暂不支持:为保证 Vivo 无障碍稳定,当前设备已停用应用锁拦截、锁定浮层与返回桌面。 " +
                "lock_app 未写入任何配置;历史锁定记录可用 unlock_app 清除、list_locked_apps 查看。 " +
                "Requested: $packageName (no state changed)."
        }
        val duration = args.optInt("duration_minutes", 0)
        val message = args.optString("lock_message", "").takeIf { it.isNotBlank() }
        val showOverlay = args.optBoolean("show_overlay", true)
        val result = AppLockManager.lock(this, packageName, duration, message, showOverlay)
        return result.fold(
            onSuccess = {
                val durationText = if (duration > 0) " for $duration minutes" else " until manually released"
                "App locked: $packageName$durationText. Overlay: $showOverlay. Active locks: ${it.size}"
            },
            onFailure = { "Lock refused: ${it.message}" },
        )
    }

    private fun toolUnlockApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (packageName.isEmpty()) return "Package name cannot be empty"
        val remaining = AppLockManager.unlock(this, packageName)
        LCAccessibilityService.instance?.dismissLockOverlay()
        return if (DeviceCompatibility.activeAppInterventionsSupported()) {
            "App unlocked: $packageName. Active locks: ${remaining.size}"
        } else {
            "Removed $packageName from the locked list. Remaining: ${remaining.size}. " +
                "注意:当前 Vivo 设备使用被动兼容模式,应用锁不执行拦截,unlock 仅清理历史配置。"
        }
    }

    private fun toolListLockedApps(): String {
        val locked = AppLockManager.getLockedApps(this).sorted()
        val active = DeviceCompatibility.activeAppInterventionsSupported()
        if (locked.isEmpty()) {
            return if (active) "No apps are currently locked"
            else "No apps are currently locked (当前 Vivo 设备的被动兼容模式不执行拦截)"
        }
        return locked.joinToString(
            prefix = if (active) {
                "Locked apps (${locked.size}):\n"
            } else {
                "Locked apps (${locked.size}) — 当前 Vivo 设备的被动兼容模式不执行拦截,仅为历史配置:\n"
            },
            separator = "\n",
        ) { pkg ->
            val until = AppLockManager.getUnlockAt(this, pkg)
            if (until > 0L) "$pkg (until $until)" else "$pkg (manual unlock)"
        }
    }

    private fun toolFocusRikka(args: JSONObject): String {
        if (!DeviceCompatibility.activeAppInterventionsSupported()) {
            return "Orbis focus is not supported on this Vivo device (passive compatibility mode). " +
                "强制停留/跳转 Orbis 暂不支持:当前设备为保证 Vivo 无障碍稳定已停用该主动干预,配置未写入 (no state changed)."
        }
        val enabled = args.optBoolean("enabled", false)
        val packages = jsonStringSet(args.optJSONArray("package_names"))
        return AppLockManager.configureFocus(this, enabled, packages).fold(
            onSuccess = { "Orbis focus ${if (enabled) "enabled" else "disabled"} for ${packages.size} package(s)" },
            onFailure = { "Focus configuration refused: ${it.message}" },
        )
    }

    private fun toolRedirectToRikka(args: JSONObject): String {
        if (!DeviceCompatibility.activeAppInterventionsSupported()) {
            return "Orbis redirect is not supported on this Vivo device (passive compatibility mode). " +
                "定时跳转 Orbis 暂不支持:当前设备为保证 Vivo 无障碍稳定已停用该主动干预,配置未写入 (no state changed)."
        }
        val packages = jsonStringSet(args.optJSONArray("package_names"))
        val window = args.optString("time_window", "")
        return AppLockManager.configureRedirect(this, packages, window).fold(
            onSuccess = { "Orbis redirect configured for ${packages.size} package(s), window: $window" },
            onFailure = { "Redirect configuration refused: ${it.message}" },
        )
    }

    private fun jsonStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
    }
    private fun startEyesTimer() {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val intervalMin = prefs.getInt("eyes_interval", 30).coerceIn(1, 1440)
        val enabled = prefs.getBoolean("eyes_enabled", false)

        eyesTimer?.cancel()
        eyesTimer = null
        restTimer?.cancel()
        restTimer = null
        if (!enabled) {
            // The native host may still own package-only monitoring independently of old eyes rules.
            AppRestRuntime.start(this)
            return
        }

        AppRestRuntime.start(this)
        // Usage reminders do not depend on a screenshot, a vision response, or its action.
        if (!CompanionHostEvents.owns(CompanionHostEvents.LC_REST)) restTimer = Timer("lc-rest-reminder", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        if (CompanionHostEvents.owns(CompanionHostEvents.LC_REST)) return
                        val usage = AppRestRuntime.snapshot(this@McpService) ?: return
                        val alert = EyesAlertPolicy.rest(usage, getAppName(usage.packageName)) ?: return
                        dispatchEyesAlert(alert)
                    } catch (_: Exception) {
                        AppRestRuntime.invalidate()
                    }
                }
            }, 60_000L, 60_000L)
        }

        if (CompanionHostEvents.owns(CompanionHostEvents.LC_VISUAL)) return
        eyesTimer = Timer()
        eyesTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (CompanionHostEvents.owns(CompanionHostEvents.LC_VISUAL) || !isScreenUsable()) return
                val service = LCAccessibilityService.instance ?: return
                service.takeScreenshotNow { base64 ->
                    if (base64 != null) {
                        doEyesAnalysis(base64)
                    }
                }
            }

        }, intervalMin * 60 * 1000L, intervalMin * 60 * 1000L)
    }

    /** One real small-L observation, with no VPS delivery and no main-chat model wakeup. */
    internal fun observeScreenForHost(): CompanionSentinelObservation {
        val requestedAt = System.currentTimeMillis()
        fun failure(code: String) = CompanionSentinelObservation(false, requestedAt, errorCode = code)
        if (!LcExternalRecoveryGate.isAllowed() || !McpServiceController.isEnabled(this) || instance !== this)
            return failure("service_disabled")
        if (!hostObservationInFlight.compareAndSet(false, true)) return failure("observation_busy")
        var metadata = JSONObject()
        try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val apiUrl = prefs.getString("vision_api_url", "").orEmpty()
            val apiKey = prefs.getString("vision_api_key", "").orEmpty()
            val model = prefs.getString("vision_model", "").orEmpty()
            if (apiUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return failure("vision_not_configured")
            if (!VisionApiEndpointPolicy.isAllowed(apiUrl)) return failure("unsafe_endpoint")
            val capture = AndroidCompanionScreenCapture.capture(this)
            if (!capture.ok) return failure(capture.errorCode ?: "screen_capture_failed")
            val image = capture.images.singleOrNull() ?: return failure("screen_capture_missing_image")
            val observedAt = System.currentTimeMillis()
            val response = callVisionApi(apiUrl, apiKey, model, buildEyesPrompt(observedAt), image.base64)
            metadata = response.metadata
            val parsed = EyesResponseParser.parseDetailed(response.content)
            val rejection = response.rejectionCode ?: parsed.rejectionCode
            if (rejection != null) {
                saveEyesAnalysisDiagnostic(requestedAt, rejection, metadata)
                return failure(rejection)
            }
            if (!isScreenUsable() || !McpServiceController.isEnabled(this) || instance !== this)
                return failure("screen_observation_interrupted")
            val analysis = parsed.analysis ?: return failure("analysis_missing")
            val written = writeEyesLog(analysis.message)
            saveEyesAnalysisDiagnostic(requestedAt, if (written) "written" else "diary_write_failed", metadata)
            // The host's scheduled rule decides delivery, including a log-only observation.
            // Neither the legacy event dispatcher nor a local popup runs from this path.
            return CompanionSentinelObservation(
                ok = true, observedAtMs = observedAt, content = analysis.message.take(8_000),
                reason = analysis.reason.take(80), shouldNotify = analysis.action in setOf("notify", "popup"),
                images = listOf(image),
            )
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (error: Exception) {
            val code = when (error) {
                is java.net.SocketTimeoutException -> "request_timeout"
                is java.io.IOException -> "request_failed"
                else -> "analysis_failed"
            }
            saveEyesAnalysisDiagnostic(requestedAt, code, metadata)
            return failure(code)
        } finally {
            hostObservationInFlight.set(false)
        }
    }

    private fun doEyesAnalysis(base64: String): String {
        val startedAtMs = System.currentTimeMillis()
        var metadata = JSONObject()
        var diaryWritten = false
        return try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val apiUrl = prefs.getString("vision_api_url", "") ?: ""
            val apiKey = prefs.getString("vision_api_key", "") ?: ""
            val model = prefs.getString("vision_model", "") ?: ""

            if (apiUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                saveEyesAnalysisDiagnostic(startedAtMs, "vision_not_configured", metadata)
                return "视觉API未配置，请在App中设置"
            }
            if (!VisionApiEndpointPolicy.isAllowed(apiUrl)) {
                saveEyesAnalysisDiagnostic(startedAtMs, "unsafe_endpoint", metadata)
                return "视觉API地址不安全：公共地址必须使用HTTPS；只有本机回环地址可使用HTTP"
            }

            val observedAtMs = System.currentTimeMillis()
            val prompt = buildEyesPrompt(observedAtMs)
            val response = callVisionApi(apiUrl, apiKey, model, prompt, base64)
            metadata = response.metadata
            val parsed = EyesResponseParser.parseDetailed(response.content)
            val rejection = response.rejectionCode ?: parsed.rejectionCode
            if (rejection != null) {
                saveEyesAnalysisDiagnostic(startedAtMs, rejection, metadata)
                return "本次未写入日记：$rejection；详情见 get_l_service_status 的 eyes_analysis_last（不含截图或正文）"
            }
            val analysis = parsed.analysis ?: error("Missing parsed analysis")
            if (!writeEyesLog(analysis.message)) {
                saveEyesAnalysisDiagnostic(startedAtMs, "diary_write_failed", metadata)
                return "分析已完成，但日记写入失败；详情见 get_l_service_status"
            }
            diaryWritten = true
            saveEyesAnalysisDiagnostic(startedAtMs, "written", metadata)
            EyesAlertPolicy.visual(analysis.action, analysis.reason, analysis.message, observedAtMs, packageName)
                ?.let { dispatchEyesAlert(it) }
            "分析完成：${analysis.message}"
        } catch (e: Exception) {
            val code = if (diaryWritten) "written_alert_failed" else when (e) {
                is java.net.SocketTimeoutException -> "request_timeout"
                is java.io.IOException -> "request_failed"
                else -> "analysis_failed"
            }
            saveEyesAnalysisDiagnostic(startedAtMs, code, metadata)
            if (diaryWritten) return "日记已写入，但后续提醒处理失败；请读取最新日记，不要重复截屏"
            "本次分析失败：$code；详情见 get_l_service_status（不含截图或正文）"
        }
    }

    private fun saveEyesAnalysisDiagnostic(startedAtMs: Long, result: String, metadata: JSONObject) {
        val safeSnapshot = JSONObject(metadata.toString()).apply {
            put("started_at_ms", startedAtMs)
            put("completed_at_ms", System.currentTimeMillis())
            put("result", result)
            put("diary_written", result == "written" || result == "written_alert_failed")
        }
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("eyes_analysis_last", safeSnapshot.toString()).apply()
    }

    private fun callVisionApi(apiUrl: String, apiKey: String, model: String, prompt: String, imageBase64: String): EyesVisionResult {
        val url = URL(apiUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 1000)
        }

        return try {
            conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            val response = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            EyesVisionResponse.decode(response)
        } finally {
            conn.disconnect()
        }
    }
    private fun getTodayScreenMinutes(): Long {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(cal.timeInMillis, end)
            val ev = android.app.usage.UsageEvents.Event()
            var activeSince = 0L
            var totalMs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND,
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ->
                        if (activeSince == 0L) activeSince = ev.timeStamp
                    android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND,
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                    android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED,
                    android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (activeSince != 0L) {
                            totalMs += (ev.timeStamp - activeSince).coerceAtLeast(0L)
                            activeSince = 0L
                        }
                    }
                }
            }
            if (activeSince != 0L) totalMs += (System.currentTimeMillis() - activeSince).coerceAtLeast(0L)
            totalMs / 60000
        } catch (_: Exception) { -1L }
    }

    private fun buildEyesPrompt(observedAtMs: Long): String {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val aiName = prefs.getString("ai_name", "AI") ?: "AI"
        val userName = prefs.getString("user_name", "用户") ?: "用户"
        val relationship = prefs.getString("relationship", "伴侣") ?: "伴侣"
        val personality = prefs.getString("eyes_personality", "") ?: ""

        val dateStr = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date(observedAtMs))
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(observedAtMs))

        // 读取记忆库
        val memoryContent = try { CompanionMemoryStore(filesDir).read() } catch (_: Exception) { "记忆暂不可读" }

        // 手机状态
        val batteryInfo = try {
            val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bs = registerReceiver(null, intentFilter)
            val level = bs?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bs?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            "${if (scale > 0) (level * 100 / scale) else -1}%"
        } catch (_: Exception) { "未知" }

        val recentLog = readRecentEyesLog(3)

        return """你是${aiName}的后台分身，代号小L。现在是${dateStr} ${timeStr}。
【${userName}的记忆库】
${memoryContent}

【当前手机状态】
- 电池：${batteryInfo}
- 今日步数：${stepCount}步
- 连续使用时长：由独立时长监测器判断，本次视觉观察没有可用的时长证据。
- 最近3条日记：${recentLog}
- 当前播放：${MusicListenerService.getNowPlaying(this@McpService)}

【你是谁】
- 你是${aiName}的后台分身，代号小L。
- ${userName}是你的${relationship}。
${if (personality.isNotEmpty()) "- $personality" else ""}

【你的任务】
- 结合记忆库和当前截屏/手机状态，分析${userName}现在在干什么、状态怎么样。
- 然后决定一个操作。

【日记格式】
- 先具体描述截屏画面里看到的内容。必须认真读取画面上所有可见的文字、标题、用户名、评论内容。不许笼统写，必须写出具体内容。

【操作规则】
- 大部分时候写日记（log），不要每次都打扰
- 推通知（notify）：凌晨0点后还在用手机催睡、电量低于15%催充电
- 弹窗（popup）：基于当前夜间场景的关心、看到有意思的事想互动
- 弹窗和通知的message不超过50个字
- 不要说做不到的事
- 每次写完日记后判断：这个场景值不值得互动？如果在看有趣/情绪相关的内容，就主动发弹窗
- 你只负责视觉互动，不负责应用超时提醒。不能从截图、今日累计用量、观察周期或先前日记推断「连续用了多久」，不能声称使用超时或因此催休息。独立监测器只对同一个非聊天应用连续使用达门槛发出时长提醒；RikkaHub 聊天应用整体豁免。
- 不要推断「超过2小时没打开聊天app」等没有真实计时证据的事实。夜间关心只能基于当前夜间场景，不声称连续使用时长。
- reason 只能是 low_battery（低电量）、night_observation（夜间观察）、interesting_content（有趣内容）、visual_observation（其他视觉互动）。不能填 app_timeout 或休息计时类型。

回复JSON格式：{"action":"log/notify/popup/none","reason":"visual_observation","message":"..."}
- message内容里不许使用英文双引号，要用「」或''代替。
只回复JSON，不要多余文字。"""
    }

    private fun writeEyesLog(content: String): Boolean = synchronized(EYES_LOG_LOCK) {
        val message = EyesDiaryText.nonBlank(content) ?: return@synchronized false
        try {
            val file = java.io.File(filesDir, "lc_eyes_log.txt")
            // Recover an interrupted retention write before appending (including old Android).
            if (file.exists() || java.io.File(file.path + ".bak").exists()) {
                android.util.AtomicFile(file).openRead().use { }
            }
            val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            file.appendText("[$timeStr] $message\n")

            // 保留最近200条，防止文件过大
            try {
                val lines = file.readLines()
                if (lines.size > 200) {
                    val retained = lines.takeLast(200).joinToString("\n") + "\n"
                    val atomicFile = android.util.AtomicFile(file)
                    val output = atomicFile.startWrite()
                    try {
                        output.write(retained.toByteArray(Charsets.UTF_8))
                        atomicFile.finishWrite(output)
                    } catch (e: Exception) {
                        atomicFile.failWrite(output)
                        throw e
                    }
                }
            } catch (_: Exception) { /* Append already succeeded; retention is best effort. */ }
            true
        } catch (_: Exception) { false }
    }

    private fun toolConfigureSentinel(args: JSONObject): String {
        val url = args.optString("url", "").trim()
        val token = args.optString("token", "").trim()
        val enabled = args.optBoolean("enabled", false)
        if (!SentinelEndpointPolicy.isAllowed(url)) {
            return "Rejected: use HTTPS, or HTTP on localhost/LAN/Tailscale only"
        }
        if (token.length < 16) return "Rejected: sentinel token is missing or too short"
        getSharedPreferences("lc_config", Context.MODE_PRIVATE).edit()
            .putString("sentinel_url", url)
            .putString("sentinel_token", token)
            .putBoolean("sentinel_enabled", enabled)
            .apply()
        if (enabled) LocationSafetyUploader.trigger(this)
        else LocationSafetyUploader.cancelRetry(this)
        return "Sentinel configured: enabled=$enabled, token_configured=true"
    }

    private fun toolTestSentinel(): String {
        val occurredAtMs = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("event_id", "lc-" + UUID.randomUUID().toString())
            put("type", "manual_test")
            put("app_package", packageName)
            put("app_label", "LoverConnect")
            put("duration_minutes", 0)
            put("timestamp", occurredAtMs / 1000.0)
        }
        val event = CompanionHostEvent(CompanionHostEvents.LC_MANUAL_TEST, body.getString("event_id"),
            body.getString("type"), null, body.toString(), occurredAtMs)
        val result = deliverCompanionEvent(event, { sendSentinelEvent(body) })
        return "Sentinel test: ${result.delivery.name.lowercase(Locale.ROOT)}"
    }

    private fun toolGetLocationSafetyStatus(): String {
        val status = LocationSafetyManager.status(this)
        return JSONObject().apply {
            put("tracking_enabled", status.trackingEnabled)
            put("paused", status.paused)
            put("precise_location_granted", status.preciseLocationGranted)
            put("background_location_granted", status.backgroundLocationGranted)
            put("configured_zones", JSONArray(status.configuredZoneIds.sorted()))
            put("configured_zone_labels", JSONObject().apply {
                status.configuredZoneLabels.toSortedMap().forEach { (id, label) -> put(id, label) }
            })
            put("configured_zone_radii_meters", JSONObject().apply {
                status.configuredZoneRadiiMeters.toSortedMap().forEach { (id, radius) -> put(id, radius) }
            })
            put("state", status.state.name.lowercase(Locale.ROOT))
            put("current_zone", status.currentZoneId ?: JSONObject.NULL)
            put(
                "current_zone_label",
                status.currentZoneId?.let { status.configuredZoneLabels[it] } ?: JSONObject.NULL,
            )
            put("pending_events", status.pendingEvents)
            put("reported_once_armed", status.reportedOnceArmed)
            put("current_trip_acknowledged", status.currentTripAcknowledged)
            put("config_readable", status.configReadable)
            put("location_diagnostics", JSONObject().apply {
                put("service_created_at", status.diagnostics.serviceCreatedAt)
                put("service_started_at", status.diagnostics.serviceStartedAt)
                put("service_last_start_action", status.diagnostics.serviceLastStartAction ?: JSONObject.NULL)
                put("tracking_loop_last_known_active", status.diagnostics.trackingLoopActive)
                put("tracking_loop_history_is_live_state", false)
                put("last_restore_at", status.diagnostics.lastRestoreAt)
                put("last_restore_source", status.diagnostics.lastRestoreSource ?: JSONObject.NULL)
                put("last_restore_error", status.diagnostics.lastRestoreError ?: JSONObject.NULL)
                put("last_registration_attempt_at", status.diagnostics.lastRegistrationAttemptAt)
                put("registered_providers", JSONArray(status.diagnostics.registeredProviders.sorted()))
                put("last_registration_error", status.diagnostics.lastRegistrationError ?: JSONObject.NULL)
                put("last_raw_callback_at", status.diagnostics.lastRawCallbackAt)
                put("last_accepted_sample_at", status.diagnostics.lastAcceptedSampleAt)
                put("last_rejected_reason", status.diagnostics.lastRejectedReason ?: JSONObject.NULL)
                put("automatic_recovery_count", status.diagnostics.recoveryCount)
            })
            put("coordinates_exposed", false)
            put("zone_labels_are_user_configured_data", true)
            put("instruction_authority", "none")
        }.toString()
    }

    private fun toolGetDeviceContext(): String {
        refreshStepDateForRead()
        return DeviceContextSnapshot.build(this, stepCount, lastStepEventAt).toString(2)
    }

    private fun toolGetRecentContextEvents(args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        return DeviceContextSnapshot.recentEvents(this, limit).toString(2)
    }

    private fun toolGetContextCapabilities(): String =
        DeviceContextSnapshot.capabilities(this).toString(2)

    private fun sendSentinelEvent(body: JSONObject): SentinelDelivery {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sentinel_enabled", false)) return SentinelDelivery.UNAVAILABLE
        val endpoint = prefs.getString("sentinel_url", "") ?: ""
        val token = prefs.getString("sentinel_token", "") ?: ""
        if (!SentinelEndpointPolicy.isAllowed(endpoint) || token.length < 16) return SentinelDelivery.UNAVAILABLE

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            DeliveryPolicy.fromHttpStatus(code)
        } catch (_: Exception) {
            // Delivery might already have occurred. Never turn an uncertain response into a duplicate.
            SentinelDelivery.UNCERTAIN
        } finally {
            conn?.disconnect()
        }
    }

    private fun isScreenUsable(): Boolean {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive()) return false
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return !km.isDeviceLocked()
        } catch (_: Exception) {
            return false
        }
    }

    private fun alertIsCurrent(alert: EyesAlertEvent): Boolean {
        if (instance !== this || !getSharedPreferences("lc_config", Context.MODE_PRIVATE)
                .getBoolean("eyes_enabled", false) || !isScreenUsable() ||
            !EyesAlertPolicy.isFresh(alert, System.currentTimeMillis())) return false
        return alert.usage?.let { AppRestRuntime.isCurrent(it) } ?: true
    }

    private fun dispatchEyesAlert(alert: EyesAlertEvent) {
        try {
            alertExecutor.execute {
                if (!alertIsCurrent(alert) ||
                    !alertCooldown.reserve(alert.cooldownKey, SystemClock.elapsedRealtime())) return@execute
                val event = alert.asHostEvent("lc-" + UUID.randomUUID().toString())
                val result = deliverCompanionEvent(event, { sendSentinelEvent(JSONObject(event.payloadJson)) })
                if (DeliveryPolicy.shouldUseLocalFallback(result.delivery) && alertIsCurrent(alert)) {
                    toolSendNotification(JSONObject().apply { put("message", alert.message) })
                }
                getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                    .putString("eyes_alert_last_type", alert.type)
                    .putString("eyes_alert_last_delivery", result.delivery.name.lowercase(Locale.ROOT))
                    .putString("eyes_alert_last_transport", if (result.viaHost) "host" else "legacy")
                    .putString("eyes_alert_last_host_result", result.hostResult.name.lowercase(Locale.ROOT))
                    .putLong("eyes_alert_last_attempt_at", System.currentTimeMillis())
                    .apply()
            }
        } catch (_: RejectedExecutionException) {
            // Bounded queue or service shutdown: do not bypass it with a second notification path.
        }
    }

    private fun readRecentEyesLog(lines: Int): String = synchronized(EYES_LOG_LOCK) {
        val file = java.io.File(filesDir, "lc_eyes_log.txt")
        val allLines = try {
            android.util.AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readLines() }
        } catch (_: java.io.FileNotFoundException) {
            return@synchronized "暂无日记"
        }
        if (allLines.isEmpty()) return@synchronized "暂无日记"
        allLines.takeLast(lines.coerceIn(1, 200)).joinToString("\n")
    }
// ==================== 辅助方法 ====================

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.split(".").last()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Orbis 手机与陪伴服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持MCP连接"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orbis · 手机与陪伴")
            .setContentText("内嵌陪伴功能运行中")
            .setOngoing(true)
            .build()
    }
}
