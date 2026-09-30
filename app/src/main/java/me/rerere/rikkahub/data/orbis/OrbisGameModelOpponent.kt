package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

internal data class GomokuModelRequest(
    val provider: ProviderSetting,
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
)

/** One isolated text request per durable repository ticket; no chat service, tools or retries. */
class OrbisGameModelOpponent internal constructor(
    private val settings: () -> Settings,
    private val generate: suspend (GomokuModelRequest) -> UIMessage,
) {
    constructor(settingsStore: SettingsStore, providerManager: ProviderManager) : this(
        settings = { settingsStore.settingsFlow.value },
        generate = { request ->
            providerManager.getProviderByType(request.provider).generateText(
                providerSetting = request.provider,
                messages = request.messages,
                params = request.params,
            ).message
        },
    )

    suspend fun chooseMove(turn: OrbisGameMatch): Int = withTimeout(60_000L) {
        val request = prepareGomokuModelRequest(turn, settings())
        val response = generate(request)
        require(response.getTools().isEmpty()) { "game_model_unexpected_tool" }
        parseGomokuModelMove(response.toText(), turn.moves)
    }
}

private const val AUXILIARY_SUFFIX = "--auxiliary-no-memory"

/** Never invent an alias for arbitrary vendors or borrow a different provider/base model. */
internal fun selectGomokuModel(model: Model, providers: List<ProviderSetting>): Model {
    val container = model.findProvider(providers, checkOverwrite = false)
        ?: error("game_model_provider_missing")
    require(container.enabled) { "game_model_provider_disabled" }
    if (model.modelId.endsWith(AUXILIARY_SUFFIX)) return model
    val alias = container.models.firstOrNull { it.modelId == model.modelId + AUXILIARY_SUFFIX }
    if (alias != null) {
        // Model-level connection overrides must not route the auxiliary call to a
        // different endpoint/account than the configured main model.
        val originalProvider = model.findProvider(providers)?.copyProvider(models = emptyList())
        val aliasProvider = alias.findProvider(providers)?.copyProvider(models = emptyList())
        require(originalProvider == aliasProvider) { "game_model_auxiliary_alias_missing" }
        return alias
    }
    require(container.models.none { it.modelId.endsWith(AUXILIARY_SUFFIX) }) {
        "game_model_auxiliary_alias_missing"
    }
    return model
}

private fun gameHeaders(headers: List<CustomHeader>): List<CustomHeader> = headers.filterNot {
    val name = it.name.trim().lowercase()
    name.startsWith("x-st-") || name in setOf(
        "x-session-id", "x-conversation-id", "x-thread-id", "x-client-id", "x-opencode-session",
        "x-orbis-conversation-id", "x-request-purpose",
    )
}

/** Only scalar sampling preferences are safe to merge after the provider builds its task envelope. */
private fun gameBody(bodies: List<CustomBody>): List<CustomBody> = bodies.filter {
    it.key in setOf("temperature", "top_p", "top_k", "frequency_penalty", "presence_penalty",
        "seed", "repetition_penalty", "min_p") && (it.value as? JsonPrimitive)?.isString == false
}

internal fun prepareGomokuModelRequest(turn: OrbisGameMatch, settings: Settings): GomokuModelRequest {
    require(turn.opponent == GomokuRules.MODEL_OPPONENT) { "not_model_opponent" }
    require(turn.modelCallLimit in 1..40 && turn.modelCalls in 1..turn.modelCallLimit) { "game_model_ticket_required" }
    val position = GomokuRules.replay(turn.moves)
    require(!position.finished && !position.humanTurn) { "not_model_turn" }
    val assistant = settings.assistants.firstOrNull { it.id.toString() == turn.opponentAssistantId }
        ?: error("game_model_assistant_missing")
    val selected = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        ?: error("game_model_not_configured")
    val model = selectGomokuModel(selected, settings.providers)
    require(model.type == ModelType.CHAT) { "game_model_not_chat" }
    val provider = model.findProvider(settings.providers) ?: error("game_model_provider_missing")
    require(provider.enabled) { "game_model_provider_disabled" }
    val legal = position.board.indices.filter { position.board[it] == 0 }
    val board = buildJsonObject {
        put("game", GomokuRules.RULESET)
        put("size", GomokuRules.SIZE)
        put("your_piece", 2)
        put("human_piece", 1)
        put("board_row_major", JsonArray(position.board.map(::JsonPrimitive)))
        put("moves", JsonArray(turn.moves.map(::JsonPrimitive)))
        put("legal_cells", JsonArray(legal.map(::JsonPrimitive)))
    }
    val prompt = """
        这是独立的 Orbis 五子棋棋局，不是主聊天；没有主聊天历史，也没有可调用工具。
        保留你的说话身份，但本次只需选择一步合法落子，不写叙述，不调用任何工具。
        9×9 棋盘，格子编号为 row*9+column（0到80）；人类执1先手，你执2后手，0为空位。
        横、竖或斜向连续至少五子获胜，没有禁手。只能从 legal_cells 选择一个格子。
        仅输出 JSON 对象，例如 {"cell":40}，其中 cell 必须是整数，不要输出分析或 Markdown。
    """.trimIndent()
    return GomokuModelRequest(
        provider = provider,
        messages = listOf(UIMessage.system(listOf(assistant.systemPrompt, prompt).filter { it.isNotBlank() }.joinToString("\n\n")),
            UIMessage.user(board.toString())),
        params = TextGenerationParams(
            model = model.copy(tools = emptySet(), customBodies = emptyList(), customHeaders = emptyList()),
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = 256,
            reasoningLevel = ReasoningLevel.OFF,
            tools = emptyList(),
            customHeaders = gameHeaders(assistant.customHeaders + model.customHeaders),
            customBody = gameBody(assistant.customBodies + model.customBodies),
            sessionId = null,
            orbisConversationId = null,
            maxAutomaticContinuations = 0,
        ),
    )
}

internal fun parseGomokuModelMove(text: String, moves: List<Int>): Int {
    require(text.length <= 4096) { "game_model_invalid_reply" }
    val trimmed = text.trim()
    val raw = if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
        trimmed.substringAfter('\n', "").removeSuffix("```").trim()
    } else trimmed
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: throw IllegalArgumentException("game_model_invalid_reply")
    val cellValue = obj["cell"] as? JsonPrimitive
    require(cellValue != null && !cellValue.isString) { "game_model_invalid_reply" }
    val cell = cellValue.intOrNull ?: throw IllegalArgumentException("game_model_invalid_reply")
    val position = GomokuRules.replay(moves)
    require(!position.finished && !position.humanTurn && cell in position.board.indices && position.board[cell] == 0) {
        "game_model_illegal_move"
    }
    return cell
}
