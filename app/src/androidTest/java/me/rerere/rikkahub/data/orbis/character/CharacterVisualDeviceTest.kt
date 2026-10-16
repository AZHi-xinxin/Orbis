package me.rerere.rikkahub.data.orbis.character

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.activity.OrbisCharacterActivity
import me.rerere.rikkahub.ui.widget.characterFaceComposite
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File

/** Optional local operator fixture, never bundled or executed on a physical device. */
@RunWith(AndroidJUnit4::class)
class CharacterVisualDeviceTest {
    @Test fun privateFixtureRendersWithIndependentFacesAndNativeMotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("character_visual_fixture") == "1")
        check(instrumentation is IsolatedGenerationLoopRunner)
        val qemu = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val isEmulator = android.os.ParcelFileDescriptor.AutoCloseInputStream(qemu).bufferedReader().use { it.readText().trim() }
        check(isEmulator == "1") { "Synthetic emulator only" }
        val context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val fixture = File(context.getExternalFilesDir(null), "dev81-visual-fixture.zip")
        val draft = fixture.inputStream().use(CharacterArchive::read)
        verifyCharacterDraft(draft)
        check(draft.interactions.lines.isEmpty() && draft.presentation?.greeting.orEmpty().isEmpty())
        val output = File(context.getExternalFilesDir(null), "dev81-visual-output").apply { check(mkdirs() || isDirectory) }
        fun save(name: String, bitmap: Bitmap) = File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        fun image(asset: CharacterAsset): Bitmap {
            val bytes = draft.assets.getValue(asset.sha256)
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 }))
        }
        val p = requireNotNull(draft.presentation)
        val poses = listOf("pose-fold", "pose-mug").map { id -> p.assets.single { it.id == id } }
        val faces = listOf("smile", "cry", "shy").map { emotion -> p.assets.first { it.kind == CharacterAssetKind.EXPRESSION && it.emotion == emotion } }
        val sheet = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(Color.rgb(220, 223, 235)) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 14f }
        poses.forEachIndexed { row, pose ->
            val base = image(pose)
            faces.forEachIndexed { col, face ->
                val expression = image(face)
                val anchor = requireNotNull(pose.faceAnchor)
                val result = characterFaceComposite(base, expression, anchor, requireNotNull(face.faceAnchor))
                val bodyStart = ((anchor.y + anchor.height) * base.height).toInt() + 1
                for (y in bodyStart until base.height) for (x in 0 until base.width) assertEquals(base.getPixel(x, y), result.getPixel(x, y))
                canvas.drawBitmap(result, col * 256f, row * 384f, null)
                canvas.drawText("${pose.id} / ${face.emotion}", col * 256f + 4, row * 384f + 16, paint)
                expression.recycle(); result.recycle()
            }
            base.recycle()
        }
        save("independent-faces.png", sheet); sheet.recycle()
        check(GlobalContext.getOrNull() == null)
        val dormant = AppScope().also { it.cancel() }
        val owner = Assistant(name = "Visual fixture")
        val store = openCharacterStore(context, owner.id.toString())
        val installed = store.install(draft, 0)
        store.save(installed.copy(motion = true, room = RoomMode.DAY), installed.revision)
        val settings = SettingsStore(context, dormant).also {
            it.settingsFlow.value = Settings(assistants = listOf(owner), assistantId = owner.id,
                providers = emptyList(), mcpServers = emptyList(), ttsProviders = emptyList())
        }
        startKoin { modules(module { single { settings }; single { TTSManager(context) } }) }
        try {
            val intent = Intent(context, OrbisCharacterActivity::class.java).putExtra("character_owner", owner.id.toString())
                .putExtra("development_room_preview", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<OrbisCharacterActivity>(intent).use {
                Thread.sleep(2200)
                instrumentation.waitForIdleSync()
                val first = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                save("native-room-a.png", first)
                Thread.sleep(1200)
                val second = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                save("native-room-b.png", second)
                assertEquals(first.width, second.width); assertEquals(first.height, second.height)
                // Header and room stay put while the central portrait has visible motion.
                var changed = 0
                for (y in first.height / 5 until first.height * 4 / 5 step 3)
                    for (x in first.width / 4 until first.width * 3 / 4 step 3)
                        if (first.getPixel(x,y) != second.getPixel(x,y)) changed++
                assertTrue("Native portrait should visibly breathe", changed > 30)
                first.recycle(); second.recycle()
            }
        } finally { stopKoin() }
    }
}
