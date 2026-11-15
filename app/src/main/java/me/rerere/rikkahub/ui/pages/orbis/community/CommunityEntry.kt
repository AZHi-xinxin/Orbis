package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.orbis.community.CommunityClient
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

/** Human-only foreground indicator. No model request, push notification or read receipt. */
@Composable internal fun CommunityEntry(assistantId: String) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var open by remember { mutableStateOf(false) }
    var unread by remember { mutableIntStateOf(0) }
    LaunchedEffect(context,lifecycle,open) {
        if(open) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) {
                try {
                    if(!MarketClient(context).hasSession()) unread=0
                    else {
                        val api=CommunityClient(context)
                        val human=api.identities().singleOrNull { it.ms("kind")=="human" }
                        unread=if(human==null) 0 else {
                            val rooms=api.get("/rooms?limit=50",human.ms("id")).ma("items")+
                                api.get("/rooms?limit=50&offset=50",human.ms("id")).ma("items")
                            rooms.filter { it.mi("muted")==0 }.sumOf { it.mi("unread").coerceAtLeast(0) }
                        }
                    }
                } catch(e:CancellationException) { throw e } catch(_:Exception) { unread=0 }
                delay(30_000)
            }
        }
    }
    CommunityEntryButton(unread=unread,onClick={ open=true })
    if(open) CommunityPage(assistantId) { open=false }
}

@Composable
internal fun CommunityEntryButton(unread: Int, onClick: () -> Unit, modifier: Modifier=Modifier) {
    // Both pieces reserve their own space outside the circular touch feedback clip.
    // The slot grows with accessibility fonts, so neither digits nor the icon are hidden.
    Box(modifier.sizeIn(minWidth=40.dp,minHeight=40.dp)) {
        IconButton(onClick=onClick,modifier=Modifier.matchParentSize().semantics {
            contentDescription=if(unread>0) "打开社区，$unread 条未读群消息" else "打开社区"
        }) { }
        Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
            if(unread>0) Badge(modifier=Modifier.align(Alignment.End).testTag("community-unread-badge")) {
                Text(if(unread>99) "99+" else unread.toString(),maxLines=1)
            }
            Text("♧",modifier=Modifier.testTag("community-entry-icon"),fontSize=23.sp,color=OrbisTheme.colors.accent)
        }
    }
}
