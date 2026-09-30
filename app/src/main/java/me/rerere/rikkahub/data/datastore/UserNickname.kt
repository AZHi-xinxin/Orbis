package me.rerere.rikkahub.data.datastore

/** Merge only the human nickname into the latest persisted display settings. */
internal fun DisplaySetting.withValidatedUserNickname(value: String): DisplaySetting {
    val nickname = value.trim()
    require(nickname.length <= 80 && nickname.none { it.isISOControl() }) {
        "昵称最多 80 个字符，且不能包含换行或控制字符。"
    }
    return copy(userNickname = nickname)
}
