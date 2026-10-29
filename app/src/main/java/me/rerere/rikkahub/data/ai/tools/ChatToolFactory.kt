package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.model.recallableAiReplies

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
    private val orbisMemoryRepository: me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository? = null,
) {
    private val stImageAttachments = StMemoryImageAttachmentRegistry()

    private fun hostWorkspaceId(assistant: Assistant, consultation: Boolean): String? =
        assistant.workspaceId?.toString() ?: if (BuildConfig.ORBIS_ENABLED && !consultation && settingsStore != null)
            me.rerere.rikkahub.data.orbis.integration.AiWorkspacePreparation.defaultId(assistant.id.toString())
        else null

    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: String? = null,
        voiceCallId: String? = null,
        allowAmbientCallBinding: Boolean = true,
        boundaryPauseAllowed: () -> Boolean = { false },
        consultationReferenceOnly: Boolean = false,
        imageSourceMessageIds: Set<kotlin.uuid.Uuid>? = null,
        onWorkshopAccepted: ((me.rerere.rikkahub.data.orbis.workshop.WorkshopJob) -> Unit)? = null,
        readActiveConversation: (suspend () -> me.rerere.rikkahub.data.model.Conversation?)? = null,
        recallOwnMessage: (suspend (kotlin.uuid.Uuid) -> Boolean)? = null,
    ): List<Tool> = buildList {
        if (BuildConfig.ORBIS_ENABLED && !consultationReferenceOnly && settingsStore != null) {
            if (settings.aiGlobalObservation) add(observationWindowsTool(
                enabled = { settingsStore.settingsFlow.value.aiGlobalObservation &&
                    settingsStore.settingsFlow.value.getAssistantById(assistant.id) != null },
                read = { limit, offset -> conversationRepository.observationWindows(assistant.id.toString(), limit, offset) }))
            add(connectionProposalTool(settingsStore, assistant.id.toString()) { proposal, guide ->
                if (conversationId != null) {
                    val queue = me.rerere.rikkahub.data.orbis.integration.connectionSetupQueue
                    if (proposal == null) queue.offerGuide(assistant.id.toString(), conversationId)
                    else queue.offer(conversationId, proposal, guide)
                }
            })
            if (LocalToolOption.SelfCheck in assistant.localTools) {
                val selfCheck = me.rerere.rikkahub.data.orbis.diagnostics.SelfCheckHost(context, settingsStore, mcpManager, workspaceRepository)
                add(createOrbisSelfCheckTool(
                    enabled = { settingsStore.settingsFlow.value.getAssistantById(assistant.id)?.localTools?.contains(LocalToolOption.SelfCheck) == true },
                    check = { selfCheck.check(assistant.id.toString(), it, conversationId) },
                ))
            }
            var stRuntimeWorkspaceForRun: String? = null
            add(orbisSelfConfigurationTool(settingsStore, assistant.id.toString(), context, mcpManager, workspaceRepository,
                stRuntimeWorkspaceForRun = { stRuntimeWorkspaceForRun }))
            hostWorkspaceId(assistant, consultationReferenceOnly)?.let { workspaceId ->
                val owner = assistant.id.toString()
                fun currentBinding() = settingsStore.settingsFlow.value.getAssistantById(assistant.id)?.workspaceId?.toString() == workspaceId
                val stHost = me.rerere.rikkahub.data.orbis.st.StRuntimeHost.get(context)
                val stWorkspace = workspaceRepository.getById(workspaceId)
                if (stWorkspace != null && currentBinding() && stHost.enabled(stWorkspace.root)) {
                    stRuntimeWorkspaceForRun = workspaceId
                    add(createOrbisStRuntimeTool(
                        allowed = { currentBinding() && stHost.enabled(stWorkspace.root) &&
                            workspaceRepository.getById(workspaceId)?.let {
                                it.root == stWorkspace.root && it.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name
                            } == true },
                        start = { stHost.requestStart(stWorkspace.root, owner, it) },
                        status = { stHost.current(stWorkspace.root) },
                        stop = { stHost.stop(stWorkspace.root) },
                    ))
                }
                val ombreHost = me.rerere.rikkahub.data.orbis.ombre.OmbreRuntimeHost.get(context)
                if (me.rerere.rikkahub.data.orbis.ombre.OMBRE_RUNTIME_ENTRY_ENABLED &&
                    stWorkspace != null && currentBinding() && ombreHost.enabled(stWorkspace.root)) {
                    add(createOrbisOmbreRuntimeTool(
                        allowed = { currentBinding() && ombreHost.enabled(stWorkspace.root) &&
                            workspaceRepository.getById(workspaceId)?.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name },
                        start = { ombreHost.requestStart(stWorkspace.root, owner, it) },
                        status = { ombreHost.current(stWorkspace.root) }, stop = { ombreHost.stop(stWorkspace.root) },
                    ))
                }
                add(createOrbisBackupTool(workspaceId, ::currentBinding,
                    readFile = { path ->
                        check(currentBinding() && workspaceRepository.getById(workspaceId) != null)
                        val chunk = workspaceRepository.readNativeChunk(workspaceId, path, 0,
                            me.rerere.rikkahub.data.sync.BackupRecoveryConnection.MAX_BYTES, null)
                        require(chunk.eof && chunk.totalBytes <= me.rerere.rikkahub.data.sync.BackupRecoveryConnection.MAX_BYTES)
                        chunk.text.toByteArray(Charsets.UTF_8)
                    },
                    bind = { connection, automatic, secrets ->
                        check(currentBinding() && workspaceRepository.getById(workspaceId) != null)
                        check(settingsStore.update { me.rerere.rikkahub.data.sync.AiBackupBinding.bind(
                            it, owner, workspaceId, connection, automatic, secrets) })
                        if (automatic) me.rerere.rikkahub.data.sync.AutoBackupRuntime.now(context)
                    },
                    status = {
                        check(currentBinding() && workspaceRepository.getById(workspaceId) != null)
                        me.rerere.rikkahub.data.sync.AiBackupOperations.status(
                            context, owner, workspaceId, settingsStore.settingsFlow.value)
                    },
                    request = { action ->
                        val live = settingsStore.settingsFlow.value
                        check(currentBinding() && workspaceRepository.getById(workspaceId) != null &&
                            me.rerere.rikkahub.data.sync.AiBackupBinding.owns(live, owner, workspaceId))
                        if (action == "save_now") {
                            check(live.autoBackup.enabled) { "automatic_backup_disabled" }
                            me.rerere.rikkahub.data.sync.AutoBackupRuntime.now(context)
                        } else me.rerere.rikkahub.data.sync.AiBackupOperations.enqueue(context, action, owner, workspaceId, live)
                    }))
            }
        }
        if (BuildConfig.ORBIS_ENABLED && orbisMemoryRepository != null) {
            add(buildOrbisMemoryTool(assistant.id.toString(),
                assistantExists = { (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id) != null },
                readOnly = consultationReferenceOnly,
                executeMemory = orbisMemoryRepository::execute))
        }
        if (me.rerere.rikkahub.data.ai.legacyMemoryEnabled(assistant.enableMemory)) {
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
        if (assistant.workspaceId == null) {
            hostWorkspaceId(assistant, consultationReferenceOnly)?.let { expectedId ->
                addAll(createPreparedWorkspaceTools(expectedId, workspaceRepository,
                    stillBound = { settingsStore?.settingsFlow?.value?.getAssistantById(assistant.id)?.workspaceId?.toString() == expectedId }))
            }
        } else {
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId.toString(), workspaceCwd, consultationReferenceOnly))
        }
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
        val imageDestinations = if (conversationId == null || imageSourceMessageIds == null) emptyList() else mcpTools
            .filter { it.third.name in setOf("store_memory_image", "stbrain_manage") &&
                mcpManager.supportsMemoryImageUploads(it.first) &&
                me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context).permits(assistant.id.toString(), "mcp", it.first.toString()) }
            .groupBy { it.first }.map { (serverId, entries) ->
                val server = settings.mcpServers.first { it.id == serverId }
                val store = entries.firstOrNull { it.third.name == "store_memory_image" } ?: entries.first()
                StImageDestination(serverId, approvalFingerprint(json.encodeToString(server)), store.second,
                    "mcp__${store.second}__${store.third.name}")
            }
        val imageConversationId = conversationId?.let(kotlin.uuid.Uuid::parse)
        // The active continuation can be newer than Room. Use the SAME owner-bound source
        // for discovery and invocation; never consult the currently selected UI tab.
        val readBoundConversation: suspend () -> me.rerere.rikkahub.data.model.Conversation? = {
            (if (readActiveConversation != null) readActiveConversation()
            else imageConversationId?.let { conversationRepository.getConversationById(it) })
                ?.takeIf { it.id == imageConversationId && it.assistantId == assistant.id }
        }
        if (BuildConfig.ORBIS_ENABLED && !consultationReferenceOnly && imageConversationId != null) {
            add(createOrbisConversationHistoryTool(assistant.id, imageConversationId, readBoundConversation,
                listRemoved = { current, limit, offset -> conversationRepository.listRemovedHistory(current, limit, offset) },
                readRemoved = { current, message -> conversationRepository.readRemovedHistory(current, message) },
                enabled = { (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id) != null },
                verifyCurrent = { current -> conversationRepository.verifyCurrentHistoryScope(current) }))
        }
        if (BuildConfig.ORBIS_ENABLED && !consultationReferenceOnly && recallOwnMessage != null &&
            LocalToolOption.MessageRecall in assistant.localTools) {
            val targets = readBoundConversation()?.recallableAiReplies()?.map { it.id }.orEmpty()
            add(createMessageRecallTool(targets, recallOwnMessage))
        }
        val imageScope = if (imageDestinations.isEmpty() || imageConversationId == null) null else
            readBoundConversation()
                ?.takeIf { it.id == imageConversationId && it.assistantId == assistant.id }
                ?.takeIf { current -> current.currentMessages.map { it.id }.toSet().containsAll(imageSourceMessageIds!!) }
                ?.let { StImageAttachmentScope.capture(assistant.id, imageConversationId, it, imageSourceMessageIds!!, assistant.contextMessageLimit) }
        val readImageConversation: suspend () -> me.rerere.rikkahub.data.model.Conversation? = {
            readBoundConversation()
        }
        val readImageOriginal: suspend (String) -> StImageOriginal = { url ->
            readStMemoryImageOriginal(url, context.filesDir.resolve("upload"),
                org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>())
        }
        fun imageTargetsCurrent(): Boolean {
            val live = settingsStore?.settingsFlow?.value ?: settings
            val owner = live.getAssistantById(assistant.id) ?: return false
            return imageDestinations.all { destination ->
                val current = live.mcpServers.firstOrNull { it.id == destination.serverId }
                current != null && approvalFingerprint(json.encodeToString(current)) == destination.revision &&
                    destination.serverId in owner.mcpServers && mcpManager.supportsMemoryImageUploads(destination.serverId) &&
                    me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context).permits(assistant.id.toString(), "mcp", destination.serverId.toString())
            }
        }
        if (imageScope != null) add(createStImageListTool(imageScope, imageDestinations, stImageAttachments,
            readImageConversation, readImageOriginal, ::imageTargetsCurrent))
        // This authenticated staging primitive is for the host bridge only, never model tool discovery.
        mcpTools.filterNot { me.rerere.rikkahub.data.ai.mcp.StMemoryImageProtocol.isHostUploadTool(it.third) }.forEach { (serverId, serverName, tool) ->
            val server = settings.mcpServers.firstOrNull { it.id == serverId }
            // Bind to the executing server ID; names may be changed or share a prefix.
            fun mcpSelected(): Boolean {
                val live = settingsStore?.settingsFlow?.value ?: settings
                val owner = live.getAssistantById(assistant.id) ?: return false
                return me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context)
                    .permits(assistant.id.toString(), "mcp", serverId.toString()) &&
                    mcpToolsForAssistant(live, owner).any { it.first == serverId && it.third.name == tool.name }
            }
            val imageDestination = imageDestinations.firstOrNull { it.serverId == serverId }
            val bridgeImageTool = imageScope != null && imageDestination != null &&
                tool.name in setOf("store_memory_image", "stbrain_manage")
            val schema = if (bridgeImageTool && tool.name == "store_memory_image")
                stImageReferenceSchema(tool.inputSchema) else tool.inputSchema
            val revision = approvalFingerprint("mcp-v1\n${json.encodeToString(server)}\n${json.encodeToString(tool)}" +
                if (bridgeImageTool) "\nst-image-reference-v1\n$schema" else "")
            val definition = Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = (tool.description ?: "") + if (bridgeImageTool)
                        "\nOrbis 当前聊天图片请先调用 orbis_list_current_images 获取 upload_ref 和 mime_type，再原样提交。宿主验证并暂存原图；不要生成 data_base64。" else "",
                    parameters = { schema },
                    needsApproval = { tool.needsApproval },
                    isSelected = { mcpSelected() },
                    hostApproval = HostToolApproval("mcp:$serverId:${tool.name}", revision, "MCP $serverName · ${tool.name}"),
                    isApprovalCurrent = {
                        val live = settingsStore?.settingsFlow?.value ?: settings
                        val current = live.mcpServers.firstOrNull { it.id == serverId }
                        mcpSelected() && current == server && current?.commonOptions?.enable == true &&
                            live.getAssistantById(assistant.id)?.let { owner ->
                                mcpToolsForAssistant(live, owner).any { it.first == serverId && it.third == tool }
                            } == true
                    },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            add(if (bridgeImageTool) wrapStImageUploadTool(definition, tool.name, imageScope!!, imageDestination!!,
                stImageAttachments, readImageConversation, readImageOriginal, stage = { ref, original ->
                    check(imageTargetsCurrent()) { "st_image_target_changed" }
                    mcpManager.stageMemoryImageUpload(serverId, ref, original.mimeType, original.bytes, original.sha256)
                }) else definition)
        }
        if (BuildConfig.ORBIS_ENABLED) {
            if (!consultationReferenceOnly) {
                fun liveOwner() = (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id)
                if (LocalToolOption.HumanHealth in assistant.localTools) {
                    add(me.rerere.rikkahub.data.orbis.health.healthTool(context, assistant.id.toString(),
                        { liveOwner()?.localTools?.contains(LocalToolOption.HumanHealth) == true }))
                }
                if (LocalToolOption.LocalDesire in assistant.localTools) {
                    add(me.rerere.rikkahub.data.orbis.desire.localDesireTool(context, assistant.id.toString(),
                        org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository>(),
                        { liveOwner()?.localTools?.contains(LocalToolOption.LocalDesire) == true }))
                }
                if (LocalToolOption.ThemeDesign in assistant.localTools) {
                    add(me.rerere.rikkahub.data.orbis.theme.themeTool(
                        { me.rerere.rikkahub.data.orbis.theme.openThemes(context, assistant.id.toString()) },
                        { liveOwner()?.localTools?.contains(LocalToolOption.ThemeDesign) == true },
                        readWorkspaceImage = { path ->
                            val workspace = liveOwner()?.workspaceId?.toString() ?: error("theme_workspace_unavailable")
                            val bytes = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
                                .readNativeBytes(workspace, path)
                            check(liveOwner()?.workspaceId?.toString() == workspace) { "theme_workspace_changed" }
                            bytes
                        }))
                }
                if (assistant.orbisBodyEnabled && conversationId != null) {
                    // Registering tools must never reinterpret historical human text as a new
                    // event after enable/retry. Only ChatService's accepted-input hooks record it.
                    add(bodyTool(conversationId, { liveOwner()?.orbisBodyEnabled == true }) {
                        me.rerere.rikkahub.data.orbis.body.OrbisBody.open(context, assistant.id.toString())
                    })
                }
                if (assistant.orbisPetEnabled) add(petTool({ liveOwner()?.orbisPetEnabled == true }, { action, text ->
                    me.rerere.rikkahub.data.orbis.activities.PetAiRuntime.enqueue(context, assistant.id.toString(), action, text)
                }) {
                    me.rerere.rikkahub.data.orbis.activities.OrbisActivities.pet(context, assistant.id.toString())
                })
                if (assistant.orbisStudyEnabled) add(studyTool({ liveOwner()?.orbisStudyEnabled == true }) {
                    me.rerere.rikkahub.data.orbis.activities.OrbisActivities.study(context, assistant.id.toString())
                })
            }
            if (!consultationReferenceOnly && conversationId != null && imageSourceMessageIds != null) {
                val zipConversationId = kotlin.uuid.Uuid.parse(conversationId)
                val zipLastUserId = readBoundConversation()
                    ?.currentMessages?.lastOrNull { it.role == me.rerere.ai.core.MessageRole.USER }?.id
                addAll(createOrbisZipAttachmentTools(
                    assistantId = assistant.id,
                    conversationId = zipConversationId,
                    sourceMessageIds = imageSourceMessageIds,
                    readConversation = readBoundConversation,
                    readArchiveFile = { document -> readManagedZipAttachment(document,
                        context.filesDir.resolve("upload"),
                        org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()) },
                ))
                add(createOrbisZipCreateTool(context, assistant.id.toString(), conversationId,
                    scopeCurrent = {
                        val ownerExists = (settingsStore?.settingsFlow?.value ?: settings)
                            .getAssistantById(assistant.id) != null
                        val live = readBoundConversation()
                        ownerExists && isZipCreationScopeCurrent(assistant.id, zipConversationId,
                            zipLastUserId, imageSourceMessageIds, live)
                    }))
            }
            val privateRoomTools = me.rerere.rikkahub.data.orbis.privateroom.buildPrivateRoomAssistantTools(
                assistantId = assistant.id.toString(),
                assistantExists = { (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id) != null },
                openRepository = { ownerId -> me.rerere.rikkahub.data.orbis.privacy.AndroidPrivateVaults.open(context, ownerId) },
                consultationReferenceOnly = consultationReferenceOnly,
            )
            // Unconfigured/paused rooms expose status only. No implicit creation or enabling;
            // each actual operation also rechecks this owner's live vault state under its lock.
            val privateRoomReady = !consultationReferenceOnly && withContext(Dispatchers.IO) {
                runCatching {
                    val state = me.rerere.rikkahub.data.orbis.privacy.AndroidPrivateVaults
                        .open(context, assistant.id.toString()).status()
                    state.availability == me.rerere.rikkahub.data.orbis.privacy.PrivateVaultAvailability.READY &&
                        state.enabled && state.recoveryConfirmed
                }.getOrDefault(false)
            }
            addAll(privateRoomTools.filter { privateRoomReady || it.name == "orbis_private_room_visit" })
            addAll(me.rerere.rikkahub.data.orbis.gallery.buildGalleryTools(context, assistant.id.toString(),
                LocalToolOption.LocalGallery in assistant.localTools))
            if (conversationId != null && LocalToolOption.ContextPruning in assistant.localTools) {
                val boundId = kotlin.uuid.Uuid.parse(conversationId)
                add(createOrbisContextPruningTool(
                    me.rerere.rikkahub.data.ai.contextpruning.AndroidContextPruning.open(context, assistant.id, boundId),
                    readConversation = readBoundConversation,
                    isEnabled = {
                        val live = (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id)
                        live != null && LocalToolOption.ContextPruning in live.localTools
                    },
                ))
            }
            addAll(createOrbisKaomojiTools { me.rerere.rikkahub.data.orbis.OrbisKaomojis.open(context.applicationContext) })
            addAll(me.rerere.rikkahub.data.orbis.schedule.buildOrbisScheduleTools(context))
            add(createOrbisVoiceNoteTool(
                me.rerere.rikkahub.data.orbis.voice.OrbisVoiceNotes(context,
                    org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()),
                settings = { settingsStore?.settingsFlow?.value ?: settings },
            ))
            if (!consultationReferenceOnly) assistant.workspaceId?.toString()?.let { workspaceId ->
                add(createOrbisSendAudioTool(
                    me.rerere.rikkahub.data.orbis.voice.OrbisVoiceNotes(context,
                        org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()),
                    workspaceId, workspaceRepository,
                    stillBound = { (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id)?.workspaceId == assistant.workspaceId },
                ))
            }
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
            if (!consultationReferenceOnly) {
                add(createOrbisCharacterTool(context, assistant.id.toString()) {
                    settingsStore?.settingsFlow?.value?.let { live -> !live.init && live.assistants.any { it.id == assistant.id } } == true
                })
                if (conversationId != null && runCatching {
                    me.rerere.rikkahub.data.orbis.workshop.ImageWorkshop.open(context).state.value.allowAi
                }.getOrDefault(false)) add(createOrbisImageWorkshopTool(context, assistant.id.toString(), conversationId,
                    onNewAccepted = onWorkshopAccepted))
                addAll(createOrbisCompanionSpaceTools(context, assistant.id.toString()))
                if (conversationId != null) addAll(createOrbisScreenShareTools(context, assistant.id.toString(), conversationId))
                if (conversationId != null) addAll(createOrbisVideoCallTools(context, assistant.id.toString(),
                    conversationId, voiceCallId, allowAmbientCallBinding))
            }
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
    }.let { builtTools ->
        val boundary = me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context)
        val ownerId = assistant.id.toString()
        val tools = if (BuildConfig.ORBIS_ENABLED && !consultationReferenceOnly && boundary.state(ownerId).grants.isNotEmpty()) builtTools +
            createBoundaryTool(boundary, ownerId, conversationId, catalog = {
                val live = settingsStore?.settingsFlow?.value ?: settings
                mapOf("model" to live.providers.flatMap { it.models }.associate { it.id.toString() to "${it.displayName} · ${it.type}" },
                    "mcp" to live.mcpServers.associate { it.id.toString() to it.commonOptions.name },
                    "tool" to (builtTools.associate { it.name to it.description.take(100) } + ("orbis_help" to "按需查询 Orbis 操作指南") +
                        model.tools.associate { me.rerere.rikkahub.data.orbis.boundary.builtinBoundaryId(it) to "模型内置 $it" }))
            }, toolSources = {
                mcpToolsForAssistant(settings, assistant).associate { (id, name, tool) ->
                    "mcp__${name}__${tool.name}" to id.toString()
                }
            }, bindingCurrent = { (settingsStore?.settingsFlow?.value ?: settings).getAssistantById(assistant.id) != null },
                verifyConversation = {
                    conversationId == null || (readActiveConversation?.invoke()
                        ?: conversationRepository.getConversationById(kotlin.uuid.Uuid.parse(conversationId)))?.let {
                            it.id.toString() == conversationId && it.assistantId == assistant.id
                        } == true
                }, alternateModelAvailable = { id ->
                    (settingsStore?.settingsFlow?.value ?: settings).providers.any { provider ->
                        provider.enabled && provider.models.any { it.id.toString() == id && it.type == me.rerere.ai.provider.ModelType.CHAT }
                    }
                }) else builtTools
        fun selected(tool: Tool): Boolean {
            if (tool.name == me.rerere.rikkahub.data.orbis.boundary.BOUNDARY_TOOL) return boundary.state(ownerId).grants.isNotEmpty()
            if (tool.name in setOf("toy_bluetooth_stop", "toy_lab_stop", "end_voice_call")) return true
            if (boundary.pause(ownerId, conversationId) != null && !boundaryPauseAllowed()) return false
            if (!boundary.permits(ownerId, "tool", tool.name)) return false
            return true // MCP-specific selection is bound to the server ID in the tool itself.
        }
        val compactDescriptions = BuildConfig.ORBIS_ENABLED && boundary.permits(ownerId, "tool", ORBIS_HELP_TOOL_NAME)
        appendOrbisHelpTool(
            tools = tools,
            build = OrbisHelpBuild(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
            ),
            enabled = BuildConfig.ORBIS_ENABLED,
        ).map { originalTool ->
            val tool = originalTool
            val guarded = tool.copy(isSelected = { selected(tool) && tool.isSelected() }, isApprovalCurrent = {
                val live = settingsStore?.settingsFlow?.value?.getAssistantById(assistant.id)
                val selectionCurrent = settingsStore == null || (live != null && when {
                    tool.name == me.rerere.rikkahub.data.orbis.boundary.BOUNDARY_TOOL -> true
                    tool.name.startsWith("toy_lab") -> true // separate device grant checked by the lab tool
                    tool.name == ORBIS_MEMORY_TOOL -> true // stopping injection must not disable store/query
                    tool.name.startsWith("companion_") || tool.name.startsWith("toy_bluetooth_") ->
                        live.localTools == assistant.localTools
                    tool.name.startsWith("calendar_") -> LocalToolOption.Calendar in live.localTools
                    tool.name.startsWith("workspace_") -> live.workspaceId?.toString() ==
                        hostWorkspaceId(assistant, consultationReferenceOnly)
                    tool.name.startsWith("mcp__") || tool.name == ST_IMAGE_LIST_TOOL -> live.mcpServers == assistant.mcpServers
                    else -> live.localTools == assistant.localTools &&
                        live.enableMemory == assistant.enableMemory &&
                        live.useGlobalMemory == assistant.useGlobalMemory &&
                        live.enabledSkills == assistant.enabledSkills &&
                        live.enableRecentChatsReference == assistant.enableRecentChatsReference
                })
                // Emergency stop does not become inaccessible when its family is disabled.
                (tool.name in setOf("toy_bluetooth_stop", "toy_lab_stop", "end_voice_call") || selectionCurrent) && selected(tool) && tool.isApprovalCurrent()
            })
            // Derive approval identity from the original definition, before shortening its wire description.
            val bound = bindToolApproval(guarded, assistant.id.toString(), ToolApprovalStore.get(context))
            val approved = if (compactDescriptions) compactOrbisToolDescription(bound) else bound
            if (!BuildConfig.ORBIS_ENABLED || consultationReferenceOnly || tool.name == "orbis_self_check") approved else approved.copy(execute = { args ->
                val result = approved.execute(args)
                runCatching {
                    result.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().asSequence().mapNotNull { part ->
                        if (part.text.length > 32 * 1024) null else runCatching { json.parseToJsonElement(part.text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                    }.firstOrNull { it["ok"] == kotlinx.serialization.json.JsonPrimitive(false) }?.let { failure ->
                        val code = listOf("code", "error", "reason").mapNotNull { failure[it] as? kotlinx.serialization.json.JsonPrimitive }
                            .map { it.content }.firstOrNull { it in me.rerere.rikkahub.data.orbis.diagnostics.diagnosticCodes } ?: "unclassified_failure"
                        me.rerere.rikkahub.data.orbis.diagnostics.DiagnosticJournal.record(context, ownerId,
                            if (tool.name == "orbis_theme") "theme" else "tool", code)
                    }
                }
                result
            })
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?, consultationReferenceOnly: Boolean): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (consultationReferenceOnly && workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, consultationReferenceOnly)
    }
}
