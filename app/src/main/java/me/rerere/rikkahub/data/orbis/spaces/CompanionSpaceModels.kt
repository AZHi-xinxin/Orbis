package me.rerere.rikkahub.data.orbis.spaces

import kotlinx.serialization.Serializable

enum class CompanionSpaceSection(val title: String) { SECRET("秘密基地"), SOCIAL("共同空间"), PHOTOS("照片墙") }

@Serializable
data class SpaceStory(
    val id: String, val title: String, val prompt: String, val body: String = "",
    val paper: String = "letter", val revision: Int = 1, val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class SpaceComment(
    val id: String, val actor: String, val text: String, val replyTo: String? = null, val createdAt: Long,
)

@Serializable
data class SpacePost(
    val id: String, val actor: String, val text: String, val imageIds: List<String> = emptyList(),
    val likes: Set<String> = emptySet(), val comments: List<SpaceComment> = emptyList(), val createdAt: Long,
)

@Serializable
data class SpaceMedia(val id: String, val filename: String, val mime: String, val bytes: Int, val sha256: String)

@Serializable
data class SpacePhoto(
    val id: String, val mediaId: String, val note: String = "", val revision: Int = 1,
    val createdAt: Long, val sourceCallId: String? = null, val sourceFrameId: String? = null,
)

/** Retention receipts outlive deleting a photo: deleting must not reset the per-call allowance. */
@Serializable
data class SpaceVideoReceipt(val callId: String, val frameId: String, val photoId: String)

@Serializable
data class CompanionSpaceSnapshot(
    val version: Int = 1, val revision: Long = 0,
    val stories: List<SpaceStory> = emptyList(), val posts: List<SpacePost> = emptyList(),
    val photos: List<SpacePhoto> = emptyList(), val media: List<SpaceMedia> = emptyList(),
    val coverMediaId: String? = null, val wallStyle: String = "polaroid",
    val videoReceipts: List<SpaceVideoReceipt> = emptyList(),
)

internal object CompanionSpaceLimits {
    const val INDEX_BYTES = 12 * 1024 * 1024
    const val MEDIA_BYTES = 12 * 1024 * 1024
    const val TOTAL_MEDIA_BYTES = 512L * 1024 * 1024
    const val STORY_CHARS = 150_000
    const val POST_CHARS = 20_000
    const val ITEMS = 1_000
    val papers = setOf("letter", "kraft", "floral", "journal", "midnight")
    val walls = setOf("polaroid", "album", "hanging", "collage")
    fun id(value: String) = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    fun text(value: String, maximum: Int) {
        require(value.codePointCount(0, value.length) <= maximum && '\u0000' !in value) { "space_text_too_long" }
    }
}
