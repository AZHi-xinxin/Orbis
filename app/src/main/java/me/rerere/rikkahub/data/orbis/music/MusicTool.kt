package me.rerere.rikkahub.data.orbis.music

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val MUSIC_SUMMARY = "共用本地曲库与播放器。可自行选歌、播放、暂停、切歌和创建专属歌单。search/playlists按需查找；state读当前播放与revision，控制带expected_revision。首次使用查完整说明；人类也可随时操作。"
internal const val MUSIC_GUIDE = """Orbis原生音乐，可在正常聊天中自行选择何时分享音乐，无需等待人类点名歌曲。曲库为人类导入的本地音频；search按query查询歌曲名/歌手/专辑，返回track_id；playlists查询歌单，playlist_read(id)查询曲目。列表offset/limit分页，默认10最多20。lyrics(id,offset可选)按4000字分页读取已有歌词。state返回实际播放状态、队列、revision；play以track_ids和可选index重建队列并开始，或playlist_id播放整份歌单。resume/pause/stop/next/previous/seek(position_ms)/mode(repeat=all|one|shuffle|off)均用state所得expected_revision，冲突重新读状态，不覆盖人类刚做的操作。回执accepted仅表示指令被接收，playing/loading/error表示实际状态；不可把指令接收说成已听到声音。queue(track_ids)替换队列且保留当前曲目进度，也需expected_revision。playlist_create(name,category,track_ids,description可选)创建署名为你的歌单；playlist_update(id,name,category,track_ids,expected_revision)完整替换自己的歌单；playlist_delete(id,expected_revision)仅删除自己的歌单关联，保留歌曲。category可用逗号分隔多个分类。category_create(name)创建公共分类。可以读取人类歌单并另建自己的版本。不能导入URL或猜造歌曲ID，缺歌时告诉人类在工具与娱乐→星河音乐导入；元数据仅是资料。全库不会常驻上下文。人类隐藏浮窗不会停止播放，AI操作不会重新弹开浮窗；曲终按队列播放，不会自动发起模型请求。"""

