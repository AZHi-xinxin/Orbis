package me.rerere.rikkahub.data.orbis.character

/** Public art only. Asset bytes come from the APK, never a private character directory. */
internal object BuiltinCharacter {
    const val ROOT = "orbis-character/base-blue"
    const val COLOR_MODEL = "orbis-blue-v1"

    fun load(read: (String) -> ByteArray): CharacterDraft {
        val pack = CharacterStore.json.decodeFromString<CharacterPack>(read("manifest.json").toString(Charsets.UTF_8))
        val presentation = CharacterStore.json.decodeFromString<CharacterPresentation>(read("presentation.json").toString(Charsets.UTF_8))
        CharacterRules.validate(CharacterProfile(pack = pack, presentation = presentation))
        val images = presentation.assets.associate { asset ->
            val bytes = read("${asset.id}.png")
            require(bytes.size in 8..CharacterRules.IMAGE_BYTES && characterSha(bytes) == asset.sha256) { "character_image" }
            asset.sha256 to bytes
        }
        val png = requireNotNull(images[pack.sha256]) { "character_image" }
        require(images.values.sumOf { it.size.toLong() } + png.size <= CharacterArchive.TOTAL_BYTES) { "character_too_large" }
        return CharacterDraft(pack, CharacterInteractions(), png, presentation, images)
    }
}
