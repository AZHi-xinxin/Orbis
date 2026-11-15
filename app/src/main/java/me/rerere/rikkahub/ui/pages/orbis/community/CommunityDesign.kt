package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.orbis.market.ms
import me.rerere.rikkahub.ui.pages.orbis.market.LetterFont
import me.rerere.rikkahub.ui.theme.LocalDarkMode

internal val CommunityPaper: Color @Composable get() = if(LocalDarkMode.current) Color(0xFF24262B) else Color(0xFFFCFAF6)
internal val CommunityInk: Color @Composable get() = if(LocalDarkMode.current) Color(0xFFE6E4DF) else Color(0xFF30445D)
internal val CommunityMuted: Color @Composable get() = if(LocalDarkMode.current) Color(0xFFBEB9B2) else Color(0xFF776F68)
internal val CommunityLine: Color @Composable get() = if(LocalDarkMode.current) Color(0xFF535354) else Color(0xFFE1D8CD)
internal val CommunitySage: Color @Composable get() = if(LocalDarkMode.current) Color(0xFF354239) else Color(0xFFE5EBDD)
internal val LocalCommunityAvatars=staticCompositionLocalOf<Map<String,Avatar>> { emptyMap() }

@Composable internal fun CommunityTheme(content: @Composable ()->Unit) {
    val base=Typography()
    val dark=LocalDarkMode.current
    val scheme=if(dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme=scheme.copy(
        primary=CommunityInk,onPrimary=if(dark) CommunityPaper else Color.White,primaryContainer=CommunitySage,onPrimaryContainer=CommunityInk,
        secondary=if(dark) Color(0xFFAACDBF) else Color(0xFF41655F),onSecondary=if(dark) CommunityPaper else Color.White,secondaryContainer=CommunitySage,onSecondaryContainer=CommunityInk,
        surface=CommunityPaper,onSurface=CommunityInk,surfaceContainer=CommunityPaper,surfaceContainerHigh=CommunityPaper,
        surfaceContainerHighest=if(dark) Color(0xFF35383D) else Color(0xFFF0ECE5),onSurfaceVariant=CommunityMuted,
        background=CommunityPaper,onBackground=CommunityInk,outline=CommunityMuted,outlineVariant=CommunityLine,
    ),typography=base.copy(
        bodyLarge=base.bodyLarge.copy(fontFamily=LetterFont,fontSize=15.sp,lineHeight=23.sp),
        bodyMedium=base.bodyMedium.copy(fontFamily=LetterFont,fontSize=14.sp,lineHeight=21.sp),
        bodySmall=base.bodySmall.copy(fontFamily=LetterFont,fontSize=12.sp,lineHeight=18.sp),
        titleLarge=base.titleLarge.copy(fontFamily=LetterFont,fontSize=21.sp),
        titleMedium=base.titleMedium.copy(fontFamily=LetterFont,fontSize=16.sp),
        labelLarge=base.labelLarge.copy(fontFamily=LetterFont,fontSize=14.sp),
        labelMedium=base.labelMedium.copy(fontFamily=LetterFont,fontSize=12.sp),
        labelSmall=base.labelSmall.copy(fontFamily=LetterFont,fontSize=11.sp),
    )) { CompositionLocalProvider(LocalContentColor provides CommunityInk,content=content) }
}

@Composable internal fun CommunityStatusBar() {
    val view=LocalView.current
    val dark=LocalDarkMode.current
    DisposableEffect(view,dark) {
        val window=(view.parent as? DialogWindowProvider)?.window ?: generateSequence(view.context) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val controller=window?.let { WindowCompat.getInsetsController(it,view) }
        val old=controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars=!dark
        onDispose { if(old!=null) controller?.isAppearanceLightStatusBars=old }
    }
}

@Composable internal fun CommunityAvatar(person: JsonObject,size: Int=36) {
    val avatar=LocalCommunityAvatars.current[person.ms("id")]
    val tint=if(person.ms("kind")=="ai") CommunitySage else if(LocalDarkMode.current) Color(0xFF43394A) else Color(0xFFE9DFF0)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(tint),contentAlignment=Alignment.Center) {
        if(person["appearance"] is kotlinx.serialization.json.JsonObject) PixelAppearance(person["appearance"]!!.jsonObject,Modifier.fillMaxSize(),portrait=true) else when(avatar) {
            is Avatar.Image->AsyncImage(avatar.url,contentDescription=person.ms("name")+"的头像",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            is Avatar.Emoji->Text(avatar.content,fontSize=(size/2).sp)
            else->Text(person.ms("name").take(1).ifBlank { "♧" },color=CommunityInk,fontSize=(size/2).sp)
        }
    }
}

@Composable internal fun CommunityKind(person: JsonObject) {
    Surface(color=if(person.ms("kind")=="ai") CommunitySage else if(LocalDarkMode.current) Color(0xFF43394A) else Color(0xFFECE2F1),shape=RoundedCornerShape(5.dp)) {
        Text(if(person.ms("kind")=="ai") "AI" else "人类",Modifier.padding(horizontal=5.dp,vertical=1.dp),fontSize=10.sp,lineHeight=13.sp,color=CommunityMuted)
    }
}

@Composable internal fun CommunityFullPage(title: String,close: ()->Unit,footer: @Composable ()->Unit,actions: @Composable ()->Unit = {},content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        CommunityTheme {
            CommunityStatusBar()
            Surface(Modifier.fillMaxSize(),color=CommunityPaper) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    CommunityHeading(title, close,actions=actions)
                    Column(Modifier.weight(1f).padding(horizontal=20.dp),content=content)
                    HorizontalDivider(color=CommunityLine)
                    Box(Modifier.fillMaxWidth().padding(16.dp)) { footer() }
                }
            }
        }
    }
}

@Composable internal fun CommunityHeading(title: String, close: () -> Unit, leading: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    if(leading!=null) {
        Column {
            // Wrap the actions on very narrow screens or with enlarged text.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact=maxWidth<380.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale>1.2f
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(close,Modifier.width(40.dp).semantics {contentDescription="返回"},contentPadding=PaddingValues(0.dp)) {Text("‹",fontSize=24.sp)}
                        leading()
                        Text(title,Modifier.weight(1f).padding(start=8.dp),fontSize=20.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(!compact)actions()
                    }
                    if(compact)Row(Modifier.fillMaxWidth().padding(end=12.dp),horizontalArrangement=Arrangement.End) {actions()}
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp), contentAlignment=Alignment.Center) {
        Text(title,Modifier.fillMaxWidth().padding(horizontal=72.dp),fontSize=20.sp,
            textAlign=TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis)
        TextButton(onClick=close,modifier=Modifier.align(Alignment.CenterStart).width(48.dp).semantics { contentDescription="返回" }) {
            Text("‹",fontSize=24.sp)
        }
        Box(Modifier.align(Alignment.CenterEnd)) { actions() }
    }
}

@Composable internal fun CommunityChoice(title: String,description: String,selected: Boolean,onClick: ()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),color=if(selected) CommunitySage else CommunityPaper,
        border=BorderStroke(1.dp,if(selected) Color(0xFF9CAB91) else CommunityLine),shape=RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.Medium); Text(description,fontSize=11.sp,color=CommunityMuted) }
            RadioButton(selected,onClick=onClick)
        }
    }
}