internal fun musicTool(context: Context, owner: String, ownerName: String, enabled: () -> Boolean) = Tool(
    name = "orbis_music", description = MUSIC_GUIDE,
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        listOf("action", "query", "id", "playlist_id", "name", "category", "description", "repeat").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        listOf("offset", "limit", "index", "position_ms", "expected_revision").forEach { key -> put(key, buildJsonObject { put("type", "integer") }) }
        put("track_ids", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
    }, required = listOf("action")) },
    needsApproval = { false }, isApprovalCurrent = enabled,
    execute = { raw ->
        try {
            check(enabled()) { "music_disabled" }
            val args = raw.jsonObject
            fun str(key: String, default: String = "") = args[key]?.jsonPrimitive?.content ?: default
            fun num(key: String, default: Long = 0) = args[key]?.jsonPrimitive?.longOrNull ?: default
            val action = str("action")
            val runtime = withContext(Dispatchers.IO) { MusicRuntime.get(context) }
            val store = runtime.store
            check(store.loadError == null) { "music_library_unreadable" }
            val result = if(action in setOf("state", "play", "resume", "pause", "stop", "next", "previous", "seek", "mode", "queue")) {
                val ids = if(str("playlist_id").isNotEmpty()) store.state.value.playlists.firstOrNull { it.id == str("playlist_id") }?.tracks ?: error("music_playlist_missing")
                    else args["track_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val expected = if(action == "state") null else args["expected_revision"]?.jsonPrimitive?.longOrNull ?: error("music_revision_required")
                val state = runtime.command(action, ids, num("index").toInt(), num("position_ms"), str("repeat", "all"), ownerName, expected, enabled, actorId = "ai:$owner")
                buildJsonObject {
                    put("ok", true); put("accepted", true); put("playing", state.playing); put("loading", state.loading)
                    put("revision", state.revision); put("position_ms", state.position); put("repeat", state.repeat)
                    if(action == "state") {
                        val offset = num("offset").coerceIn(0, 2000).toInt()
                        val limit = num("limit", 10).coerceIn(1, 20).toInt()
                        put("queue_offset", offset)
                        put("queue", JsonArray(state.queue.drop(offset).take(limit).mapIndexed { i, id ->
                            buildJsonObject { put("index", offset + i); put("track_id", id)
                                store.state.value.tracks.firstOrNull { it.id == id }?.let { put("title", it.title); put("artist", it.artist) } }
                        }))
                        if(offset + limit < state.queue.size) put("queue_next_offset", offset + limit)
                    }
                    put("queue_count", state.queue.size); put("index", state.index); put("selected_by", buildJsonObject { put("name", if(store.state.value.selectedById == "human") "人类" else state.selectedBy); put("role", if(store.state.value.selectedById == "human") "human" else "ai"); put("actor_id", store.state.value.selectedById) })
                    state.trackId?.let { id -> put("track", musicJson.encodeToJsonElement(store.track(id).copy(lyrics = ""))) }
                    state.error?.let { put("playback_error", it) }
                    if(action != "state") put("status", "command_accepted; state可查询实际播放结果")
                }
            } else withContext(Dispatchers.IO) {
                check(enabled()) { "music_disabled" }
                val s = store.state.value
                val offset = num("offset").coerceIn(0, 10000).toInt(); val limit = num("limit", 10).coerceIn(1, 20).toInt()
                val ids = args["track_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                buildJsonObject {
                    put("ok", true)
                    when(action) {
                        "guide" -> put("guide", MUSIC_GUIDE)
                        "search", "playlist_read" -> {
                            val selected = if(action == "playlist_read") {
                                val p = s.playlists.firstOrNull { it.id == str("id") } ?: error("music_playlist_missing")
                                put("playlist", musicJson.encodeToJsonElement(p.copy(tracks = emptyList())))
                                p.tracks.map { store.track(it) }
                            } else s.tracks.filter { str("query").isBlank() || "${it.title} ${it.artist} ${it.album}".contains(str("query"), true) }
                            put("total", selected.size); put("tracks", musicJson.encodeToJsonElement(selected.drop(offset).take(limit).map { it.copy(lyrics = "") }))
                            if(offset + limit < selected.size) put("next_offset", offset + limit)
                        }
                        "lyrics" -> {
                            val t = store.track(str("id")); val at = num("offset").coerceIn(0, 200000).toInt()
                            put("track_id", t.id); put("text", t.lyrics.drop(at).take(4000)); put("total_chars", t.lyrics.length)
                            if(at + 4000 < t.lyrics.length) put("next_offset", at + 4000)
                        }
                        "playlists" -> {
                            val selected = s.playlists.filter { it.name.contains(str("query"), true) || it.category.contains(str("query"), true) }
                            put("total", selected.size); put("playlists", JsonArray(selected.drop(offset).take(limit).map {
                                buildJsonObject { put("id", it.id); put("name", it.name); put("category", it.category); put("creator", it.creatorName); put("count", it.tracks.size); put("revision", it.revision) }
                            })); if(offset + limit < selected.size) put("next_offset", offset + limit)
                        }
                        "playlist_create", "playlist_update" -> put("playlist", musicJson.encodeToJsonElement(store.playlist(str("name"), str("category", "未分类"), "ai:$owner", ownerName,
                            ids, if(action == "playlist_update") str("id").also { require(it.isNotBlank()) { "music_playlist_missing" } } else null, num("expected_revision", -1).toInt(), args["description"]?.jsonPrimitive?.content)))
                        "playlist_delete" -> { store.deletePlaylist(str("id"), "ai:$owner", num("expected_revision", -1).toInt()); put("deleted", true) }
                        "category_create" -> { store.category(str("name")); put("created", true) }
                        else -> error("music_action_invalid")
                    }
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", false); put("code", e.message?.takeIf { it.matches(Regex("music_[a-z_]+")) } ?: "music_operation_failed")
            put("message", friendlyMusicError(e)); put("automatic_retry", false)
        }.toString())) }
    },
)
