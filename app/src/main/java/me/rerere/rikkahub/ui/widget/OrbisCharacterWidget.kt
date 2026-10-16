package me.rerere.rikkahub.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import android.util.TypedValue
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.character.CharacterRules
import me.rerere.rikkahub.data.orbis.character.CharacterAssetKind
import me.rerere.rikkahub.data.orbis.character.cycle
import me.rerere.rikkahub.data.orbis.character.widgetAsset
import me.rerere.rikkahub.data.orbis.character.widgetLayers
import me.rerere.rikkahub.data.orbis.character.characterCardNames
import me.rerere.rikkahub.data.orbis.character.openCharacterStore
import me.rerere.rikkahub.data.orbis.character.ensureBuiltinCharacter
import me.rerere.rikkahub.data.orbis.character.resolveCharacterOwner
import me.rerere.rikkahub.ui.activity.OrbisCharacterActivity
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.DataInputStream

/** Device-local bindings are deliberately excluded from portable packs and system backup. */
object CharacterWidgetBindings {
    private fun file(context: Context, id: Int): AtomicFile {
        require(id > 0)
        val root = File(context.noBackupFilesDir.canonicalFile, "orbis-character-widgets")
        require(root.canonicalFile == root)
        require(root.isDirectory || root.mkdirs())
        return AtomicFile(File(root, "$id.txt"))
    }
    @Synchronized fun owner(context: Context, id: Int): String? = runCatching {
        val bytes = DataInputStream(file(context, id).openRead()).use { stream -> val bytes = ByteArray(36); stream.readFully(bytes); require(stream.read() == -1); bytes }
        bytes.toString(Charsets.US_ASCII).takeIf(CharacterRules::owner)
    }.getOrNull()
    @Synchronized fun bind(context: Context, id: Int, owner: String) {
        require(CharacterRules.owner(owner) && hosted(context, id))
        val target = file(context, id); val out = target.startWrite()
        try { out.write(owner.toByteArray(Charsets.US_ASCII)); target.finishWrite(out) }
        catch (error: Exception) { target.failWrite(out); throw error }
    }
    @Synchronized fun remove(context: Context, id: Int) {
        if (id > 0) {
            val target = file(context, id); target.delete()
            check(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists() && !File(target.baseFile.path + ".new").exists()) { "character_binding_remove_failed" }
        }
    }
    /** Called before normal backup restoration, including a retry after interrupted restoration. */
    @Synchronized fun invalidateForRestore(context: Context) {
        val root = File(context.noBackupFilesDir.canonicalFile, "orbis-character-widgets")
        require(root.canonicalFile == root)
        root.listFiles().orEmpty().mapNotNull { it.name.substringBefore('.').toIntOrNull() }.distinct()
            .filter { it > 0 }.forEach { remove(context, it) }
        OrbisCharacterWidget.refresh(context)
    }
    fun hosted(context: Context, id: Int): Boolean = id > 0 &&
        AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(context, OrbisCharacterWidget::class.java)
}

class OrbisCharacterWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPRESSION && intent.action != ACTION_POSE) { super.onReceive(context, intent); return }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (!CharacterWidgetBindings.hosted(context, id)) return
        val pending = goAsync()
        worker.launch { try { runCatching { withTimeout(7_000) {
            val owner = CharacterWidgetBindings.owner(context, id) ?: return@withTimeout
            val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
            if (resolveCharacterOwner(owner, settings.assistants.map { it.id.toString() }, true) == null) return@withTimeout
            val store = openCharacterStore(context, owner)
            val p = store.read()
            val kind = if (intent.action == ACTION_EXPRESSION) CharacterAssetKind.EXPRESSION else CharacterAssetKind.POSE
            val next = p.presentation.cycle(kind, System.currentTimeMillis())
            if (next != p.presentation) store.save(p.copy(presentation = next), p.revision)
            update(context, id, immediatePlaceholder = false)
        } } } finally { pending.finish() } }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        worker.launch { try { runCatching { withTimeout(7_000) { ids.forEach { update(context, it) } } } } finally { pending.finish() } }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { CharacterWidgetBindings.remove(context, it) } }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        // Widget IDs and assistant IDs from another installation are not trustworthy bindings.
        (oldIds + newIds).forEach { CharacterWidgetBindings.remove(context, it) }
        onUpdate(context, AppWidgetManager.getInstance(context), newIds)
    }
    companion object {
        private const val ACTION_EXPRESSION = "org.orbis.character.EXPRESSION"
        private const val ACTION_POSE = "org.orbis.character.POSE"
        private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun refresh(context: Context) {
            val app = context.applicationContext
            worker.launch { runCatching { withTimeout(7_000) {
                AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, OrbisCharacterWidget::class.java)).forEach { update(app, it) }
            } } }
        }
        suspend fun update(context: Context, id: Int, immediatePlaceholder: Boolean = true) {
            if (!CharacterWidgetBindings.hosted(context, id)) return
            val views = RemoteViews(context.packageName, R.layout.orbis_character_widget)
            var stageHeightDp = 180
            if (Build.VERSION.SDK_INT >= 31) {
                val available = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).coerceAtLeast(150)
                stageHeightDp = minOf(180, available)
                views.setViewLayoutHeight(R.id.character_widget_canvas, stageHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
            views.setTextViewText(R.id.character_widget_title, "Orbis · 互动")
            views.setTextViewText(R.id.character_widget_hint, "点此选择助手")
            views.setImageViewResource(R.id.character_widget_image, R.mipmap.ic_launcher)
            val owner = CharacterWidgetBindings.owner(context, id)
            // Use the persisted explicit binding, never the currently selected / last chatted assistant.
            // Publish a safe placeholder immediately, including when settings cannot be read.
            val configure = Intent(context, CharacterWidgetConfigActivity::class.java).setData(Uri.parse("orbis-internal://character-widget/$id"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(R.id.character_widget_root, PendingIntent.getActivity(context, id, configure, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (immediatePlaceholder) AppWidgetManager.getInstance(context).updateAppWidget(id, views)
            val assistant = owner?.let {
                val settings = withTimeoutOrNull(4_000) { GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init } }
                val bound = resolveCharacterOwner(owner, settings?.assistants?.map { it.id.toString() }.orEmpty(), settings != null && !settings.init)
                settings?.assistants?.firstOrNull { it.id.toString() == bound }
            }
            var thumb: Bitmap? = null
            var card: Bitmap? = null
            try {
                if (assistant != null) {
                    views.setTextViewText(R.id.character_widget_title, assistant.name.take(40).ifBlank { "伙伴" })
                    views.setTextViewText(R.id.character_widget_hint, "点卡片，进入聊天")
                    runCatching {
                        val store = openCharacterStore(context, owner)
                        val p = ensureBuiltinCharacter(context, store)
                        views.setTextViewText(R.id.character_widget_hint, p.presentation.greeting.ifBlank { "点卡片，进入聊天" })
                        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                        // The full-screen portrait is deliberately not a fallback widget sprite.
                        val layers = p.presentation.widgetLayers(System.currentTimeMillis())
                        val asset = layers.pose
                        if (asset != null) {
                            val bytes = store.assetBytes(asset)
                            thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            layers.expression?.let { expression ->
                                val faceBytes = store.assetBytes(expression)
                                val face = BitmapFactory.decodeByteArray(faceBytes, 0, faceBytes.size, options)
                                if (face != null) try {
                                    thumb?.let { original ->
                                        thumb = characterFaceComposite(original, face, requireNotNull(asset.faceAnchor), requireNotNull(expression.faceAnchor))
                                        original.recycle()
                                    }
                                } finally { face.recycle() }
                            }
                            thumb?.let { original ->
                                val colored = characterColoredSprite(original, asset, p.presentation.colors, layers.expression?.emotion ?: asset.emotion)
                                thumb = colored
                                if (colored !== original) original.recycle()
                            }
                            thumb?.let { decoded ->
                                val framed = characterWidgetSprite(decoded, stageHeightDp, asset.trimBodyAtCardEdge)
                                thumb = framed
                                if (framed !== decoded) decoded.recycle()
                            }
                        }
                        thumb?.let { views.setImageViewBitmap(R.id.character_widget_image, it) }
                        p.presentation.assets.firstOrNull { it.kind == CharacterAssetKind.CARD && it.tag == p.presentation.card }?.let {
                            val bytes = store.assetBytes(it)
                            card = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            views.setImageViewBitmap(R.id.character_widget_card, card)
                        }
                        if (card == null) {
                            val cardName = p.presentation.card.takeIf { it in characterCardNames } ?: "cloud-blue"
                            card = context.assets.open("orbis-character/cards/$cardName.png").use { BitmapFactory.decodeStream(it, null, options) }
                            views.setImageViewBitmap(R.id.character_widget_card, card)
                        }
                        listOf(R.id.character_widget_head to ACTION_EXPRESSION, R.id.character_widget_hand to ACTION_POSE).forEach { (view, action) ->
                            val click = Intent(context, OrbisCharacterWidget::class.java).setAction(action)
                                .setData(Uri.parse("orbis-internal://widget-action/$id/$action"))
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            views.setOnClickPendingIntent(view, PendingIntent.getBroadcast(context, id, click, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        }
                    }
                } else if (owner != null) views.setTextViewText(R.id.character_widget_hint, "绑定待核对 · 点此重新选择")
                val target = if (assistant == null) CharacterWidgetConfigActivity::class.java else OrbisCharacterActivity::class.java
                val intent = Intent(context, target).setData(Uri.parse("orbis-internal://character-widget/$id"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(R.id.character_widget_root, PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                AppWidgetManager.getInstance(context).updateAppWidget(id, views)
            } finally { thumb?.recycle(); card?.recycle() }
        }
    }
}
