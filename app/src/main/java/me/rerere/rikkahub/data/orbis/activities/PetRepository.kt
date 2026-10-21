package me.rerere.rikkahub.data.orbis.activities

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small atomic documents, separate from all conversation and memory stores. */
interface ActivityStorage { fun read(): String?; fun write(text: String) }

@Serializable
data class PetIdentity(val id: String, val name: String, val appearance: String, val adoptedAt: Long)
@Serializable
data class PetEvent(val id: String, val action: String, val at: Long, val message: String, val decoration: String? = null,
    val aiActions: List<String> = emptyList())
@Serializable
data class PetNotice(val eventId: String, val conversationId: String, val at: Long, val text: String,
    val selected: Boolean, val attempted: Boolean = false)
@Serializable
data class PetState(
    val version: Int = 1, val pet: PetIdentity? = null, val revision: Long = 0,
    val food: Double = 100.0, val water: Double = 100.0, val mood: Double = 100.0,
    val health: Double = 100.0, val settledAt: Long = 0, val boarding: Boolean = false,
    val messy: Boolean = false, val decoration: String = "sage", val sleepingUntil: Long = 0,
    val careDays: Set<String> = emptySet(), val translationCredits: Int = 0,
    val events: List<PetEvent> = emptyList(),
    val aiModelId: String? = null, val aiConsent: Boolean = false,
    val aiPending: PetAiTicket? = null, val aiLastAttemptAt: Long = 0,
    val sayings: List<PetSaying> = emptyList(),
    val noticeConversationId: String? = null, val noticesEnabled: Boolean = false,
    val notices: List<PetNotice> = emptyList(), val lastNoticeAt: Long = 0,
    val aiDailyLimit: Int = 30, val aiUsageDay: String = "", val aiUsageCount: Int = 0,
)

object PetRules {
    val actions = setOf("feed", "water", "pet", "play", "tidy", "board", "return", "sleep", "decorate")
    /** Full real elapsed time, including app closure/migration. No background timer or offline cap. */
    fun advance(state: PetState, now: Long): PetState {
        if (state.pet == null || now <= state.settledAt) return state
        val hours = (now.toDouble() - state.settledAt) / 3_600_000.0
        val food = if (state.boarding) maxOf(80.0, state.food) else (state.food - 4 * hours).coerceAtLeast(0.0)
        val water = if (state.boarding) maxOf(80.0, state.water) else (state.water - 5 * hours).coerceAtLeast(0.0)
        val moodRate = if (state.boarding) .5 else 2.0
        val mood = (state.mood - moodRate * hours).coerceAtLeast(0.0)
        val healthyHours = minOf(hours, state.mood / moodRate,
            if (state.boarding) Double.POSITIVE_INFINITY else minOf(state.food / 4, state.water / 5))
        val health = ((state.health + healthyHours * 2).coerceAtMost(100.0) - (hours - healthyHours) * 3).coerceIn(0.0, 100.0)
        return state.copy(food = food, water = water, mood = mood, health = health, settledAt = now)
    }
    fun validate(state: PetState) {
        require(state.version == 1 && state.revision >= 0 && state.settledAt >= 0)
        require(listOf(state.food, state.water, state.mood, state.health).all { it.isFinite() && it in 0.0..100.0 })
        require(state.translationCredits >= 0 && state.events.size <= 1000 && state.careDays.size <= 100_000)
        require(state.events.map { it.id }.distinct().size == state.events.size)
        require(state.aiDailyLimit in 0..1000 && state.aiUsageCount >= 0)
        require(state.aiUsageDay.isEmpty() || runCatching { java.time.LocalDate.parse(state.aiUsageDay) }.isSuccess)
        require(state.events.all { it.aiActions.size <= 3 && it.aiActions.all(PetAiRules.labels::containsKey) })
        require(state.sayings.size <= 50 && state.sayings.map { it.id }.distinct().size == state.sayings.size)
        require(state.aiModelId == null || runCatching { UUID.fromString(state.aiModelId) }.isSuccess)
        require(!state.aiConsent || state.aiModelId != null)
        require(state.noticeConversationId == null || runCatching { UUID.fromString(state.noticeConversationId) }.isSuccess)
        require(state.notices.size <= 20 && state.lastNoticeAt >= 0 && state.notices.map { it.eventId }.distinct().size == state.notices.size)
        state.notices.forEach { require(it.text.length <= 512 && it.at >= 0); UUID.fromString(it.conversationId) }
        state.sayings.forEach { require(it.original.length <= 512 && it.animal.length <= 81 && it.at >= 0) }
        state.aiPending?.let { require(it.id.isNotBlank() && it.modelId == state.aiModelId && it.trigger in PetAiRules.triggers) }
        state.pet?.let { require(it.name.isNotBlank() && it.name.length <= 40 && it.adoptedAt >= 0); PetCatalog.find(it.appearance) }
    }
}

