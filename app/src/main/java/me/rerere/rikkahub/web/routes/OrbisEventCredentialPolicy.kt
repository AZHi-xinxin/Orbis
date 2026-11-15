package me.rerere.rikkahub.web.routes

/** The scoped device credential is never offered to ordinary event/context authorization. */
internal fun acceptsOrbisEventCredential(token: String?, touchOnly: Boolean,
    generalCredential: (String) -> Boolean, touchCredential: (String) -> Boolean): Boolean =
    !token.isNullOrBlank() && ((touchOnly && touchCredential(token)) || generalCredential(token))
