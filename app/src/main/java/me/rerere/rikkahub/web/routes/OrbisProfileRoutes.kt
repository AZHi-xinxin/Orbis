package me.rerere.rikkahub.web.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.requireSensitiveWebAccess

@Serializable private data class WebNicknameRequest(val nickname: String)
@Serializable private data class WebNicknameResponse(val nickname: String)

fun Route.orbisProfileRoutes(settingsStore: SettingsStore) {
    post("/orbis/profile") {
        call.requireSensitiveWebAccess(settingsStore)
        val nickname = call.receiveBoundedWebJson<WebNicknameRequest>(2048).nickname.trim()
        if (nickname.length > 80 || nickname.any { it.isISOControl() }) {
            throw BadRequestException("invalid_nickname")
        }
        settingsStore.updateUserNickname(nickname)
        call.respond(WebNicknameResponse(nickname))
    }
}
