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

/** A fixed check name pinpoints rejection without exporting source identifiers, rows or SQL text. */
internal enum class RikkaSchemaCheck(val explanation: String) {
    OPEN("聊天数据库未能以只读方式打开"),
    TABLE("缺少普通聊天表，或同名对象不是支持的数据表"),
    COLUMNS("聊天表的必要字段类型不兼容"),
    LEGACY_NODES("发现仍保存在旧字段中的消息，不能直接忽略这些原文"),
    ROW_TYPES("聊天行的字段类型或消息序号不符合要求"),
    DUPLICATE_IDS("发现重复的聊天或消息编号"),
    NODE_ORDER("同一聊天的消息位置重复，不能安全决定先后顺序"),
    ORPHAN_NODES("发现没有对应聊天窗口的消息"),
    SOURCE_IDS("聊天或消息编号格式不受支持"),
    ROW_SIZE("单条消息或标题超过当前安全读取大小"),
    ROW_COUNT("聊天或消息数量超过当前一次导入的上限"),
    TITLE_SIZE("聊天标题总量超过当前一次导入的上限"),
}

internal class RikkaChatReadException(stage: RikkaChatReadStage, detail: RikkaSchemaCheck? = null) : IllegalArgumentException(
    if (stage == RikkaChatReadStage.SCHEMA && detail != null)
        "${detail.explanation}；已停止导入，原聊天未更改。请保留未修改的原 ZIP，不要删表或清数据。[RIKKA_SCHEMA/${detail.name}]"
    else "${stage.explanation}。[RIKKA_${stage.name}]"
)

internal fun requireRikkaSchema(condition: Boolean, check: RikkaSchemaCheck) {
    if (!condition) throw RikkaChatReadException(RikkaChatReadStage.SCHEMA, check)
}

internal inline fun <T> readRikkaStage(stage: RikkaChatReadStage, read: () -> T): T = try {
    read()
} catch (failure: CancellationException) { throw failure
} catch (failure: ArchiveReadException) { throw failure
} catch (failure: RikkaChatReadException) { throw failure
} catch (failure: IOException) { throw failure
} catch (_: Exception) { throw RikkaChatReadException(stage) }
