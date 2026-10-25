package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

private const val COMMUNITY_NUMBER = "1129700652"

@Composable
internal fun OrbisCommunityCard() {
    val context = LocalContext.current
    val colors = OrbisTheme.colors
    val scope = rememberCoroutineScope()
    var showCode by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun copyNumber() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ST / Orbis QQ群", COMMUNITY_NUMBER))
        message = "群号已复制，可在 QQ 搜索加群"
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) scope.launch {
            saving = true
            message = try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
                    context.resources.openRawResource(R.drawable.orbis_community_qr).use { it.copyTo(out) }
                } }
                "二维码已保存"
            } catch (_: Exception) { "保存失败，请重试或复制群号" }
            finally { saving = false }
        }
    }
    Surface(color = colors.indigo.copy(alpha = .1f), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("一起把 Orbis 变得更好", color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("ST / Orbis · QQ 交流群", color = colors.mutedInk, fontSize = 14.sp)
            Text(COMMUNITY_NUMBER, color = colors.indigo, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("交流使用体验、反馈问题，或分享你的新发现。", color = colors.mutedInk, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = ::copyNumber) { Text("复制群号") }
                OutlinedButton(onClick = { showCode = true; message = null }) { Text("查看二维码") }
            }
            if (!showCode) message?.let { Text(it, color = colors.mutedInk, fontSize = 12.sp) }
        }
    }
    if (showCode) Dialog(onDismissRequest = { showCode = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = RoundedCornerShape(24.dp), color = colors.panel) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ST / Orbis 交流群", color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Image(painterResource(R.drawable.orbis_community_qr), "QQ 群 $COMMUNITY_NUMBER 入群二维码",
                        Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
                Text(message ?: "保存后在 QQ 扫一扫中选择相册；也可直接搜索群号。", color = colors.mutedInk, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving, onClick = {
                        if (Build.VERSION.SDK_INT < 29) export.launch("ST-Orbis-QQ-$COMMUNITY_NUMBER.jpg")
                        else scope.launch {
                            saving = true
                            message = try {
                                withContext(Dispatchers.IO) { saveCommunityCode(context) }
                                "已保存到相册，请用 QQ 扫一扫识别"
                            } catch (_: Exception) { "保存失败，请重试或复制群号" }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "正在保存" else "保存二维码") }
                    TextButton(onClick = ::copyNumber) { Text("复制群号") }
                    TextButton(onClick = { showCode = false }) { Text("关闭") }
                }
            }
        }
    }
}

private fun saveCommunityCode(context: Context) {
    check(Build.VERSION.SDK_INT >= 29)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "ST-Orbis-QQ-$COMMUNITY_NUMBER.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Orbis")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            context.resources.openRawResource(R.drawable.orbis_community_qr).use { it.copyTo(out) }
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
