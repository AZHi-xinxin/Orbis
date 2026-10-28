package me.rerere.rikkahub.ui.pages.setting

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.utils.openUrl

private const val SUPPORT_URL = "https://afdian.com/a/azhi1"
private const val SUPPORT_IMAGE_NAME = "Orbis-Afdian-azhi1.jpg"

@Composable
internal fun OrbisSupportCard() {
    val context = LocalContext.current
    val colors = OrbisTheme.colors
    val scope = rememberCoroutineScope()
    var showCode by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) scope.launch {
            saving = true
            message = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { out ->
                        context.resources.openRawResource(R.drawable.orbis_support_afdian).use { it.copyTo(out) }
                    }
                }
                "爱发电二维码已保存"
            } catch (_: Exception) { "保存失败，请重试或直接打开爱发电主页" }
            finally { saving = false }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(), color = colors.panel,
        shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, colors.border.copy(alpha = .65f)),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = colors.indigo.copy(alpha = .1f), shape = RoundedCornerShape(16.dp)) {
                    Icon(HugeIcons.Favourite, contentDescription = null,
                        modifier = Modifier.padding(13.dp).size(24.dp), tint = colors.indigo)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("在这里支持开发者", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("爱发电 · 沈止戈", color = colors.mutedInk, fontSize = 12.sp)
                }
            }
            Text("如果 Orbis 陪伴到了你，欢迎支持后续开发。谢谢每一份心意。",
                color = colors.mutedInk, fontSize = 13.sp, lineHeight = 21.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { context.openUrl(SUPPORT_URL) }) { Text("打开爱发电主页") }
                OutlinedButton(onClick = { showCode = true; message = null }) { Text("查看支持二维码") }
            }
            Text("afdian.com/a/azhi1 · 自愿支持", color = colors.mutedInk, fontSize = 11.sp)
        }
    }
    if (showCode) Dialog(
        onDismissRequest = { showCode = false }, properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 560.dp).fillMaxHeight(.9f),
            shape = RoundedCornerShape(24.dp), color = colors.panel) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("在这里支持开发者", color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Image(painterResource(R.drawable.orbis_support_afdian), "沈止戈的爱发电主页二维码",
                        Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
                Text(message ?: "可以保存图片后扫码，也可以直接打开爱发电主页。",
                    color = colors.mutedInk, fontSize = 12.sp, lineHeight = 18.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(enabled = !saving, onClick = {
                        if (Build.VERSION.SDK_INT < 29) export.launch(SUPPORT_IMAGE_NAME)
                        else scope.launch {
                            saving = true
                            message = try {
                                withContext(Dispatchers.IO) { saveSupportCode(context) }
                                "爱发电二维码已保存到相册"
                            } catch (_: Exception) { "保存失败，请重试或直接打开爱发电主页" }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "正在保存" else "保存二维码") }
                    TextButton(onClick = { context.openUrl(SUPPORT_URL) }) { Text("打开主页") }
                    TextButton(onClick = { showCode = false }) { Text("关闭") }
                }
            }
        }
    }
}

private fun saveSupportCode(context: Context) {
    check(Build.VERSION.SDK_INT >= 29)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, SUPPORT_IMAGE_NAME)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Orbis")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            context.resources.openRawResource(R.drawable.orbis_support_afdian).use { it.copyTo(out) }
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
