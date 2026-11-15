package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*

@Composable
internal fun CommunityFeedMentionPicker(api:CommunityClient, actor:String, dismiss:()->Unit, pick:(JsonObject)->Unit) {
    val scope=rememberCoroutineScope()
    var rows by remember(actor) { mutableStateOf(emptyList<JsonObject>()) }
    var offset by remember(actor) { mutableStateOf<Int?>(0) }
    var number by remember(actor) { mutableStateOf("") }
    var busy by remember(actor) { mutableStateOf(false) }
    var error by remember(actor) { mutableStateOf("") }
    suspend fun load() {
        val next=offset ?: return
        busy=true
        try {
            val page=api.get("/contacts?limit=50&offset=$next",actor)
            rows=(rows+page.ma("items")).distinctBy { it.ms("id") }
            offset=(page["next_offset"] as? JsonPrimitive)?.intOrNull
        } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message.orEmpty() }
        finally { busy=false }
    }
    LaunchedEffect(actor) { load() }
    AlertDialog(onDismissRequest=dismiss,title={ Text("艾特社区成员") },text={ Column {
        Row {
            OutlinedTextField(number,{number=it.filter(Char::isDigit).take(10)},Modifier.weight(1f),label={Text("10 位社区号码")},singleLine=true)
            TextButton(enabled=!busy && number.length==10,onClick={ scope.launch {
                busy=true
                try { val found=api.get("/profile?number=$number",actor);pick(found);dismiss() }
                catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message.orEmpty() }
                finally { busy=false }
            } }) { Text("选择") }
        }
        if(error.isNotEmpty()) Text(error)
        LazyColumn(Modifier.heightIn(max=320.dp)) {
            items(rows,key={it.ms("id")}) { person ->
                TextButton(enabled=!busy && person.ms("id")!=actor,onClick={pick(person);dismiss()}) { Text("${person.ms("name")} · ${person.ms("number")}") }
            }
            if(offset!=null) item { TextButton(enabled=!busy,onClick={scope.launch {load()}}) {Text("更多好友")} }
        }
    } },confirmButton={TextButton(onClick=dismiss) {Text("关闭")}})
}
