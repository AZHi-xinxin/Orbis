package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar as LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold as Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.lover.connect.ui.components.StarSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.contact.OrbisContactPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

private val UPDATE_PAUSE_DAY_OPTIONS = listOf(7, 14, 21)
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }
    val settingsStore = koinInject<SettingsStore>()
    val contactScope = rememberCoroutineScope()
    var contactSaving by remember { mutableStateOf(false) }
    var contactError by remember { mutableStateOf<String?>(null) }

    fun updateContact(transform: (OrbisContactPreferences) -> OrbisContactPreferences) {
        contactScope.launch {
            contactSaving = true
            contactError = null
            try { withContext(NonCancellable) { settingsStore.updateOrbisContact(transform) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { contactError = "联系设置未保存，请重试。" }
            finally { contactSaving = false }
        }
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = {
                            selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                            showUpdatePauseDialog = true
                        },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = {
                            Text(
                                if (updateChecksEnabled) {
                                    stringResource(R.string.setting_update_reminder_enabled)
                                } else {
                                    stringResource(
                                        R.string.setting_update_reminder_paused_until,
                                        Instant.ofEpochMilli(displaySetting.updateCheckDisabledUntilEpochMillis)
                                            .toLocalDateTime(),
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                    }
                                )
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text("AI 通知自动朗读") },
                        supportingContent = { Text("默认关闭。只朗读 AI 本轮成功推送的陪伴通知，不读取其他应用、闹钟或旧通知。使用当前全局 TTS 声音和语速；网络 TTS 会将通知文字交给你选定的语音服务。") },
                        trailingContent = {
                            Switch(checked = settings.orbisContact.notificationAutoRead,
                                enabled = !contactSaving && !settings.init,
                                onCheckedChange = { value -> updateContact { it.copy(notificationAutoRead = value) } })
                        },
                    )
                    item(
                        headlineContent = { Text("锁屏也朗读（可能被旁人听见）") },
                        supportingContent = { Text("默认关闭；不开启时只在已解锁状态朗读。通话、闹钟、其他播放、静音或勿扰期间跳过，不积压补播。") },
                        trailingContent = {
                            Switch(checked = settings.orbisContact.notificationReadWhenLocked,
                                enabled = settings.orbisContact.notificationAutoRead && !contactSaving && !settings.init,
                                onCheckedChange = { value -> updateContact { it.copy(notificationReadWhenLocked = value) } })
                        },
                    )
                    item(
                        headlineContent = { Text("允许 AI 主动来电") },
                        supportingContent = { Text("仍需你接听后才开始通话。拒接后冷却 ${settings.orbisContact.rejectCooldownMinutes} 分钟，期间不重复打扰。") },
                        trailingContent = {
                            Switch(checked = settings.orbisContact.allowIncomingCalls,
                                enabled = !contactSaving && !settings.init,
                                onCheckedChange = { value -> updateContact { it.copy(allowIncomingCalls = value) } })
                        },
                    )
                }
            }
            contactError?.let { message -> item { Text(message, modifier = Modifier.padding(horizontal = 16.dp)) } }
            item { OrbisIncomingCallHistoryButton() }
            item { OrbisRingtoneSettings() }
        }
    }

    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        UPDATE_PAUSE_DAY_OPTIONS.forEachIndexed { index, days ->
                            SegmentedButton(
                                selected = selectedUpdatePauseDays == days,
                                onClick = { selectedUpdatePauseDays = days },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = UPDATE_PAUSE_DAY_OPTIONS.size,
                                ),
                            ) {
                                Text(stringResource(R.string.setting_update_reminder_pause_days, days))
                            }
                        }
                    }
                    if (!updateChecksEnabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis =
                                    System.currentTimeMillis() + selectedUpdatePauseDays * MILLIS_PER_DAY,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
