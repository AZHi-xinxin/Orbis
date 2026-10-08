package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Only fixed local diagnostics reach the UI, never raw SQLite or source JSON exceptions. */
internal enum class RikkaChatReadStage(val explanation: String) {
    ARCHIVE("没有找到可读取的 RikkaHub 聊天备份结构，请确认导出时包含聊天记录"),
    DATABASE("聊天数据库无法完整读取，请保留原 ZIP 以便核查"),
    SCHEMA("聊天表结构无法安全识别；不是仅因版本号不同而拒绝，请保留原 ZIP 以便适配"),
    MESSAGE("消息内容或分支结构无法安全识别，请保留原 ZIP 以便适配"),
}

internal class RikkaChatReadException(stage: RikkaChatReadStage) : IllegalArgumentException(
    "${stage.explanation}。[RIKKA_${stage.name}]"
)

internal inline fun <T> readRikkaStage(stage: RikkaChatReadStage, read: () -> T): T = try {
    read()
} catch (failure: CancellationException) { throw failure
} catch (failure: ArchiveReadException) { throw failure
} catch (failure: RikkaChatReadException) { throw failure
} catch (failure: IOException) { throw failure
} catch (_: Exception) { throw RikkaChatReadException(stage) }
