package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.OrbisGames
import me.rerere.rikkahub.data.orbis.OrbisMiniGames
import me.rerere.rikkahub.data.orbis.OrbisStickers
import me.rerere.rikkahub.data.orbis.OrbisPhones
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolsEngine
import me.rerere.rikkahub.data.orbis.contact.OrbisNotificationSpeechScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.HostToolApproval
import me.rerere.rikkahub.data.ai.approval.ToolApprovalStore
import me.rerere.rikkahub.data.ai.approval.approvalFingerprint
import me.rerere.rikkahub.data.ai.approval.bindToolApproval
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Conversation ownership, not the currently selected UI tab, determines the MCP catalogue. */
internal fun mcpToolsForAssistant(settings: Settings, assistant: Assistant) = settings.mcpServers
    .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
    .flatMap { server -> server.commonOptions.tools.filter { it.enable }
        .map { Triple(server.id, server.commonOptions.name, it) } }

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val context: Context,
    private val cloudTools: CloudToolsEngine? = null,
    private val settingsStore: SettingsStore? = null,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: String? = null,
        voiceCallId: String? = null,
        allowAmbientCallBinding: Boolean = true,
        consultationReferenceOnly: Boolean = false,
    ): List<Tool> = buildList {
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(
                buildMemoryTools(
                    json = json,
                    onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                )
            )
        }
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createSearchTools(settings))
        }
        addAll(withContext(Dispatchers.IO) { localTools.getTools(assistant.localTools,
            conversationId?.let { OrbisNotificationSpeechScope(assistant.id.toString(), it) }) })
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd, consultationReferenceOnly))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }

        val mcpTools = mcpToolsForAssistant(settings, assistant)
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            val server = settings.mcpServers.firstOrNull { it.id == serverId }
            val revision = approvalFingerprint("mcp-v1\n${json.encodeToString(server)}\n${json.encodeToString(tool)}")
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    hostApproval = HostToolApproval("mcp:$serverId:${tool.name}", revision, "MCP $serverName · ${tool.name}"),
                    isApprovalCurrent = {
                        val live = settingsStore?.settingsFlow?.value ?: settings
                        val current = live.mcpServers.firstOrNull { it.id == serverId }
                        current == server && current?.commonOptions?.enable == true &&
                            live.getAssistantById(assistant.id)?.let { owner ->
                                mcpToolsForAssistant(live, owner).any { it.first == serverId && it.third == tool }
                            } == true
                    },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
        if (BuildConfig.ORBIS_ENABLED) {
            addAll(createOrbisKaomojiTools { me.rerere.rikkahub.data.orbis.OrbisKaomojis.open(context.applicationContext) })
            addAll(me.rerere.rikkahub.data.orbis.schedule.buildOrbisScheduleTools(context))
            add(createOrbisVoiceNoteTool(
                me.rerere.rikkahub.data.orbis.voice.OrbisVoiceNotes(context,
                    org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()),
                settings = { settingsStore?.settingsFlow?.value ?: settings },
            ))
            addAll(me.rerere.rikkahub.data.orbis.consultation.createOrbisConsultationMainTools(
                context, assistant.id, conversationId, conversationRepository,
            ))
            // Resolve only when explicitly invoked: GroupChats -> GenerationLoop -> this factory.
            // Binding is this run's owner, never the active private/group tab.
            addAll(createOrbisGroupReadTools(
                listGroups = { limit, after ->
                    org.koin.core.context.GlobalContext.get()
                        .get<me.rerere.rikkahub.data.orbis.group.OrbisGroupChats>()
                        .listReadableGroups(assistant.id, limit, after)
                },
                readGroup = { room, limit, before, own ->
                    org.koin.core.context.GlobalContext.get()
                        .get<me.rerere.rikkahub.data.orbis.group.OrbisGroupChats>()
                        .readReadableGroup(assistant.id, room, limit, before, own)
                },
            ))
            if (conversationId != null) addAll(createOrbisIncomingCallTools(context, assistant.id.toString(), conversationId,
                voiceCallId, allowAmbientCallBinding))
            if (conversationId != null) runCatching {
                me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels.open(context)
            }.getOrNull()?.let { controller ->
                addAll(createOrbisSentinelTools(controller, assistant.id.toString(), conversationId))
            }
            addAll(createOrbisVoiceCallTools(me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository(context), assistant.id.toString()))
            // Independent JSON API, explicitly opted in per current assistant; not MCP wrapping.
            addAll(cloudTools?.createTools(assistant.id).orEmpty())
            add(createOrbisDeviceTool(
                readDevice = { withContext(Dispatchers.IO) { OrbisPhones.open(context.applicationContext).readDevice() } },
                readObservation = { withContext(Dispatchers.IO) { OrbisPhones.open(context.applicationContext).readState() } },
            ))
            add(createOrbisGamesRecordsTool(
                readState = { withContext(Dispatchers.IO) { OrbisGames.open(context.applicationContext).readSnapshot() } },
                readLibrary = { withContext(Dispatchers.IO) { OrbisMiniGames.open(context.applicationContext).readSnapshot() } },
            ))
            addAll(createOrbisGameLibraryTools(
                readLibrary = { withContext(Dispatchers.IO) { OrbisMiniGames.open(context.applicationContext).readSnapshot() } },
                install = { request -> withContext(Dispatchers.IO) {
                    OrbisMiniGames.open(context.applicationContext).install(
                        authorId = assistant.id.toString(), authorName = assistant.name.take(200),
                        title = request.title, description = request.description, html = request.html,
                        gameId = request.gameId, expectedSha256 = request.expectedSha256,
                    )
                } },
            ))
            add(createOrbisStickersTool {
                withContext(Dispatchers.IO) { OrbisStickers.open(context.applicationContext).readSnapshot() }
            })
        }
    }.let { tools ->
        appendOrbisHelpTool(
            tools = tools,
            build = OrbisHelpBuild(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
            ),
            enabled = BuildConfig.ORBIS_ENABLED,
        ).map { tool ->
            val guarded = tool.copy(isApprovalCurrent = {
                val live = settingsStore?.settingsFlow?.value?.getAssistantById(assistant.id)
                val selectionCurrent = settingsStore == null || (live != null && when {
                    tool.name.startsWith("companion_") || tool.name.startsWith("toy_bluetooth_") ->
                        live.localTools == assistant.localTools
                    tool.name.startsWith("calendar_") -> LocalToolOption.Calendar in live.localTools
                    tool.name.startsWith("workspace_") -> live.workspaceId == assistant.workspaceId
                    tool.name.startsWith("mcp__") -> live.mcpServers == assistant.mcpServers
                    else -> live.localTools == assistant.localTools &&
                        live.enableMemory == assistant.enableMemory &&
                        live.useGlobalMemory == assistant.useGlobalMemory &&
                        live.enabledSkills == assistant.enabledSkills &&
                        live.enableRecentChatsReference == assistant.enableRecentChatsReference
                })
                // Emergency stop does not become inaccessible when its family is disabled.
                (tool.name == "toy_bluetooth_stop" || selectionCurrent) && tool.isApprovalCurrent()
            })
            bindToolApproval(guarded, assistant.id.toString(), ToolApprovalStore.get(context))
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?, consultationReferenceOnly: Boolean): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, consultationReferenceOnly)
    }
}
