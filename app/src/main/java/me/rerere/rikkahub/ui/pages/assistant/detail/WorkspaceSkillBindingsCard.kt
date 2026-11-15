package me.rerere.rikkahub.ui.pages.assistant.detail
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import me.rerere.rikkahub.data.orbis.skills.WorkspaceSkillBindings
import me.rerere.rikkahub.data.orbis.market.ms
@Composable internal fun WorkspaceSkillBindingsCard(owner:String,workspace:String?) {
 if(workspace==null)return
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 val store=remember(owner,workspace){WorkspaceSkillBindings(File(context.filesDir,"orbis-workspace-skills"),owner,workspace)}
 var rows by remember(owner,workspace){mutableStateOf(emptyList<JsonObject>())};var note by remember{mutableStateOf("")}
 LaunchedEffect(store){rows=withContext(Dispatchers.IO){store.list()}}
 if(rows.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)) {
  Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
   Text("AI 自己绑定的技能",style=MaterialTheme.typography.titleSmall)
   for(row in rows) Row(Modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f)){Text(row.ms("name"));Text(row.ms("path"),style=MaterialTheme.typography.bodySmall)}
    TextButton(onClick={scope.launch{try{withContext(Dispatchers.IO){store.remove(row.ms("name"))};rows=withContext(Dispatchers.IO){store.list()}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){note="解绑未完成，请重新读取"}}}){Text("解绑")}
   }
   if(note.isNotBlank())Text(note)
  }
 }
}
