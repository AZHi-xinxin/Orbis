package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.orbis.skills.WorkspaceSkillFailure

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        appendLine("    <name>${skill.name}</name>")
                        appendLine("    <description>${skill.description}</description>")
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { workspaceSkillReceipt("use_skill") {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: throw WorkspaceSkillFailure("skill_not_available", "此name不在当前AI已启用的技能清单中。请核对清单；工作区新登记的技能应通过orbis_skill_register(operation=read,name=技能名)读取。")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = withContext(Dispatchers.IO) {
                    val target = if(path.isNullOrBlank()) skill.skillFile else {
                        SkillPaths.resolveSkillFile(skill.skillDir, path)
                            ?: throw WorkspaceSkillFailure("skill_path_denied", "参考文件path必须位于此技能目录内，不能读取外部路径。")
                    }
                    if(!target.isFile) throw WorkspaceSkillFailure("skill_file_missing", "技能文件不存在或不是普通文件，请核对SKILL.md及其引用文件，未读取其他目录。")
                    try {
                        val text=target.readText()
                        if(path.isNullOrBlank()) SkillFrontmatterParser.extractBody(text) else text
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { throw WorkspaceSkillFailure("skill_file_unreadable", "技能文件无法读取，请核对文件与读取权限。", cause=e) }
                }
                listOf(UIMessagePart.Text(content))
            } }
        )
    )
}
