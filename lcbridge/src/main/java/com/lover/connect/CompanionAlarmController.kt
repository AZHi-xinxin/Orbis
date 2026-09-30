package com.lover.connect

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Android side effects are behind this seam; reading never restores or schedules anything. */
interface CompanionAlarmGateway {
    fun schedule(record: CompanionAlarmRecord)
    fun cancel(hour: Int, minute: Int): Boolean
    fun tokenExists(record: CompanionAlarmRecord): Boolean
    fun health(): JSONObject
}

class CompanionAlarmController(
    private val store: CompanionAlarmStore,
    private val gateway: CompanionAlarmGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private fun latest(records: List<CompanionAlarmRecord>) = records.associateBy { it.hour * 100 + it.minute }
    private fun ok() = JSONObject().put("ok", true).put("outcome", "completed")
    private fun failure(code: String, outcome: String) = JSONObject().put("ok", false)
        .put("outcome", outcome).put("error", JSONObject().put("code", code))

    fun set(hour: Int, minute: Int, message: String): JSONObject = synchronized(CompanionAlarmStore.LOCK) {
        require(hour in 0..23 && minute in 0..59 && message.length <= 4096)
        val now = clock()
        val zoneId = zone()
        val localNow = Instant.ofEpochMilli(now).atZone(zoneId)
        var target = localNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.toInstant().isAfter(localNow.toInstant())) target = target.plusDays(1)
        val previous = latest(store.records())[hour * 100 + minute]
        val record = CompanionAlarmRecord(UUID.randomUUID().toString(), hour, minute, message,
            target.toInstant().toEpochMilli(), zoneId.id, now, now, "scheduling")
        // Persist intent first. An interrupted write/schedule is unknown, never automatically retried.
        store.put(record)
        try {
            gateway.schedule(record)
        } catch (_: Exception) {
            runCatching { store.update(record.id, "schedule_unknown", clock(), "schedule_call_failed") }
            return@synchronized failure("alarm_schedule_unconfirmed", "unknown")
                .put("alarm_id", record.id).put("message", "调度结果不确定，请先查询，不要自动重试。")
        }
        try {
            store.update(record.id, "scheduled", clock(), "system_api_accepted")
            if (previous != null && previous.status in setOf("scheduled", "scheduling", "schedule_unknown", "cancel_unknown"))
                store.update(previous.id, "superseded", clock(), "replaced_same_time")
        } catch (_: Exception) {
            return@synchronized failure("alarm_receipt_not_saved", "unknown").put("alarm_id", record.id)
        }
        ok().put("alarm", record.copy(status = "scheduled").toJson())
            .put("next_trigger_at", target.toString()).put("next_trigger_at_ms", record.triggerAt)
            .put("day", if (target.toLocalDate() == localNow.toLocalDate()) "今天" else "明天")
            .put("replaces_same_time", previous?.status in setOf("scheduled", "scheduling", "schedule_unknown", "cancel_unknown"))
            .put("previous_tracked_alarm_id", previous?.id ?: JSONObject.NULL)
            .put("replacement_rule", "同一小时和分钟替换，与备注是否相同无关；旧版未登记的同时间项无法核验。")
            .put("message", "系统已接受一次性闹钟安排；这不是实际响铃或用户听到的证明。")
    }

    fun cancel(hour: Int, minute: Int): JSONObject = synchronized(CompanionAlarmStore.LOCK) {
        require(hour in 0..23 && minute in 0..59)
        val record = latest(store.records())[hour * 100 + minute]
        val cancelsPendingPlan = record?.status in setOf("scheduled", "scheduling", "schedule_unknown", "cancel_unknown")
        // This durable tombstone prevents recovery from resurrecting a cancelled plan after a crash.
        if (record != null && cancelsPendingPlan) store.update(record.id, "cancel_unknown", clock(), "cancel_requested")
        val found = try { gateway.cancel(hour, minute) } catch (_: Exception) {
            return@synchronized failure("alarm_cancel_unconfirmed", "unknown")
        }
        if (record != null && cancelsPendingPlan) {
            try { store.update(record.id, "cancelled", clock(), "schedule_cancelled") }
            catch (_: Exception) { return@synchronized failure("alarm_cancel_receipt_not_saved", "unknown") }
        }
        ok().put("alarm_id", record?.id ?: JSONObject.NULL).put("tracked_record_found", record != null)
            .put("scheduled_for", record?.let(::format) ?: JSONObject.NULL)
            .put("trigger_at_ms", record?.triggerAt ?: JSONObject.NULL)
            .put("pending_token_found", found).put("hour", hour).put("minute", minute)
            .put("message", if (record == null && !found) "未找到该时间的 Orbis 登记或待处理令牌；不代表系统时钟没有闹钟。"
                else "已撤销该时间的未来调度；不停止已开始的响铃，响铃通知中可关闭。")
    }

    fun query(includeHistory: Boolean = true, limit: Int = 30, offset: Int = 0): JSONObject = synchronized(CompanionAlarmStore.LOCK) {
        require(limit in 1..100 && offset in 0..10000)
        val now = clock()
        val all = store.records()
        val heads = latest(all)
        val health = gateway.health()
        val rows = all.map { record ->
            val isLatest = heads[record.hour * 100 + record.minute]?.id == record.id
            val pending = isLatest && record.status == "scheduled"
            val token = if (pending && record.triggerAt > now) gateway.tokenExists(record) else false
            val state = when {
                !isLatest && record.status in setOf("scheduled", "scheduling", "schedule_unknown", "cancel_unknown") -> "superseded"
                pending && record.triggerAt <= now -> "overdue_unconfirmed"
                pending && !health.optBoolean("can_schedule_exact", true) -> "permission_blocked"
                pending && !token -> "registration_missing"
                pending && record.detail == "restore_not_confirmed" -> "restore_unconfirmed"
                pending -> "scheduled_unverified"
                record.status == "scheduling" -> "schedule_unknown"
                record.status == "ringing" && health.optString("live_playback_alarm_id") != record.id -> "playback_started_unconfirmed"
                else -> record.status
            }
            record.toJson().put("state", state).put("is_latest_for_time", isLatest)
                .put("scheduled_for", format(record))
                .put("requested_enabled", pending).put("enabled", state == "scheduled_unverified")
                .put("next_trigger_at", if (state == "scheduled_unverified") format(record) else JSONObject.NULL)
                .put("pending_token_exists", token).put("system_queue_verified", false)
        }
        val active = rows.filter { it.getString("state") in setOf("scheduled_unverified", "registration_missing", "permission_blocked", "restore_unconfirmed", "schedule_unknown", "cancel_unknown", "ringing") }
            .sortedBy { it.getLong("trigger_at_ms") }
        val history = rows.filter { it !in active }.sortedByDescending { it.getLong("updated_at_ms") }
        val available = active + if (includeHistory) history else emptyList()
        val selected = available.drop(offset).take(limit)
        val hasMore = offset + selected.size < available.size
        ok().put("scope", "orbis_companion_alarm_ledger").put("read_only", true)
            .put("observed_at_ms", now).put("time_zone", zone().id).put("device", health)
            .put("active_count", active.size).put("history_count", history.size)
            .put("returned_count", selected.size).put("has_more", hasMore)
            .put("offset", offset).put("next_offset", if (hasMore) offset + selected.size else JSONObject.NULL)
            .put("alarms", JSONArray(selected))
            .put("next_trigger_at", active.firstOrNull { it.getString("state") == "scheduled_unverified" }?.opt("next_trigger_at") ?: JSONObject.NULL)
            .put("coverage", "只查询升级后由 Orbis 陪伴工具登记的闹钟及有限近期回执；旧版未登记项、系统时钟及其他应用的闹钟无法完整列出。空列表不等于手机没有闹钟。")
            .put("status_note", "enabled 仅表示本应用计划启用且当前未发现失效条件；PendingIntent 存在不证明系统队列仍有该闹钟。fired 是收到触发广播，ringing 是播放器启动回执，都不能证明用户听到了。强行停止期间不能响铃或自恢复，重新打开应用后才尝试恢复未来的已确认计划。")
    }

    /** Explicit lifecycle operation, never invoked from query. No past alarm is shifted or replayed. */
    fun restore(): Unit = synchronized(CompanionAlarmStore.LOCK) {
        val now = clock()
        val records = store.records()
        val livePlayback = gateway.health().optString("live_playback_alarm_id")
        records.filter { it.status == "ringing" && it.id != livePlayback }.forEach {
            store.update(it.id, "stopped", now, "playback_not_live_on_restore")
        }
        latest(records).values.filter { it.status == "scheduled" }.forEach { record ->
            if (record.triggerAt <= now) {
                store.update(record.id, "missed_unconfirmed", now, "past_due_on_restore")
            } else {
                try {
                    gateway.schedule(record)
                    store.update(record.id, "scheduled", now, "restored_future_plan")
                } catch (_: Exception) {
                    // Keep the original confirmed intention, so a later permitted startup may recover it.
                    store.update(record.id, "scheduled", now, "restore_not_confirmed")
                }
            }
        }
    }

    /** Reject stale broadcasts after replacement/cancellation; fired is not proof of playback. */
    fun receive(id: String): CompanionAlarmRecord? = synchronized(CompanionAlarmStore.LOCK) {
        val all = store.records()
        val record = all.firstOrNull { it.id == id } ?: return@synchronized null
        if (latest(all)[record.hour * 100 + record.minute]?.id != id ||
            record.status !in setOf("scheduled", "scheduling", "schedule_unknown", "missed_unconfirmed")) return@synchronized null
        // UPDATE_CURRENT may have replaced extras before a failed setExact call. An old queue
        // entry must not use that new generation to ring a future (e.g. tomorrow's) plan early.
        if (record.triggerAt > clock() + 1_000) return@synchronized null
        store.update(id, "fired", clock(), "receiver_received")
    }

    private fun format(record: CompanionAlarmRecord) = Instant.ofEpochMilli(record.triggerAt).atZone(ZoneId.of(record.timeZone)).toString()
}
