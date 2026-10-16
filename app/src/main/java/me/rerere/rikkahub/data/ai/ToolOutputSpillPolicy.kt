package me.rerere.rikkahub.data.ai

/** workspace_read_file bounds its UTF-8 chunk before serialization; never truncate its cursor. */
internal fun keepCompleteToolOutput(toolName: String): Boolean =
    me.rerere.rikkahub.data.orbis.privateroom.isPrivateRoomToolName(toolName) ||
        toolName == me.rerere.rikkahub.data.ai.tools.ORBIS_MEMORY_TOOL ||
        toolName == "workspace_read_file"
