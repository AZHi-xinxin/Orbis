package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*

/** A whole destination, not a settings sheet containing a miniature destination. */
@Composable
internal fun CompanionWindow(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    val colors = if (dark) darkColorScheme(
        primary=Color(0xffbdd0a7), onPrimary=Color(0xff223220),
        background=Color(0xff202923), onBackground=Color(0xffe6e8db),
        surface=Color(0xff2b332d), onSurface=Color(0xffe6e8db),
        secondaryContainer=Color(0xff394631), onSecondaryContainer=Color(0xffe6e8db),
        surfaceContainer=Color(0xff303b31), surfaceContainerHighest=Color(0xff3a4438),
        onSurfaceVariant=Color(0xffbac2b4), outlineVariant=Color(0xff4a5848), outline=Color(0xff82917c),
    ) else lightColorScheme(
        primary=Color(0xff526c48), onPrimary=Color(0xfffffdf4),
        background=Color(0xfff7f4e8), onBackground=Color(0xff3e493f),
        surface=Color(0xfffffcf3), onSurface=Color(0xff3e493f),
        secondaryContainer=Color(0xffe5ead6), onSecondaryContainer=Color(0xff3e493f),
        surfaceContainer=Color(0xffeeecdf), surfaceContainerHighest=Color(0xffe5e1d0),
        onSurfaceVariant=Color(0xff65705f), outlineVariant=Color(0xffdedfce), outline=Color(0xff8a957e),
    )
    // Preserve the user's font family and system font scale; only this destination's
    // hierarchy and palette change. No global theme or chat preference is written.
    val base=MaterialTheme.typography
    val type=base.copy(
        headlineSmall=base.headlineSmall.copy(fontSize=24.sp,lineHeight=32.sp),
        titleLarge=base.titleLarge.copy(fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=base.titleMedium.copy(fontSize=18.sp,lineHeight=26.sp),
        bodyLarge=base.bodyLarge.copy(fontSize=16.sp,lineHeight=24.sp),
        bodyMedium=base.bodyMedium.copy(fontSize=14.sp,lineHeight=22.sp),
        bodySmall=base.bodySmall.copy(fontSize=12.sp,lineHeight=18.sp),
        labelLarge=base.labelLarge.copy(fontSize=14.sp,lineHeight=20.sp),
        labelMedium=base.labelMedium.copy(fontSize=12.sp,lineHeight=18.sp),
    )
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        DisposableEffect(view,dark) {
            val window=(view.parent as? DialogWindowProvider)?.window
            val controller=window?.let { WindowCompat.getInsetsController(it,view) }
            val oldStatus=controller?.isAppearanceLightStatusBars
            val oldNav=controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars=!dark
            controller?.isAppearanceLightNavigationBars=!dark
            onDispose {
                if(oldStatus!=null) controller.isAppearanceLightStatusBars=oldStatus
                if(oldNav!=null) controller.isAppearanceLightNavigationBars=oldNav
            }
        }
        MaterialTheme(colorScheme=colors,typography=type) {
            Surface(Modifier.fillMaxSize().testTag("companion-window"),color=colors.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),content=content)
            }
        }
    }
}

@Composable
internal fun CompanionHeading(title:String, subtitle:String, onBack:()->Unit, onSettings:()->Unit, avatar:String?=null) {
    Row(Modifier.fillMaxWidth().padding(start=4.dp,end=16.dp,top=8.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) {Icon(HugeIcons.ArrowLeft01,"返回聊天",Modifier.size(22.dp))}
        Column(Modifier.weight(1f).padding(end=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(title,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Surface(shape=CircleShape,color=if(avatar==null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            border=if(avatar==null) BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant) else null) {
            IconButton(onClick=onSettings,modifier=Modifier.semantics {contentDescription=if(avatar==null) "照料与设置" else "学习机设置"}) {
                if(avatar==null) Icon(HugeIcons.MoreVertical,null,Modifier.size(21.dp))
                else Text(avatar.takeLast(1),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onPrimary,
                    modifier=Modifier.testTag("study-settings-avatar"))
            }
        }
    }
}

internal data class CompanionTab(val id:String,val title:String,val icon:ImageVector)
internal val petTabs get()=listOf(CompanionTab("home","小窝",HugeIcons.Home01),CompanionTab("diary","照料日记",HugeIcons.Book02),CompanionTab("decorate","布置",HugeIcons.Settings02))
internal val studyTabs get()=listOf(CompanionTab("desk","书桌",HugeIcons.BookOpen01),CompanionTab("library","题库",HugeIcons.Bookshelf01),CompanionTab("wrong","错题本",HugeIcons.Book02),CompanionTab("history","学习足迹",HugeIcons.Time02))

@Composable
internal fun CompanionNavigation(tabs:List<CompanionTab>,selected:String,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth().testTag("companion-navigation").padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        tabs.forEach { tab ->
            val active=tab.id==selected
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                .background(if(active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics {this.selected=active}.clickable(role=Role.Tab) {onSelect(tab.id)}.padding(vertical=10.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Icon(tab.icon,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.primary)
                Text(tab.title,style=MaterialTheme.typography.labelMedium,maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun CompanionSettings(title:String,enabled:Boolean,onToggle:(Boolean)->Unit,onDismiss:()->Unit,description:String,extra:@Composable ColumnScope.()->Unit={}) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Text("启用此能力",Modifier.weight(1f));Switch(enabled,onToggle)}
            Text(description,style=MaterialTheme.typography.bodySmall)
            extra()
        }
    },confirmButton={TextButton(onClick=onDismiss) {Text("完成")}})
}

@Composable
internal fun CompanionEntryRow(title:String,detail:String,icon:ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick=onClick).padding(horizontal=4.dp,vertical=13.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.bodyMedium);Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Icon(HugeIcons.ArrowRight01,null,Modifier.size(18.dp))
    }
}
