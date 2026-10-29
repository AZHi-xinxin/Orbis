package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("orbis_self_check")
    data object SelfCheck : LocalToolOption()

    @Serializable
    @SerialName("orbis_message_recall")
    data object MessageRecall : LocalToolOption()

    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("orbis_companion_native")
    data object CompanionDevice : LocalToolOption()

    @Serializable
    @SerialName("orbis_companion_memory")
    data object CompanionMemory : LocalToolOption()

    @Serializable
    @SerialName("orbis_bluetooth_toy")
    data object BluetoothToy : LocalToolOption()

    @Serializable
    @SerialName("orbis_local_garden")
    data object LocalGarden : LocalToolOption()

    @Serializable
    @SerialName("orbis_local_reading")
    data object LocalReading : LocalToolOption()

    @Serializable
    @SerialName("orbis_local_soup")
    data object LocalSoup : LocalToolOption()

    @Serializable
    @SerialName("orbis_local_gallery")
    data object LocalGallery : LocalToolOption()

    @Serializable
    @SerialName("orbis_human_health")
    data object HumanHealth : LocalToolOption()

    @Serializable
    @SerialName("orbis_desire")
    data object LocalDesire : LocalToolOption()

    @Serializable
    @SerialName("orbis_theme")
    data object ThemeDesign : LocalToolOption()

    @Serializable
    @SerialName("orbis_context_pruning")
    data object ContextPruning : LocalToolOption()
}
