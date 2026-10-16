package me.rerere.rikkahub.ui.activity

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.character.CharacterRules
import me.rerere.rikkahub.data.orbis.character.resolveCharacterOwner
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import me.rerere.rikkahub.ui.pages.orbis.character.CharacterStage
import me.rerere.rikkahub.ui.pages.orbis.character.CharacterWidgetSettings
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.BuildConfig
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.widget.CharacterWidgetBindings
import me.rerere.rikkahub.ui.widget.CharacterWidgetConfigActivity
import org.koin.compose.koinInject

/** Private Activity: a stale/invalid widget never falls back to the current chat assistant. */
class OrbisCharacterActivity : ComponentActivity() {
    private var currentRoute by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentRoute = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentRoute = intent
        setContent {
            val widgetId = currentRoute?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
            val directOwner = currentRoute?.getStringExtra("character_owner")
            val developmentPreview = BuildConfig.DEBUG && currentRoute?.getBooleanExtra("development_room_preview", false) == true
            // A warm widget launch must discard the previous owner's stage immediately,
            // including its pending work and TTS, before resolving the new binding.
            key(widgetId, directOwner, developmentPreview) {
            val settings by koinInject<SettingsStore>().settingsFlow.collectAsStateWithLifecycle()
            var resolved by remember { mutableStateOf(false) }
            var owner by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(widgetId, directOwner) {
                owner = withContext(Dispatchers.IO) {
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        if (CharacterWidgetBindings.hosted(this@OrbisCharacterActivity, widgetId)) CharacterWidgetBindings.owner(this@OrbisCharacterActivity, widgetId) else null
                    } else directOwner?.takeIf(CharacterRules::owner)
                }
                resolved = true
            }
            val resolvedOwner = resolveCharacterOwner(owner, settings.assistants.map { it.id.toString() }, resolved && !settings.init)
            val assistant = settings.assistants.firstOrNull { it.id.toString() == resolvedOwner }
            MaterialTheme { OrbisVisualTheme {
                if (assistant != null) key(owner) {
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        val repository = koinInject<ConversationRepository>()
                        var failed by remember { mutableStateOf(false) }
                        LaunchedEffect(assistant.id) {
                            try {
                                val chat = repository.getRecentConversations(assistant.id, 1).firstOrNull()
                                    ?: Conversation.ofId(Uuid.random(), assistant.id).also { repository.insertConversation(it) }
                                startActivity(Intent(this@OrbisCharacterActivity, RouteActivity::class.java)
                                    .putExtra("conversationId", chat.id.toString())
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                finish()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                        }
                        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                            Text(if (failed) "未能打开绑定助手的聊天，请稍后再试。" else "正在打开${assistant.name}的聊天……")
                            TextButton(onClick = ::finish) { Text("返回") }
                        } }
                    } else if (developmentPreview) {
                        CharacterStage(owner!!, assistant.name.ifBlank { "伙伴" }, onClose = ::finish)
                    } else CharacterWidgetSettings(owner!!, assistant.name.ifBlank { "伙伴" }, onClose = ::finish,
                        onWorkshop = { startActivity(Intent(this@OrbisCharacterActivity, RouteActivity::class.java)
                            .putExtra("orbis_image_workshop_owner", owner)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) })
                }
                else Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                    if (settings.init || !resolved) { Text("正在读取助手配置……"); LinearProgressIndicator() }
                    else {
                        Text("这个入口尚未绑定，或原助手已不存在。不会自动打开其他助手。")
                        if (CharacterWidgetBindings.hosted(this@OrbisCharacterActivity, widgetId)) TextButton(onClick = {
                            startActivity(Intent(this@OrbisCharacterActivity, CharacterWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
                        }) { Text("重新选择助手") }
                    }
                    TextButton(onClick = ::finish) { Text("返回") }
                } }
            } }
            }
        }
    }
    companion object {
        fun launch(context: Context, owner: String) { require(CharacterRules.owner(owner)); context.startActivity(Intent(context, OrbisCharacterActivity::class.java).putExtra("character_owner", owner).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }
}
