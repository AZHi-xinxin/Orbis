package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.animation.core.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.orbis.activities.*
import kotlin.math.abs
import kotlin.math.sin
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PetInteractiveHome(state: PetState, busy: Boolean, artRoot: File?, ownerName:String="",onClose:()->Unit={},onSettings:()->Unit={},onAi:()->Unit={},onAction: (String, String?) -> Unit) {
    val pet=state.pet ?: return
    val appearance=PetCatalog.find(pet.appearance)
    var pane by rememberSaveable(pet.id) { mutableStateOf("home") }
    var original by rememberSaveable(pet.id) { mutableStateOf(true) }
    var reduceMotion by rememberSaveable(pet.id) { mutableStateOf(false) }
    var action by remember(pet.id) { mutableStateOf("") }
    var handled by remember(pet.id) { mutableStateOf(state.events.lastOrNull()?.id) }
    val last=state.events.lastOrNull()
    LaunchedEffect(last?.id) {
        if(last!=null && last.id!=handled) {
            handled=last.id
            for(step in petAnimationSteps(last.action, last.aiActions)) {
                action=step.action
                delay(step.durationMillis)
            }
            action=""
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active=lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !reduceMotion && pane=="home"
    var idleCycle by remember(pet.id) { mutableIntStateOf(0) }
    LaunchedEffect(active, pet.id) {
        if(active) while(true) { delay(4000);idleCycle=(idleCycle+1)%3 }
    }
    val messAlpha by animateFloatAsState(if(state.messy) 1f else 0f,
        if(active) tween(1800) else snap(), label="simple-room-tidy-crossfade")
    val phase=if(active) {
        val transition=rememberInfiniteTransition(label="pet-room-idle")
        val value by transition.animateFloat(0f,1f,infiniteRepeatable(tween(4000,easing=LinearEasing)),label="pet-breath-blink")
        value
    } else 0f
    val sleeping=state.sleepingUntil>System.currentTimeMillis() && action !in setOf("pet","play","feed","water","approach_food","approach_water","greet","content")
    // The larger original artwork stays in the central floor lane. Its transparent
    // bounds must not cover the small bed/toy captions, including at large type.
    val targetX=when {
        sleeping -> if(original) .27f else .17f
        action in setOf("feed","approach_food") -> if(original) .46f else .26f
        action in setOf("water","approach_water") -> if(original) .54f else .73f
        action in setOf("play","ask_play","mess_up") -> if(original) .54f else .64f
        else -> .49f
    }
    // Anchor the feet to the room, not the top of a width-only sprite. This keeps
    // the pet on its furniture and clear of captions on short / large-font screens.
    val targetY=when { sleeping -> .55f;action in setOf("feed","water","approach_food","approach_water") -> .88f;action=="play" -> .78f;else -> .82f }
    val x by animateFloatAsState(targetX,if(active) tween(650) else snap(),label="pet-walk-x")
    val y by animateFloatAsState(targetY,if(active) tween(650) else snap(),label="pet-walk-y")
    val dynamic=appearance.family=="cat" && !original
    val scheme=MaterialTheme.colorScheme
    val dark=scheme.background.luminance()<.35f
    val wall=if(dark) Color(0xff3b4032) else Color(0xffe9e4ce)
    val floor=if(dark) Color(0xff4c4935) else Color(0xffd4c9a6)
    val wood=if(dark) Color(0xff927e57) else Color(0xffa08863)
    val window=if(dark) Color(0xff79765e) else Color(0xfffdf8e8)
    val rug=when(state.decoration) { "rose" -> Color(0xffcca296); "sky" -> Color(0xffa7bcc0);else -> Color(0xffaeaf88) }
    Column(Modifier.fillMaxSize().testTag("pet-destination")) {
    CompanionHeading("${pet.name}的小窝",if(ownerName.isBlank()) "ORBIS · 一起照料" else "${ownerName}和你的",onClose,{pane="care"})
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        PetMeter("饱食 ${state.food.toInt()}",false)
        PetMeter("饮水 ${state.water.toInt()}",true)
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(HugeIcons.Favourite,null,Modifier.size(15.dp));Text("心情 ${state.mood.toInt()}",style=MaterialTheme.typography.labelMedium)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("pet-room-scene").background(wall)) {
        val roomW=maxWidth;val roomH=maxHeight
        if(original) Crossfade(state.messy, Modifier.fillMaxSize(),
            animationSpec=if(active) tween(1800) else snap(), label="room-tidy-crossfade") { messy ->
            AsyncImage(PetAssets.model(pet.appearance,if(messy) "messy.png" else "tidy.png",artRoot),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        }
        else Canvas(Modifier.fillMaxSize()) {
            val scale=size.width/600f
            val h=size.height/scale
            val floorY=h*.55f
            withTransform({scale(scale,scale,Offset.Zero)}) {
                for(i in 0..18) drawLine(wood.copy(alpha=.06f),Offset(i*34f,0f),Offset(i*34f,floorY),2f)
                drawRect(floor,Offset(0f,floorY),Size(600f,h-floorY))
                drawRect(wood.copy(alpha=.45f),Offset(0f,floorY-9f),Size(600f,9f))
                for(i in 1..5) drawLine(wood.copy(alpha=.19f),Offset(0f,floorY+i*(h-floorY)/6),Offset(600f,floorY+i*(h-floorY)/6),2f)
                for(i in 0..2) drawLine(wood.copy(alpha=.12f),Offset(110f+i*185,floorY),Offset(35f+i*260,h),1.4f)
                // Arched window: straight sill, soft sky and hills, not a blue settings tile.
                val wy=h*.12f
                // Keep the sill above the bed when large type leaves a short room.
                val windowScale=minOf(1f,((floorY-wy-60f)/209f).coerceAtLeast(.35f))
                withTransform({scale(1f,windowScale,Offset(120f,wy))}) {
                val arch=Path().apply {moveTo(30f,wy+200);lineTo(30f,wy+85);cubicTo(30f,wy-29,210f,wy-29,210f,wy+85);lineTo(210f,wy+200);close()}
                drawPath(arch,window)
                val glass=Path().apply {moveTo(42f,wy+188);lineTo(42f,wy+85);cubicTo(42f,wy-12,198f,wy-12,198f,wy+85);lineTo(198f,wy+188);close()}
                drawPath(glass,if(dark) Color(0xff405958) else Color(0xffbed2cb))
                val hill=Path().apply {moveTo(42f,wy+149);quadraticTo(128f,wy+73,198f,wy+94);lineTo(198f,wy+188);lineTo(42f,wy+188);close()}
                drawPath(hill,if(dark) Color(0xff657955) else Color(0xff9cb293))
                drawLine(window,Offset(120f,wy+3),Offset(120f,wy+194),9f)
                drawLine(window,Offset(36f,wy+115),Offset(205f,wy+115),9f)
                drawRoundRect(wood.copy(alpha=.16f),Offset(30f,wy+200),Size(186f,9f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f))
                }
                val shelfY=h*.29f
                drawRect(wood,Offset(410f,shelfY),Size(157f,12f))
                for(i in 0..3) rotate(if(i==1) -8f else 0f,Offset(437f+i*32f,shelfY)) {
                    drawRoundRect(if(i==1) Color(0xffb58770) else Color(0xff9eaa86),Offset(423f+i*32f,shelfY-55-(i%2)*10),Size(23f,55f+(i%2)*10),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f))
                    drawRect(window,Offset(423f+i*32f,shelfY-8),Size(23f,5f))
                }
                rotate(-8f,Offset(300f,h*.76f)) {
                    drawOval(rug,Offset(96f,h*.67f),Size(400f,h*.19f))
                    drawOval(window.copy(alpha=.4f),Offset(108f,h*.68f),Size(376f,h*.17f),style=Stroke(7f))
                }
                drawOval(Color(0xffd0b391),Offset(25f,floorY-52f),Size(132f,65f))
                drawOval(Color(0xffb39373),Offset(41f,floorY-39f),Size(100f,37f))
                drawLine(wood,Offset(480f,h*.60f),Offset(548f,h*.53f),7f)
                drawOval(Color(0xffb58770),Offset(532f,h*.53f),Size(27f,40f))
                if(messAlpha > 0f) {
                    for(i in 0..9) drawCircle(wood.copy(alpha=messAlpha),3f,Offset(35f+i*11f,h*.84f+(i%3)*10f))
                    for(i in 0..4) rotate((i*17).toFloat(),Offset(24f+i*17f,h*.65f+i*13f)) {drawRect(window.copy(alpha=messAlpha),Offset(24f+i*17f,h*.65f+i*13f),Size(19f,7f))}
                    rotate(-25f,Offset(64f,h*.8f)) {drawRoundRect(wood.copy(alpha=messAlpha),Offset(27f,h*.8f-10),Size(70f,18f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f))}
                }
                bowl(156f,h*.90f,Color(0xffb99573),Color(0xff816448),false)
                bowl(444f,h*.90f,Color(0xff97b4b3),Color(0xffc1d3cf),true)
            }
        }
        // Only decorative: remove when space is tight instead of shrinking the
        // user's accessibility font or allowing the note to cover the toy.
        if(!original && LocalDensity.current.fontScale<=1.3f && roomH>=360.dp) Surface(Modifier.align(Alignment.TopEnd).padding(top=roomH*.32f,end=roomW*.10f).widthIn(max=100.dp),
            color=if(dark) Color(0xff666044) else Color(0xfffaf1ca),shadowElevation=2.dp) {
            Text("今天也一起\n慢慢过吧",Modifier.padding(10.dp),fontSize=12.sp,lineHeight=20.sp,color=if(dark) Color(0xfff5eccc) else Color(0xff746449))
        }
        // Feeding feet are at .88h, and the bed caption ends at .57h + 28dp.
        // Reserve that caption band even on a short display with large type.
        val actorH=if(original) minOf(roomW*.54f,roomH*.38f)
            else minOf(roomW*.28f*130f/120f,roomH*.24f,(roomH*.31f-34.dp).coerceAtLeast(36.dp))
        val actorW=actorH*120f/130f
        val actor=Modifier.offset(x=(roomW*x-actorW/2).coerceIn(0.dp,(roomW-actorW).coerceAtLeast(0.dp)),y=roomH*y-actorH).width(actorW).height(actorH)
            // A transparent animal is not a rectangular button: keep the hit area and
            // accessibility action, but do not tint its entire transparent bounds on touch.
            .semantics { contentDescription="摸摸${pet.name}" }.clickable(
                interactionSource=remember(pet.id) { MutableInteractionSource() }, indication=null,
                enabled=!busy,role=Role.Button) {onAction("pet",null)}
        if(dynamic) Canvas(actor) {
            withTransform({scale(size.width/120f,size.height/130f,Offset.Zero)}) {
                drawCat(pet.appearance,phase,sleeping,action,abs(x-targetX)>.008f)
            }
        } else PetPoseSprite(pet.appearance,
            if(action.isEmpty() && !sleeping && active) petIdlePose(phase,idleCycle) else petPose(action, sleeping, phase),
            artRoot, active, actor.graphicsLayer {
            // Animate the original transparent animal, never its backdrop or the hit targets.
            val wave = sin(phase * 6.283185f)
            scaleY = 1f + wave * .008f
            scaleX = if (action in setOf("pet","ask_pet")) 1.035f else 1f
            rotationZ = when(action) { "play", "ask_play", "mess_up", "noisy" -> wave * 2f; else -> 0f }
            translationY = if(action in setOf("play","ask_play","noisy") && active) -kotlin.math.abs(wave) * 12.dp.toPx() else 0f
            alpha = if(state.health < 30) .8f else 1f
        })
        if(sleeping) Text("z Z", Modifier.offset(x=roomW*x+actorW*.25f,y=roomH*y-actorH),style=MaterialTheme.typography.labelMedium)
        val signal = when {
            sleeping -> null
            action in setOf("pet","ask_pet","lonely") -> "♡"
            state.health < 30 -> "需要照料"
            state.water < 25 || action == "thirsty" -> "想喝水"
            state.food < 25 || action == "hungry" -> "想吃东西"
            action == "ask_play" -> "陪我玩？"
            action == "noisy" -> if(appearance.family == "cat") "喵～" else if(appearance.family == "dog") "汪～" else if(appearance.family == "bird") "啾～" else "✧"
            else -> null
        }
        signal?.let { Text(it,Modifier.offset(x=roomW*x-24.dp,y=roomH*y-actorH-22.dp),
            color=scheme.onSurface,style=MaterialTheme.typography.labelSmall) }
        val note=when(action) {"approach_food","feed"->"添好粮了";"approach_water","water"->"换好了清水";"pet"->"${pet.name}蹭了蹭你的手";"play"->"一起玩一会儿";"tidy"->"房间收拾好了";else->if(sleeping) "休息中，也可以继续照料" else "点点房间里的物件，或者摸摸它"}
        Surface(Modifier.align(Alignment.TopCenter).padding(horizontal=38.dp,vertical=12.dp).fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=scheme.surface) {
            Text(note,Modifier.padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
        @Composable fun hotspot(label:String,id:String,px:Float,py:Float) {
            Box(Modifier.offset(x=(roomW*px-32.dp).coerceIn(0.dp,(roomW-64.dp).coerceAtLeast(0.dp)),y=(roomH*py-28.dp).coerceIn(0.dp,(roomH-56.dp).coerceAtLeast(0.dp))).size(64.dp,56.dp)
                .clip(RoundedCornerShape(14.dp)).clickable(enabled=!busy,role=Role.Button) {onAction(id,null)},contentAlignment=Alignment.BottomCenter) {
                // The object is the control. Its caption stays light and small; the
                // invisible touch target remains generous and accessible.
                Text(label,Modifier.testTag("pet-caption-$id").padding(bottom=2.dp),
                    fontSize=11.sp,lineHeight=14.sp,fontWeight=FontWeight.Medium,maxLines=1,
                    color=if(dark) Color(0xfffff6dc) else Color(0xff514b39),
                    style=MaterialTheme.typography.labelSmall.copy(shadow=Shadow(window.copy(alpha=.8f),Offset.Zero,3f)))
            }
        }
        hotspot("小床","sleep",.16f,.57f)
        hotspot("玩具","play",.84f,.51f)
        if(state.messy) hotspot("收拾","tidy",.09f,.75f)
        hotspot("添粮","feed",.26f,.94f)
        hotspot("换水","water",.74f,.94f)
    }
    Row(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=10.dp,bottom=2.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("已经一起照料 ${state.careDays.size} 天",style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant)
        Text(if(state.boarding) "寄养中" else if(state.messy) "有一点乱" else "干净的小窝",style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant)
    }
    CompanionNavigation(petTabs,pane,{pane=it})
    }
    if(pane!="home") ModalBottomSheet(onDismissRequest={pane="home"},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=scheme.surface) {
      Column(Modifier.fillMaxWidth().heightIn(max=440.dp).verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    when(pane) {
        "diary" -> {
            Text("照料日记",style=MaterialTheme.typography.titleMedium)
            var count by remember {mutableIntStateOf(20)}
            if(state.events.isEmpty()) Text("还没有照料记录，摸摸它就从今天开始。",style=MaterialTheme.typography.bodySmall)
            state.events.asReversed().take(count).forEach {
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM.dd  HH:mm")),style=MaterialTheme.typography.labelSmall,color=scheme.onSurfaceVariant)
                    Text(it.message,style=MaterialTheme.typography.bodyMedium);HorizontalDivider(color=scheme.outlineVariant)
                }
            }
            if(state.events.size>count) TextButton(onClick={count+=20}) {Text("显示更早的记录")}
        }
        "decorate" -> {
            Text("同一间小窝，换一种颜色",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("sage" to "草绿","rose" to "暖粉","sky" to "雾蓝").forEach { (id,label) ->
                    FilterChip(enabled=!busy,selected=state.decoration==id,onClick={onAction("decorate",id)},label={Text(label)})
                }
            }
            if(appearance.family=="cat") Row(verticalAlignment=Alignment.CenterVertically) {
                Text("原画形象与房间",Modifier.weight(1f));Switch(original,{original=it})
            }
            Row(verticalAlignment=Alignment.CenterVertically) {Text("减少动态效果",Modifier.weight(1f));Switch(reduceMotion,{reduceMotion=it})}
        }
        "care" -> {
            Text("健康 ${state.health.toInt()} · 不会死亡",style=MaterialTheme.typography.titleMedium)
            Text("外出仍会正常结算时间。寄养保障饱食和饮水，心情缓慢变化。",style=MaterialTheme.typography.bodyMedium)
            Text("${appearance.label} · 翻译机会 ${state.translationCredits} 次",style=MaterialTheme.typography.bodySmall)
            Text("保留原画的呼吸、移动、照料和休息动作；不会因为离开页面暂停宠物时间。",style=MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick={onAi()}) {Text("宠物 AI · 对话与翻译机")}
            OutlinedButton(enabled=!busy,onClick={onAction(if(state.boarding) "return" else "board",null)}) {Text(if(state.boarding) "接回家" else "安排寄养")}
            TextButton(onClick={pane="home";onSettings()}) {Text("能力设置")}
        }
    }
      }
    }
}

@Composable
private fun PetMeter(label:String,water:Boolean) {
    val ink=MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
        Canvas(Modifier.size(15.dp)) {
            withTransform({scale(size.width/16f,size.height/16f,Offset.Zero)}) {
                if(water) drawPath(Path().apply {moveTo(8f,1f);cubicTo(7f,5f,3f,7f,3f,10f);cubicTo(3f,16f,13f,16f,13f,10f);cubicTo(13f,7f,9f,5f,8f,1f)},ink,style=Stroke(1.1f))
                else {
                    for(x in listOf(2f,4f,6f)) drawLine(ink,Offset(x,1f),Offset(x,6f),1f)
                    drawLine(ink,Offset(2f,6f),Offset(6f,6f),1f);drawLine(ink,Offset(4f,6f),Offset(4f,15f),1f)
                    drawOval(ink,Offset(10f,1f),Size(4f,6f),style=Stroke(1f));drawLine(ink,Offset(12f,7f),Offset(12f,15f),1f)
                }
            }
        }
        Text(label,style=MaterialTheme.typography.labelMedium)
    }
}

private fun DrawScope.bowl(x:Float,y:Float,body:Color,inside:Color,water:Boolean) {
    drawRoundRect(body,Offset(x-40,y-10),Size(80f,30f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(16f))
    drawOval(inside,Offset(x-34,y-16),Size(68f,22f))
    if(!water) for(i in 0..5) drawCircle(body,3f,Offset(x-23+i*9,y-7+(i%2)*5))
}
private fun DrawScope.drawCat(id:String,phase:Float,sleep:Boolean,action:String,moving:Boolean) {
    val coat=when {"black" in id||"tuxedo" in id->Color(0xff353c3a);"orange" in id->Color(0xffcb945a)
        "silver" in id||"blue" in id->Color(0xff899caa);"cream" in id||"point" in id->Color(0xffddcba7)
        "calico" in id->Color(0xffe9d9bb);else->Color(0xff8c775c)}
    val t=phase*6.28318f;val breath=sin(t)*1.8f
    drawOval(Color.Black.copy(alpha=.14f),Offset(12f,113f),Size(98f,12f))
    val tail=Path().apply {moveTo(78f,105f);cubicTo(112f,115f,115f,85f+sin(t)*12,102f,82f)}
    drawPath(tail,coat,style=Stroke(11f))
    val hop=if(action=="play") abs(sin(t*2))*9 else 0f
    withTransform({translate(0f,-hop)}) {
        drawOval(coat,Offset(30f,54f-breath),Size(59f,64f+breath))
        if("white" in id||"tuxedo" in id||"calico" in id) drawOval(Color(0xffeee4d3),Offset(40f,65f),Size(29f,44f))
        for(i in 0..1) {val step=if(moving) sin(t*4+i*3.14f)*7 else 0f;drawOval(coat,Offset(30f+i*38,105f+step),Size(23f,14f))}
        val headY=if(sleep) 37f else if(action in setOf("feed","water")) 25f+abs(sin(t*4))*8 else 22f
        rotate(if(action=="pet") -12f else if(moving) sin(t*3)*3 else 0f,Offset(60f,headY+25)) {
            val ears=Path().apply {moveTo(23f,headY+21);lineTo(24f,headY-15);lineTo(48f,headY+5);close();moveTo(75f,headY+5);lineTo(98f,headY-15);lineTo(100f,headY+23);close()}
            drawPath(ears,coat);drawOval(coat,Offset(19f,headY),Size(84f,60f))
            if("tabby" in id||"brown" in id) for(i in 0..2) drawLine(coat.copy(red=coat.red*.7f,green=coat.green*.7f,blue=coat.blue*.7f),Offset(46f+i*12,headY+3),Offset(49f+i*10,headY+13),4f)
            val closed=sleep||phase>.94f
            for(i in 0..1) {val ex=43f+i*35
                if(closed) drawLine(Color(0xffcddd9b),Offset(ex-5,headY+31),Offset(ex+5,headY+31),2f)
                else {drawOval(Color(0xffcddd9b),Offset(ex-6,headY+25),Size(12f,10f));drawLine(Color(0xff253228),Offset(ex,headY+25),Offset(ex,headY+35),2f)}
            }
            drawOval(Color(0xffc6968b),Offset(57f,headY+42),Size(7f,5f))
        }
    }
}
