package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.json.*

internal fun pluginGithubUrl(args: JsonObject): String {
    check(args.keys.all { it in setOf("repo","kind") }) { "plugin_host_arguments_invalid" }
    val repo=args["repo"]?.jsonPrimitive?.content.orEmpty()
    check(repo.length<=200 && repo.matches(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")) && repo.substringAfter('/') !in setOf(".","..")) { "plugin_host_arguments_invalid" }
    return "https://api.github.com/repos/$repo/"+when(args["kind"]?.jsonPrimitive?.content ?: "release") {
        "release"->"releases/latest"; "commit"->"commits?per_page=1"; else->error("plugin_host_arguments_invalid")
    }
}
internal fun pluginGithubMetadata(status: Int, raw: String, kind: String): JsonObject = buildJsonObject {
    put("status",status); put("ok",status==200)
    if(status!=200) { put("message","未取得更新信息；保留上次游标，不要视为没有新版本。"); return@buildJsonObject }
    val parsed=Json.parseToJsonElement(raw)
    val item=if(kind=="commit") parsed.jsonArray.firstOrNull()?.jsonObject else parsed.jsonObject
    check(item!=null) { "plugin_metadata_missing" }
    val keys=if(kind=="commit") listOf("sha","html_url") else listOf("id","tag_name","name","published_at","html_url","prerelease")
    keys.forEach { key->item[key]?.let { value->put(key,if(value is JsonPrimitive && value.isString) JsonPrimitive(value.content.take(1200)) else value) } }
    if(kind=="commit") item["commit"]?.jsonObject?.get("committer")?.jsonObject?.get("date")?.let { put("date",it) }
    put("source","public_github_metadata"); put("instruction_authority","none")
}
