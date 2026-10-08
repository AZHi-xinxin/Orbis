package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import android.util.AtomicFile
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import java.io.File
import java.io.FileNotFoundException

private const val TAG = "BackupVM"

class BackupVM(
    private val context: android.content.Context,
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())
    private fun importAssistantName(id: kotlin.uuid.Uuid): String? = settingsStore.settingsFlow.value.let {
        if (it.init) null else it.assistants.firstOrNull { assistant -> assistant.id == id }?.name
    }
    val deepSeekImport = DeepSeekImportController(context, viewModelScope, conversationRepository,
        assistantName = ::importAssistantName) {
        check(!settings.value.init)
        settings.value.assistantId
    }

    val operitImport = DeepSeekImportController(context, viewModelScope, conversationRepository,
        source = ChatArchiveSource.OPERIT, assistantName = ::importAssistantName) {
        check(!settings.value.init)
        settings.value.assistantId
    }

    val kelivoImport = DeepSeekImportController(context, viewModelScope, conversationRepository,
        source = ChatArchiveSource.KELIVO, assistantName = ::importAssistantName) {
        check(!settings.value.init)
        settings.value.assistantId
    }

    val polarisImport = DeepSeekImportController(context, viewModelScope, conversationRepository,
        source = ChatArchiveSource.POLARIS, assistantName = ::importAssistantName) {
        check(!settings.value.init)
        settings.value.assistantId
    }

    val claudeImport = DeepSeekImportController(context, viewModelScope, conversationRepository,
        source = ChatArchiveSource.CLAUDE, assistantName = ::importAssistantName) {
        check(!settings.value.init)
        settings.value.assistantId
    }

    override fun onCleared() {
        chatGptImport.close()
        claudeImport.close()
        polarisImport.close()
        kelivoImport.close()
        operitImport.close()
        deepSeekImport.close()
        super.onCleared()
    }

    val chatGptImport = DeepSeekImportController(context, viewModelScope, conversationRepository,
        source = ChatArchiveSource.CHATGPT, assistantName = ::importAssistantName) {
        check(!settings.value.init)
        settings.value.assistantId
    }

    private val rikkaReceipts = rikkaReceiptOwner(context)
    val rikkaImportState = rikkaReceipts.state

    suspend fun dismissRikkaImportReceipt() = rikkaReceipts.dismiss()

    // Opening local backup must not contact previously configured remote accounts. URI staging is
    // inside the receipt boundary too, so leaving during the copy cannot resurrect an old success.
    suspend fun importRikkaChats(uri: Uri) = rikkaReceipts.import { progress ->
        val target = settingsStore.settingsFlow.value.let { check(!it.init); it.assistantId }
        val temp = File.createTempFile("orbis-rikka-import-", ".zip", context.cacheDir)
        try {
            val coroutine = currentCoroutineContext()
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                temp.outputStream().use { output ->
                    me.rerere.rikkahub.data.sync.importer.RikkaChatArchive.copyLimited(input, output,
                        me.rerere.rikkahub.data.sync.importer.RikkaChatArchive.MAX_ARCHIVE_BYTES,
                        { coroutine.ensureActive() },
                        { count -> me.rerere.rikkahub.data.sync.importer.ArchiveCapacity.requireSpace(context.cacheDir.usableSpace, count.toLong()) })
                }
            }
            me.rerere.rikkahub.data.sync.importer.RikkaChatImporter(context, conversationRepository, filesManager)
                .import(temp, target, progress)
        } finally { temp.delete() }
    }

    suspend fun importRikkaChats(file: File) = rikkaReceipts.import { progress ->
        val target = settingsStore.settingsFlow.value.let { check(!it.init); it.assistantId }
        me.rerere.rikkahub.data.sync.importer.RikkaChatImporter(context, conversationRepository, filesManager)
            .import(file, target, progress)
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value)
        )
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        @Volatile private var phoneRikkaReceipts: RikkaPhoneImportReceiptOwner? = null

        // Shared across replacement BackupVMs in this process; the small no-backup receipt also
        // survives Activity/process recreation. It contains no source filename or conversation text.
        @Synchronized private fun rikkaReceiptOwner(context: android.content.Context): RikkaPhoneImportReceiptOwner =
            phoneRikkaReceipts ?: run {
                val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "rikka-phone-import-receipt-v1.json"))
                RikkaPhoneImportReceiptOwner(read = {
                    try {
                        file.openRead().use { input ->
                            val bytes = ByteArray(RikkaPhoneImportReceiptOwner.MAX_BYTES + 1)
                            var size = 0
                            while (size < bytes.size) {
                                val count = input.read(bytes, size, bytes.size - size)
                                if (count < 0) break
                                size += count
                            }
                            require(size <= RikkaPhoneImportReceiptOwner.MAX_BYTES)
                            bytes.decodeToString(0, size, throwOnInvalidSequence = true)
                        }
                    } catch (failure: FileNotFoundException) {
                        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
                            File(file.baseFile.path + ".new").exists()) throw failure
                        null
                    }
                }, write = { raw ->
                    val output = file.startWrite()
                    try {
                        output.write(raw.toByteArray(Charsets.UTF_8)); output.fd.sync()
                        file.finishWrite(output)
                    } catch (failure: Throwable) { file.failWrite(output); throw failure }
                }).also { phoneRikkaReceipts = it }
            }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
