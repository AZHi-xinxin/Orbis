package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import java.time.Instant
import java.time.OffsetDateTime
import java.util.ArrayDeque

data class DeepSeekExport(val conversations: List<DeepSeekConversation>) {
    val messageCount get() = conversations.sumOf { it.nodes.count { node -> node.value.message != null } }
    val branchCount get() = conversations.sumOf { it.nodes.count { node -> node.value.children.size > 1 } }
    val leafCount get() = conversations.sumOf { it.nodes.count { node -> node.value.children.isEmpty() } }
    val attachmentReferenceCount get() = conversations.sumOf { conversation -> conversation.nodes.values.sumOf { node ->
        node.message?.fragments?.sumOf { (it.raw["files"] as? JsonArray)?.size ?: 0 } ?: 0
    } }
}

data class DeepSeekConversation(
    val sourceId: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val rootId: String,
    val selectedLeafId: String,
    val nodes: Map<String, DeepSeekNode>,
    /** The original JSON object is retained structurally, never treated as executable instructions. */
    val original: JsonObject,
) {
    /** Iterative traversal supports very long chats without recursive stack overflow. */
    fun pathTo(nodeId: String, checkCancelled: () -> Unit = {}): List<DeepSeekNode> {
        require(nodeId in nodes) { "deepseek_unknown_branch" }
        val result = mutableListOf<DeepSeekNode>()
        var current: String? = nodeId
        while (current != null) {
            checkCancelled()
            val node = nodes.getValue(current)
            if (node.message != null) result += node
            current = node.parentId
        }
        return result.asReversed()
    }
}

