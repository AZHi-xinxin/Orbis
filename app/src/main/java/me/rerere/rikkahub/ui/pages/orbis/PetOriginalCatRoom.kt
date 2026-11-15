package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import me.rerere.rikkahub.data.orbis.activities.*

/** Original rooms share one care flow for every family, including independent care pictures. */
@Composable internal fun PetOriginalCatRoom(state: PetState, root: File, action: String,
    sleeping: Boolean, animate: Boolean, busy: Boolean, phase: Float, idleCycle: Int,
    onAction: (String, String?) -> Unit) {
    val pet = state.pet ?: return
    val family = PetCatalog.find(pet.appearance).family
    var restDismissed by rememberSaveable(pet.id) { mutableStateOf(false) }
    val last = state.events.lastOrNull()
    LaunchedEffect(last?.id) {
        if(last?.action == "sleep" || last?.aiActions?.contains("sleep") == true) restDismissed = false
        else if(last?.action in setOf("pet","play","feed","water") ||
            last?.aiActions?.any { it in setOf("greet","ask_play","approach_food","approach_water") } == true) restDismissed = true
    }
    val color = state.decoration.takeIf { it in setOf("sage","rose","sky") } ?: "sage"
    val rest = File(root,"rest-$color.webp")
    val care = File(root,"care.webp").takeIf { it.isFile }
    val resting = sleeping && !restDismissed
    val scene = when {
        resting -> 0
        care != null && action in setOf("feed","approach_food") -> 1
        care != null && action in setOf("water","approach_water") -> 2
        else -> -1
    }
    fun act(id: String) { if(!busy) { restDismissed = id != "sleep"; onAction(id,null) } }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Crossfade(scene, Modifier.fillMaxSize(), animationSpec = if(animate) tween(600) else snap(),
            label="pet-rest-scene") { currentScene ->
            val asleep = currentScene == 0
            if(care != null && currentScene >= 0) {
                PetCareScene(care,currentScene,color,Modifier.testTag(if(asleep) "pet-rest-picture" else "pet-care-picture")
                    .semantics { contentDescription="${pet.name}${when(currentScene) {0->"在小窝里睡觉";1->"正在吃饭";else->"正在喝水"}}" }
                    .clickable(remember { MutableInteractionSource() },null,enabled=!busy,role=Role.Button) { act("pet") })
            } else if(asleep && rest.isFile) {
                AsyncImage(rest,"${pet.name}在小窝里睡觉",Modifier.fillMaxSize().testTag("pet-rest-picture")
                    .semantics { contentDescription="摸摸${pet.name}" }
                    .clickable(remember { MutableInteractionSource() },null,enabled=!busy,role=Role.Button) { act("pet") },
                    contentScale=ContentScale.Crop,alignment=Alignment.Center)
            } else BoxWithConstraints(Modifier.fillMaxSize().testTag(if(asleep) "pet-rest-still" else "pet-original-awake")) {
                val roomW=maxWidth; val roomH=maxHeight
                // Match ContentScale.Crop + BottomCenter exactly, including short displays.
                val worldW=maxOf(roomW,roomH*.75f); val worldH=worldW/.75f
                val left=(roomW-worldW)/2; val top=roomH-worldH
                Crossfade(state.messy,Modifier.fillMaxSize(),animationSpec=if(animate) tween(800) else snap(),label="pet-room-clean") { messy ->
                    val kind=if(messy) "messy" else "tidy"
                    val selected=File(root,"room-$color-$kind.webp")
                    AsyncImage(selected.takeIf {it.isFile} ?: File(root,"room-sage-$kind.webp"),null,
                        Modifier.fillMaxSize().testTag("pet-room-palette-$color"),contentScale=ContentScale.Crop,alignment=Alignment.BottomCenter,
                        colorFilter=if(selected.isFile) null else when(color) {
                            "rose" -> androidx.compose.ui.graphics.ColorFilter.tint(Color(0xffdba3b4),androidx.compose.ui.graphics.BlendMode.Softlight)
                            "sky" -> androidx.compose.ui.graphics.ColorFilter.tint(Color(0xff92bad5),androidx.compose.ui.graphics.BlendMode.Softlight)
                            else -> null
                        })
                }
                val actorW=minOf(worldW*(if(family=="bird") .23f else .38f),roomH*.48f)
                val actorH=actorW*527f/479f
                val eating=action in setOf("feed","water","approach_food","approach_water")
                val bowlX=if(action in setOf("water","approach_water")) .658f else .318f
                // In the eat illustration the muzzle is .31 across, 27 atlas pixels above the feet.
                val footX=when { asleep -> left+worldW*.145f; eating -> left+worldW*bowlX+actorW*.19f; else -> left+worldW*.50f }
                val footY=when { asleep -> top+worldH*.50f; eating -> top+worldH*.825f+actorW*(27f/479f)
                    family=="bird" -> top+worldH*.37f; family=="reptile" -> top+worldH*.55f; else -> top+worldH*.77f }
                val x by animateDpAsState(footX,if(animate && !asleep) tween(650) else snap(),label="original-cat-x")
                val y by animateDpAsState(footY,if(animate && !asleep) tween(650) else snap(),label="original-cat-y")
                val w=if(asleep) minOf(worldW*.20f,roomW*.25f) else actorW
                val h=if(asleep) w*527f/479f else actorH
                val actor=Modifier.offset((x-w/2).coerceIn(0.dp,(roomW-w).coerceAtLeast(0.dp)),
                    (y-h).coerceIn(0.dp,(roomH-h).coerceAtLeast(0.dp))).size(w,h)
                    .semantics { contentDescription="摸摸${pet.name}" }
                    .clickable(remember(pet.id) { MutableInteractionSource() },null,enabled=!busy,role=Role.Button) { act("pet") }
                PetPoseSprite(pet.appearance,if(asleep) PetPose.SLEEP else if(action.isEmpty() && animate)
                    petIdlePose(phase,idleCycle) else petPose(action,false,phase),root,animate && !asleep,actor)
                if(!asleep) {
                    @Composable fun hotspot(label: String,id: String,px: Float,py: Float) {
                        Box(Modifier.offset((left+worldW*px-32.dp).coerceIn(0.dp,(roomW-64.dp).coerceAtLeast(0.dp)),
                            (top+worldH*py-28.dp).coerceIn(44.dp,(roomH-56.dp).coerceAtLeast(44.dp)))
                            .size(64.dp,56.dp).clickable(enabled=!busy,role=Role.Button) { act(id) },contentAlignment=Alignment.BottomCenter) {
                            Text(label,Modifier.testTag("pet-caption-$id"),fontSize=12.sp,color=Color(0xfffff7e4),
                                style=MaterialTheme.typography.labelMedium.copy(shadow=Shadow(Color(0xff3e2a1b),Offset.Zero,4f)))
                        }
                    }
                    hotspot(when(family) {"bird"->"休息";"reptile"->"小窝";else->"小床"},"sleep",
                        when(family) {"bird"->.65f;"reptile"->.81f;"dog"->.18f;else->.14f},
                        when(family) {"bird"->.28f;"reptile"->.46f;"dog"->.53f;else->.57f})
                    hotspot("玩具","play",.90f,.69f)
                    hotspot("添粮","feed",when(family) {"cat"->.318f;"reptile"->.44f;else->.05f},
                        when(family) {"cat"->.93f;"reptile"->.60f;else->.78f})
                    hotspot("换水","water",when(family) {"cat"->.658f;"reptile"->.12f;else->.17f},
                        when(family) {"cat"->.93f;"reptile"->.68f;else->.82f})
                    if(state.messy) hotspot("收拾","tidy",.10f,.76f)
                }
            }
        }
        val note=when { resting -> "在小窝里休息，点其他照料可回到房间"; action in setOf("feed","approach_food") -> "添好粮了"
            action in setOf("water","approach_water") -> "换好了清水"; else -> "点点房间里的物件，或者摸摸它" }
        Surface(Modifier.align(Alignment.TopCenter).padding(horizontal=24.dp,vertical=12.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
            Text(note,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)
        }
        if(scene >= 0) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
            Row(horizontalArrangement=Arrangement.SpaceEvenly) {
                listOf("摸摸" to "pet","添粮" to "feed","换水" to "water","玩具" to "play").forEach { (label,id) ->
                    TextButton(enabled=!busy,onClick={act(id)}) { Text(label,Modifier.testTag("pet-caption-$id")) }
                }
            }
        }
    }
}
