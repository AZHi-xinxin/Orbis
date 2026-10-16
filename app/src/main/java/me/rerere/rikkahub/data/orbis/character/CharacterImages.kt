package me.rerere.rikkahub.data.orbis.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Call on IO, only for a human-picked URI; re-encode to preserve alpha and strip EXIF/location. */
fun importCharacterImage(context: Context, uri: Uri): CharacterDraft {
    require(uri.scheme == "content") { "character_image" }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.characterReadBounded(CharacterRules.IMAGE_BYTES) } ?: error("character_image")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..32000 && bounds.outHeight in 1..32000) { "character_image" }
    var sample = 1
    while ((max(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > CharacterRules.MAX_EDGE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("character_image")
    var upright = bitmap
    try {
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> postScale(-1f, 1f)
                3 -> postRotate(180f)
                4 -> postScale(1f, -1f)
                5 -> { postRotate(90f); postScale(-1f, 1f) }
                6 -> postRotate(90f)
                7 -> { postRotate(270f); postScale(-1f, 1f) }
                8 -> postRotate(270f)
            }
        }
        if (!matrix.isIdentity) upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        require(upright.compress(Bitmap.CompressFormat.PNG, 100, out)) { "character_image" }
        val png = out.toByteArray()
        require(png.size <= CharacterRules.IMAGE_BYTES) { "character_too_large" }
        return CharacterDraft(CharacterPack(sha256 = characterSha(png), width = upright.width, height = upright.height), CharacterInteractions(), png)
    } finally { if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
}

/** Decode, not extension sniffing. A ZIP cannot smuggle JPEG or invalid dimensions as PNG. */
fun verifyCharacterDraft(draft: CharacterDraft) {
    CharacterRules.validate(CharacterProfile(pack = draft.pack, interactions = draft.interactions, presentation = draft.presentation ?: CharacterPresentation()))
    require(draft.png.size <= CharacterRules.IMAGE_BYTES && characterSha(draft.png) == draft.pack.sha256) { "character_image" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(draft.png, 0, draft.png.size, bounds)
    require(bounds.outMimeType == "image/png" && bounds.outWidth == draft.pack.width && bounds.outHeight == draft.pack.height) { "character_image" }
    val decoded = BitmapFactory.decodeByteArray(draft.png, 0, draft.png.size) ?: error("character_image")
    decoded.recycle()
    draft.presentation?.assets?.forEach { asset ->
        val bytes = requireNotNull(draft.assets[asset.sha256]) { "character_image" }
        require(bytes.size <= CharacterRules.IMAGE_BYTES && characterSha(bytes) == asset.sha256) { "character_image" }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        require(b.outMimeType == "image/png" && b.outWidth == asset.width && b.outHeight == asset.height) { "character_image" }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("character_image")
        image.recycle()
    }
}

fun openCharacterStore(context: Context, owner: String) = CharacterStore(context.applicationContext.noBackupFilesDir, owner)

/** Call on IO. Existing user art is returned without loading or installing the bundled pack. */
internal fun ensureBuiltinCharacter(context: Context, store: CharacterStore): CharacterProfile =
    store.installDefaultIfEmpty { loadBuiltinCharacter(context) }

internal fun loadBuiltinCharacter(context: Context): CharacterDraft = BuiltinCharacter.load { name ->
    context.assets.open("${BuiltinCharacter.ROOT}/$name").use {
        it.characterReadBounded(if (name.endsWith(".json")) CharacterRules.JSON_BYTES else CharacterRules.IMAGE_BYTES)
    }
}.also(::verifyCharacterDraft)

fun characterError(error: Exception): String = when (error.message) {
    "character_conflict" -> "角色刚刚有更新，请退出此页再打开；没有覆盖新内容。"
    "character_missing" -> "请先导入人物图。"
    "character_too_large" -> "文件太大；图片限 16 MiB，请先缩小。"
    "character_regions" -> "触摸区域无效，请检查区域名称、大小和位置。"
    "character_lines" -> "台词不符合要求：每区最多 10 条，每条最多 240 字。"
    "character_assets" -> "素材清单不完整或尺寸无效；原设置没有改变。"
    "character_colors" -> "配色无效，请重新选择发色和瞳色。"
    "character_expression_policy" -> "表情范围无效，请至少选择一种允许的表情。"
    "character_face_anchor" -> "请先在高级设置给动作与表情配置同组面部区域，再切换表情。"
    "character_expression_assets_missing" -> "当前限时表情范围没有兼容此动作的素材；可在高级设置解除范围。"
    "character_archive", "character_format" -> "角色包格式不支持或不完整；旧角色没有被替换。"
    "character_image" -> "图片无法完整读取，或与角色包描述不符；请重选图片。"
    else -> "操作未完成，请检查文件与剩余空间；原角色和聊天不会被清空。"
}
