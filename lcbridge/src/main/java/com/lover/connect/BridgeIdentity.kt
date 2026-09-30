package com.lover.connect

/** The code namespace is retained; Android data and permissions belong to the Orbis host. */
object BridgeIdentity {
    const val MCP_PORT = 5001
    const val SOURCE_VERSION = "LC 2.4.3-personal"
    val chatPackages = setOf("org.orbis.agent", "org.orbis.agent.dev", "me.rerere.rikkahub", "com.lover.connect")
}