data class DeepSeekNode(val sourceId: String, val parentId: String?, val children: List<String>, val message: DeepSeekMessage?)
data class DeepSeekMessage(val role: MessageRole, val createdAt: Instant, val sourceModel: String, val fragments: List<DeepSeekFragment>, val sourceMetadata: JsonObject = JsonObject(emptyMap()))
data class DeepSeekFragment(val type: String, val raw: JsonObject) {
    val content: String? get() = (raw["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Never include the unknown type or source text: either may contain private data. */
internal class DeepSeekUnsupportedFragmentException : IllegalArgumentException(
    "DeepSeek 导出包包含尚未支持的消息片段类型；已停止导入，没有忽略记录。请保留原文件以便适配。"
)

/** Parser consumes only data. It never creates assistants, tools, providers or remote attachment requests. */
object DeepSeekParser {
    private val USER_TYPES = setOf("REQUEST", "FILE")
    private val ASSISTANT_TYPES = setOf("RESPONSE", "THINK", "SEARCH", "TOOL_SEARCH", "TOOL_OPEN", "TOOL_FIND", "TEMPLATE_RESPONSE", "READ_LINK", "PARTIAL_FILE_READ")
    private val TYPES = USER_TYPES + ASSISTANT_TYPES + setOf("CONTENT_RISK", "TIP")

    fun parse(root: JsonElement, checkCancelled: () -> Unit = {}): DeepSeekExport {
        try {
            val source = root as? JsonArray ?: error("deepseek_root_array")
            require(source.isNotEmpty()) { "deepseek_conversation_count" }
            val ids = hashSetOf<String>()
            val conversations = source.map { item ->
                checkCancelled()
                val raw = item.obj()
                val id = raw.string("id").identity()
                require(ids.add(id)) { "deepseek_duplicate_conversation" }
                // Official exports may leave a newly created/unnamed chat's title null.
                // This is display metadata, never a reason to reject all its messages.
                val title = raw["title"]?.takeUnless { it == JsonNull }?.string()
                    ?.takeIf { it.isNotBlank() } ?: "未命名会话"
                val createdAt = raw.string("inserted_at").timestamp()
                val updatedAt = raw.string("updated_at").timestamp()
                val mapping = raw.getValue("mapping").obj()
                require(mapping.isNotEmpty()) { "deepseek_node_count" }
                val links = mapping.mapValues { (key, value) ->
                    checkCancelled()
                    key.identity()
                    val node = value.obj()
                    require(node.string("id") == key) { "deepseek_node_identity" }
                    val parent = node.getValue("parent").let { if (it == JsonNull) null else it.string().identity() }
                    val children = node.getValue("children").array().map { it.string().identity() }
                    require(children.size == children.toSet().size && key !in children && parent != key) { "deepseek_invalid_links" }
                    DeepSeekNode(key, parent, children, null)
                }
                val roots = links.values.filter { it.parentId == null }
                require(roots.size == 1) { "deepseek_root_count" }
                val parentsFromChildren = hashMapOf<String, String>()
                links.values.forEach { node ->
                    checkCancelled()
                    require(node.parentId == null || node.parentId in links) { "deepseek_missing_parent" }
                    node.children.forEach { child ->
                        require(links[child]?.parentId == node.sourceId && parentsFromChildren.put(child, node.sourceId) == null) {
                            "deepseek_missing_or_repeated_child"
                        }
                    }
                }
                require(links.values.all { parentsFromChildren[it.sourceId] == it.parentId }) { "deepseek_nonreciprocal_parent" }
                val queue = ArrayDeque<String>()
                val nodes = linkedMapOf<String, DeepSeekNode>()
                queue.add(roots.single().sourceId)
                while (queue.isNotEmpty()) {
                    checkCancelled()
                    val key = queue.removeFirst()
                    require(key !in nodes) { "deepseek_cycle" }
                    val link = links.getValue(key)
                    val message = mapping.getValue(key).obj().getValue("message")
                    require((message == JsonNull) == (link.parentId == null)) { "deepseek_message_missing" }
                    val parsed = if (message == JsonNull) null else {
                        val content = message.obj()
                        val fragments = content.getValue("fragments").array().map { fragment ->
                            val objectValue = fragment.obj()
                            val type = objectValue.string("type")
                            // Future fragments are inert historical data, not executable tools.
                            // Keep their complete JSON, including null/structured content.
                            DeepSeekFragment(type, objectValue)
                        }
                        val user = fragments.any { it.type in USER_TYPES }
                        val assistant = fragments.any { it.type in ASSISTANT_TYPES }
                        require(!(user && assistant)) { "deepseek_mixed_roles" }
                        // Official exports have no role field. Keep even the empty interrupted message.
                        val role = when {
                            content["role"] != null -> when (content.string("role")) {
                                "USER" -> MessageRole.USER
                                "ASSISTANT" -> MessageRole.ASSISTANT
                                else -> error("deepseek_unknown_role")
                            }
                            user -> MessageRole.USER
                            assistant -> MessageRole.ASSISTANT
                            nodes[link.parentId]?.message?.role == MessageRole.USER -> MessageRole.ASSISTANT
                            fragments.any { it.type !in USER_TYPES && it.type !in setOf("CONTENT_RISK", "TIP") } -> MessageRole.ASSISTANT
                            else -> MessageRole.USER
                        }
                        DeepSeekMessage(role, content.string("inserted_at").timestamp(),
                            content["model"]?.takeUnless { it == JsonNull }?.string().orEmpty(), fragments,
                            JsonObject(content.filterKeys { it in setOf("status", "thinking_enabled", "search_enabled", "accumulated_token_usage") }))
                    }
                    nodes[key] = link.copy(message = parsed)
                    link.children.forEach(queue::addLast)
                }
                require(nodes.size == links.size) { "deepseek_disconnected_cycle" }
                val explicit = raw["current_node"]?.takeUnless { it == JsonNull }?.string()
                if (explicit != null) require(explicit in nodes) { "deepseek_current_node" }
                // This export format does not normally identify a selected branch: newest leaf is deterministic.
                val leaf = explicit ?: nodes.values.filter { it.children.isEmpty() }
                    .maxWithOrNull(compareBy<DeepSeekNode> { it.message?.createdAt ?: createdAt }.thenBy { it.sourceId })!!.sourceId
                DeepSeekConversation(id, title, createdAt, updatedAt, roots.single().sourceId, leaf, nodes, raw)
            }
            return DeepSeekExport(conversations)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: ArchiveReadException) {
            throw failure
        } catch (failure: DeepSeekUnsupportedFragmentException) {
            throw failure
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("DeepSeek 聊天结构不兼容或已损坏；现有聊天未更改")
        }
    }

    private fun JsonElement.obj() = this as? JsonObject ?: error("deepseek_object")
    private fun JsonElement.array() = this as? JsonArray ?: error("deepseek_array")
    private fun JsonElement.string() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("deepseek_string")
    private fun JsonObject.string(key: String) = getValue(key).string()
    private fun String.identity() = also { require(isNotEmpty() && none { it.isISOControl() }) { "deepseek_identity" } }
    private fun String.timestamp(): Instant = OffsetDateTime.parse(this).toInstant().also {
        require(it.epochSecond in 0..253402300799L) { "deepseek_timestamp" }
    }
}