class PetRepository(private val storage: ActivityStorage, private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val noticeCoin: () -> Boolean = { kotlin.random.Random.nextBoolean() }) {
    private val json = Json { encodeDefaults = true }
    private val mutable = MutableStateFlow(storage.read()?.let { json.decodeFromString<PetState>(it).also(PetRules::validate) } ?: PetState())
    val state = mutable.asStateFlow()
    private var blocked = false
    @Synchronized fun reload() {
        val next = storage.read()?.let { json.decodeFromString<PetState>(it).also(PetRules::validate) } ?: PetState()
        mutable.value = next; blocked = false
    }
    @Synchronized fun snapshot(): PetState = PetRules.advance(mutable.value, now())
    @Synchronized fun adopt(name: String, appearance: String = "cat-tabby-v1"): PetState {
        check(mutable.value.pet == null) { "already_adopted" }
        require(name.trim().isNotEmpty() && name.trim().length <= 40 && name.none(Char::isISOControl)) { "invalid_pet_name" }
        val time = now().coerceAtLeast(0)
        PetCatalog.find(appearance)
        return commit(PetState(pet = PetIdentity(UUID.randomUUID().toString(), name.trim(), appearance, time), settledAt = time, revision = 1))
    }
    @Synchronized fun act(action: String, operationId: String, decoration: String? = null): PetState {
        check(mutable.value.pet != null) { "adopt_first" }
        require(action in PetRules.actions && operationId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "invalid_pet_action" }
        mutable.value.events.firstOrNull { it.id == operationId }?.let {
            require(it.action == action && (action != "decorate" || it.decoration == decoration)) { "operation_id_reused" }; return snapshot()
        }
        val old = snapshot()
        val time = old.settledAt
        var next = when (action) {
            "feed" -> old.copy(food = (old.food + 25).coerceAtMost(100.0))
            "water" -> old.copy(water = (old.water + 30).coerceAtMost(100.0))
            "pet" -> old.copy(mood = (old.mood + 10).coerceAtMost(100.0), sleepingUntil = 0)
            "play" -> old.copy(mood = (old.mood + 20).coerceAtMost(100.0), food = (old.food - 2).coerceAtLeast(0.0),
                water = (old.water - 3).coerceAtLeast(0.0), messy = true, sleepingUntil = 0)
            "tidy" -> old.copy(messy = false)
            "board" -> old.copy(boarding = true, food = maxOf(80.0, old.food), water = maxOf(80.0, old.water))
            "return" -> old.copy(boarding = false)
            "sleep" -> old.copy(sleepingUntil = time + 3_600_000)
            "decorate" -> { require(decoration in setOf("sage", "rose", "sky")) { "invalid_decoration" }; old.copy(decoration = decoration!!) }
            else -> error("invalid_pet_action")
        }
        // One qualifying care day, not ten consecutive days; missed days never erase earned rewards.
        if (action in setOf("feed", "water", "pet", "play") && minOf(next.food, next.water, next.mood, next.health) >= 80) {
            val day = Instant.ofEpochMilli(time).atZone(zone()).toLocalDate().toString()
            if (day !in old.careDays) {
                val days = old.careDays + day
                next = next.copy(careDays = days, translationCredits = old.translationCredits + if (days.size % 10 == 0) 1 else 0)
            }
        }
        val note = when (action) {
            "feed" -> "已添食，饱腹改善"; "water" -> "已添水，水分改善"; "pet" -> "接受抚摸，心情改善"
            "play" -> "陪玩完成，房间变乱了"; "tidy" -> "房间已整理"; "board" -> "开始寄养，饱腹和水分由寄养照料，心情仍缓慢变化"
            "return" -> "已接回家"; "sleep" -> "开始休息一小时，仍可照料"; else -> "已更换房间装饰色"
        }
        return commit(next.copy(aiPending = null, revision = old.revision + 1, events = (old.events + PetEvent(operationId, action, time, note, if(action == "decorate") decoration else null)).takeLast(1000)))
    }
    @Synchronized fun actWithReceipt(action: String, operationId: String, decoration: String? = null): Pair<PetState, Boolean> {
        val existed = mutable.value.events.any { it.id == operationId }
        return act(action, operationId, decoration) to !existed
    }
    @Synchronized fun configureAi(modelId: String?, consent: Boolean): PetState {
        val old = snapshot()
        require(modelId == null || runCatching { UUID.fromString(modelId) }.isSuccess)
        require(!consent || modelId != null)
        return commit(old.copy(aiModelId = modelId, aiConsent = consent, aiPending = null, revision = old.revision + 1))
    }
    @Synchronized fun reserveAi(trigger: String, text: String = ""): PetAiTicket? {
        val old = snapshot()
        if (old.pet == null || !old.aiConsent || old.aiModelId == null) return null
        require(trigger in PetAiRules.triggers && text.length <= 1000) { "invalid_pet_trigger" }
        if (old.aiPending != null && old.settledAt - old.aiPending.at < 90_000) return null
        if (trigger == "idle" && old.aiLastAttemptAt > 0 && old.settledAt - old.aiLastAttemptAt < 3_600_000) return null
        val day = Instant.ofEpochMilli(old.settledAt).atZone(zone()).toLocalDate().toString()
        val used = if(old.aiUsageDay == day) old.aiUsageCount else 0
        if(old.aiDailyLimit > 0 && used >= old.aiDailyLimit) return null
        val ticket = PetAiTicket(UUID.randomUUID().toString(), old.aiModelId, trigger, text, old.settledAt)
        commit(old.copy(aiPending = ticket, aiLastAttemptAt = old.settledAt, revision = old.revision + 1,
            aiUsageDay = day, aiUsageCount = (used.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        return ticket
    }
    @Synchronized fun configureAiBudget(limit: Int) {
        require(limit in 0..1000)
        val old = snapshot()
        commit(old.copy(aiDailyLimit = limit, revision = old.revision + 1))
    }
    @Synchronized fun finishAi(ticket: PetAiTicket, reply: PetAiReply?): Boolean {
        val old = snapshot()
        // A newer care/configuration event or restored archive owns the pet now.
        if (old.aiPending != ticket || !old.aiConsent || old.aiModelId != ticket.modelId) return false
        if (reply == null) { commit(old.copy(aiPending = null, revision = old.revision + 1)); return false }
        PetAiRules.validate(reply, ticket)
        var next = old.copy(aiPending = null)
        reply.actions.forEach { action -> next = when(action) {
            "mess_up" -> next.copy(messy = true)
            "sleep" -> next.copy(sleepingUntil = old.settledAt + reply.sleepHours * 3_600_000L)
            "hungry" -> if(next.boarding) next else next.copy(food = (next.food - 10).coerceAtLeast(0.0))
            "thirsty" -> if(next.boarding) next else next.copy(water = (next.water - 10).coerceAtLeast(0.0))
            "lonely" -> next.copy(mood = (next.mood - 10).coerceAtLeast(0.0))
            "mood_up" -> next.copy(mood = (next.mood + 10).coerceAtMost(100.0))
            else -> next
        } }
        val animal = PetAiRules.animalWords(requireNotNull(old.pet).appearance, reply.utterance)
        if (reply.utterance.isNotBlank()) next = next.copy(sayings = (old.sayings + PetSaying(ticket.id, reply.utterance, animal, old.settledAt)).takeLast(50))
        val description = reply.actions.joinToString("、") { PetAiRules.labels.getValue(it) }
        if(ticket.trigger == "idle" && reply.actions.any { it == "mess_up" || it == "noisy" } && old.noticeConversationId != null && old.noticesEnabled) {
            val notice = PetNotice(ticket.id, old.noticeConversationId, old.settledAt,
                "${old.pet.name}：$description。${animal.take(81)}", noticeCoin())
            next = next.copy(notices = (old.notices + notice).takeLast(20))
        }
        return commit(next.copy(revision = old.revision + 1,
            events = (old.events + PetEvent(ticket.id, "ai_reaction", old.settledAt, listOf(description, animal).filter { it.isNotBlank() }.joinToString(" · "), aiActions = reply.actions)).takeLast(1000))).let { true }
    }
    @Synchronized fun translate(sayingId: String): String {
        val old = snapshot()
        val saying = old.sayings.find { it.id == sayingId } ?: error("pet_saying_missing")
        if (saying.translated) return saying.original
        check(old.translationCredits > 0) { "pet_translation_not_earned" }
        commit(old.copy(translationCredits = old.translationCredits - 1, revision = old.revision + 1,
            sayings = old.sayings.map { if(it.id == sayingId) it.copy(translated = true) else it }))
        return saying.original
    }
    @Synchronized fun configureNotices(conversationId: String?, enabled: Boolean) {
        require(conversationId == null || runCatching { UUID.fromString(conversationId) }.isSuccess)
        require(!enabled || conversationId != null)
        val old = snapshot()
        commit(old.copy(noticeConversationId = conversationId, noticesEnabled = enabled,
            notices = emptyList(), revision = old.revision + 1))
    }
    /** One durable attempt; a replay never rerolls chance or wakes the model repeatedly. */
    @Synchronized fun claimNotice(): PetNotice? {
        val old = snapshot()
        if(!old.noticesEnabled || (old.lastNoticeAt > 0 && old.settledAt - old.lastNoticeAt < 3_600_000)) return null
        val notice = old.notices.lastOrNull { it.selected && !it.attempted && it.conversationId == old.noticeConversationId &&
            old.settledAt - it.at in 0..21_600_000 } ?: return null
        commit(old.copy(lastNoticeAt = old.settledAt, notices = old.notices.map { it.copy(attempted = true) }, revision = old.revision + 1))
        return notice
    }
    private fun commit(next: PetState): PetState {
        check(!blocked) { "pet_storage_unavailable" }; PetRules.validate(next)
        try { storage.write(json.encodeToString(next)) } catch (e: Exception) { blocked = true; throw e }
        mutable.value = next; return next
    }
}
