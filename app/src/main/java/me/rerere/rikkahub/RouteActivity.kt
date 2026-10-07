package me.rerere.rikkahub

import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisStartupCover
import me.rerere.rikkahub.ui.pages.orbis.rememberOrbisStartupState
import me.rerere.rikkahub.data.model.OrbisStartupProcess
import me.rerere.rikkahub.data.model.shouldShowOrbisStartup

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.orbisChatUsesLightStatusIcons
import me.rerere.rikkahub.data.model.orbisChatLightStatusBarOverride
import me.rerere.rikkahub.ui.theme.LocalStatusBarAppearanceOverride
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.db.MigrationState
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.ui.activity.SafeModeActivity
import me.rerere.rikkahub.ui.components.ui.TTSController
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantBasicPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import me.rerere.rikkahub.ui.pages.assistant.detail.OrbisAssistantProfilePage
import me.rerere.rikkahub.ui.pages.assistant.detail.OrbisAssistantToolsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.OrbisCloudToolCredentialsPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.chat.LocalOrbisChatAnimationVisible
import me.rerere.rikkahub.ui.pages.orbis.LocalOpenOrbisHome
import me.rerere.rikkahub.ui.pages.orbis.OrbisHomeOverlay
import me.rerere.rikkahub.ui.pages.orbis.OrbisHomeNavigationState
import me.rerere.rikkahub.ui.pages.orbis.BindOrbisHomeNavigationFocus
import me.rerere.rikkahub.ui.pages.orbis.LocalReturnToOrbisChat
import me.rerere.rikkahub.ui.pages.orbis.OrbisSettingsPage
import me.rerere.rikkahub.ui.pages.orbis.OrbisToolsPage
import me.rerere.rikkahub.ui.pages.orbis.OrbisNativeToolSection
import me.rerere.rikkahub.ui.pages.orbis.OrbisNativeToolSelectionPanel
import me.rerere.rikkahub.ui.pages.orbis.toy.OrbisToyDevicePage
import me.rerere.rikkahub.ui.pages.orbis.OrbisPhonePage
import me.rerere.rikkahub.ui.pages.orbis.OrbisMemoryAtlasPage
import me.rerere.rikkahub.ui.pages.debug.DebugPage
import me.rerere.rikkahub.ui.pages.extensions.ExtensionsPage
import me.rerere.rikkahub.ui.pages.extensions.PromptPage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillDetailPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.ui.pages.favorite.FavoritePage
import me.rerere.rikkahub.ui.pages.history.HistoryPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.log.LogPage
import me.rerere.rikkahub.ui.pages.search.SearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNotificationPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingDonatePage
import me.rerere.rikkahub.ui.pages.setting.SettingFilesPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingSpeechPage
import me.rerere.rikkahub.ui.pages.setting.SettingWebPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.stats.StatsPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private var navStack: MutableList<NavKey>? = null
    private val orbisHomeNavigation = OrbisHomeNavigationState()
    private var showColdStart = false

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        // Consume on the first Activity, even a special entry. Warm returns, rotation,
        // shares and notification navigation must never unexpectedly replay an intro.
        val firstActivity = OrbisStartupProcess.gate.claim()
        showColdStart = shouldShowOrbisStartup(BuildConfig.ORBIS_ENABLED,
            (intent.action == Intent.ACTION_MAIN || intent.action == null) &&
                !intent.hasExtra("conversationId"), firstActivity)
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Foreground entry can restore a previously enabled companion even after
        // force-stop/process death. Never enable a new service or bypass safe mode.
        if (!isFinishing && !CrashHandler.hasCrashed(this) &&
            com.lover.connect.LcExternalRecoveryGate.isAllowed()) {
            com.lover.connect.McpServiceController.requestRecoveryIfEnabled(this, "host_foreground")
            com.lover.connect.LocationSafetyManager.restoreWhileForeground(this)
            com.lover.connect.AndroidCompanionAlarms.restoreAsync(this)
        }
    }

    @Composable
    private fun ShareHandler(backStack: MutableList<NavKey>) {
        val shareIntent = remember {
            Intent().apply {
                action = intent?.action
                putExtra(Intent.EXTRA_TEXT, intent?.getStringExtra(Intent.EXTRA_TEXT))
                putExtra(Intent.EXTRA_STREAM, intent?.getStringExtra(Intent.EXTRA_STREAM))
                putExtra(Intent.EXTRA_PROCESS_TEXT, intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            }
        }

        LaunchedEffect(backStack) {
            when (shareIntent.action) {
                Intent.ACTION_SEND -> {
                    val text = shareIntent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    val imageUri = shareIntent.getStringExtra(Intent.EXTRA_STREAM)
                    backStack.add(Screen.ShareHandler(text, imageUri))
                }

                Intent.ACTION_PROCESS_TEXT -> {
                    val text = shareIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
                    backStack.add(Screen.ShareHandler(text, null))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN || intent.hasExtra("conversationId")) {
            orbisHomeNavigation.returnToChat()
        }
        // Navigate to the chat screen if a conversation ID is provided
        intent.getStringExtra("conversationId")?.let { text ->
            navStack?.add(Screen.Chat(text))
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val incomingCalls = remember { me.rerere.rikkahub.service.OrbisIncomingCallRuntime.get(this@RouteActivity) }
        val incoming by incomingCalls.state.collectAsStateWithLifecycle()
        androidx.lifecycle.compose.LifecycleResumeEffect(incoming?.attempt?.id) {
            incoming?.takeIf { it.attempt.outcome == me.rerere.rikkahub.data.orbis.contact.IncomingCallOutcome.RINGING }?.let { call ->
                // Visible-app invitation; background delivery goes through the notification only.
                startActivity(Intent(this@RouteActivity, me.rerere.rikkahub.ui.activity.OrbisIncomingCallActivity::class.java).apply {
                    putExtra("attemptId", call.attempt.id); putExtra("callAction", "view")
                })
            }
            onPauseOrDispose { }
        }
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        val startScreen = remember { Screen.Chat(
            id = intent.getStringExtra("conversationId")?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }
                ?: if (readBooleanPreference("create_new_conversation_on_start", !BuildConfig.ORBIS_ENABLED)) {
                Uuid.random().toString()
            } else {
                readStringPreference(
                    "lastConversationId",
                    Uuid.random().toString()
                ) ?: Uuid.random().toString()
            }
        ) }

        val orbisVisible = orbisHomeNavigation.visible
        val orbisHomeRevision = orbisHomeNavigation.homeRevision
        BindOrbisHomeNavigationFocus(orbisHomeNavigation)

        val backStack = rememberNavBackStack(startScreen)
        val startup = rememberOrbisStartupState(
            enabled = showColdStart,
            targetChatId = startScreen.id,
            currentChatId = (backStack.lastOrNull() as? Screen.Chat)?.id?.takeUnless { orbisVisible },
            settingsReady = !settings.init,
            migrationVisible = migrationState is MigrationState.Migrating,
        )
        // The home overlay keeps ChatPage composed; only the actual visible, resumed route counts.
        val visibleSentinelChatId = (backStack.lastOrNull() as? Screen.Chat)?.id?.takeUnless { orbisVisible }
        androidx.lifecycle.compose.LifecycleResumeEffect(visibleSentinelChatId) {
            visibleSentinelChatId?.let { me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelPresence.enter(it) }
            onPauseOrDispose {
                visibleSentinelChatId?.let { me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelPresence.leave(it) }
            }
        }
        val chatDrawerVisibility = remember { mutableStateMapOf<Screen.Chat, Boolean>() }
        // One route owner, not one owner per composed NavDisplay entry: the home overlay
        // leaves ChatPage composed, and navigation transitions may retain older chats.
        val statusBarOverride = LocalStatusBarAppearanceOverride.current
        var orbisHomeLightStatusBars by remember { mutableStateOf<Boolean?>(null) }
        val lightChatStatusIcons = orbisChatUsesLightStatusIcons(
            settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current), LocalDarkMode.current)
        val routeStatusBarOverride = orbisChatLightStatusBarOverride(BuildConfig.ORBIS_ENABLED,
            backStack.lastOrNull() is Screen.Chat, orbisVisible, lightChatStatusIcons,
            drawerVisible = (backStack.lastOrNull() as? Screen.Chat)?.let { chatDrawerVisibility[it] } == true)
        SideEffect { statusBarOverride?.value = if (startup.visible) false
            else if (orbisVisible) orbisHomeLightStatusBars else routeStatusBarOverride }
        DisposableEffect(statusBarOverride) {
            onDispose { statusBarOverride?.value = null }
        }
        val returnToOrbisChat: () -> Unit = {
            // Release embedded focus before either home or back-stack mutations.
            orbisHomeNavigation.returnToChat()
            while (backStack.size > 1 && backStack.lastOrNull() !is Screen.Chat) {
                backStack.removeLastOrNull()
            }
        }
        SideEffect { this@RouteActivity.navStack = backStack }
        LaunchedEffect(backStack.lastOrNull()) {
            if (backStack.lastOrNull() != startScreen) orbisHomeNavigation.returnToChat()
        }

        ShareHandler(backStack)
        if (BuildConfig.ORBIS_ENABLED) me.rerere.rikkahub.ui.pages.orbis.OrbisUpdateReminder(
            enabled = !settings.init && incoming == null,
        )

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalOpenOrbisHome provides orbisHomeNavigation::openHome,
                LocalReturnToOrbisChat provides returnToOrbisChat,
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize().then(
                            if (orbisVisible || startup.visible) Modifier.clearAndSetSemantics { } else Modifier
                        ),
                        onBack = { backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                CompositionLocalProvider(LocalOrbisChatAnimationVisible provides
                                    (!orbisVisible && !startup.visible && backStack.lastOrNull() == key && chatDrawerVisibility[key] != true)) {
                                    ChatPage(
                                        id = Uuid.parse(key.id),
                                        text = key.text,
                                        files = key.files.map { it.toUri() },
                                        nodeId = key.nodeId?.let { Uuid.parse(it) },
                                        onDrawerVisibilityChange = { visible ->
                                            if (visible) chatDrawerVisibility[key] = true
                                            else chatDrawerVisibility.remove(key)
                                        },
                                        onStartupReady = if (startup.visible && key == startScreen)
                                            ({ startup.reportChatReady(key.id) }) else null,
                                    )
                                }
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.OrbisAssistantProfile> { key ->
                                OrbisAssistantProfilePage(key.id)
                            }
                            entry<Screen.OrbisAssistantTools> { key ->
                                OrbisAssistantToolsPage(key.id, key.family)
                            }
                            entry<Screen.OrbisCloudToolCredentials> {
                                OrbisCloudToolCredentialsPage()
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                if (BuildConfig.ORBIS_ENABLED) OrbisSettingsPage() else SettingPage()
                            }

                            entry<Screen.OrbisMcpSettings> {
                                if (BuildConfig.ORBIS_ENABLED) OrbisSettingsPage(startAtMcp = true) else SettingMcpPage()
                            }

                            entry<Screen.OrbisTools> {
                                OrbisToolsPage()
                            }
                            entry<Screen.OrbisSchedule> {
                                val navController = LocalNavController.current
                                me.rerere.rikkahub.ui.pages.orbis.OrbisSchedulePage(onBack = { navController.popBackStack() })
                            }
                            entry<Screen.OrbisToy> {
                                val navController = LocalNavController.current
                                OrbisToyDevicePage(onBack = { navController.popBackStack() }, toolSettings = {
                                    OrbisNativeToolSelectionPanel(OrbisNativeToolSection.TOY)
                                })
                            }
                            entry<Screen.OrbisPhone> {
                                OrbisPhonePage(hostVisible = !orbisVisible)
                            }
                            entry<Screen.OrbisMemoryAtlas> {
                                OrbisMemoryAtlasPage(hostVisible = !orbisVisible)
                            }
                            entry<Screen.OrbisGroups> {
                                me.rerere.rikkahub.ui.pages.orbis.OrbisGroupLandingPage()
                            }
                            entry<Screen.OrbisTechHub> {
                                me.rerere.rikkahub.ui.pages.orbis.OrbisTechHubPage(hostVisible = !orbisVisible)
                            }
                            entry<Screen.OrbisAiGroups> {
                                me.rerere.rikkahub.ui.pages.orbis.OrbisAiGroupsPage(hostVisible = !orbisVisible)
                            }
                            entry<Screen.OrbisConsultation> {
                                me.rerere.rikkahub.ui.pages.orbis.OrbisConsultationPage(hostVisible = !orbisVisible)
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferences> {
                                SettingPreferencesPage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id, showModels = key.showModels)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingDonate> {
                                SettingDonatePage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }
                        }
                    )
                    if (BuildConfig.ORBIS_ENABLED) {
                        OrbisHomeOverlay(
                            visible = orbisVisible,
                            homeRevision = orbisHomeRevision,
                            onOpenChat = returnToOrbisChat,
                            onStatusBarAppearance = { orbisHomeLightStatusBars = it },
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    if (migrationState !is MigrationState.Migrating) OrbisStartupCover(startup)
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class OrbisAssistantProfile(val id: String) : Screen

    @Serializable
    data class OrbisAssistantTools(val id: String, val family: String) : Screen

    @Serializable
    data object OrbisCloudToolCredentials : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object OrbisMcpSettings : Screen

    @Serializable
    data object OrbisTools : Screen

    @Serializable
    data object OrbisSchedule : Screen

    @Serializable
    data object OrbisToy : Screen

    @Serializable
    data object OrbisPhone : Screen

    @Serializable
    data object OrbisMemoryAtlas : Screen

    @Serializable
    data object OrbisGroups : Screen

    @Serializable
    data object OrbisTechHub : Screen

    @Serializable
    data object OrbisAiGroups : Screen

    @Serializable
    data object OrbisConsultation : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String, val showModels: Boolean = false) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}
