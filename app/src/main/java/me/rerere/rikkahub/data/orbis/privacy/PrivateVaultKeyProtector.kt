package me.rerere.rikkahub.data.orbis.privacy

/** Device custody only. The implementation must not persist or log unwrapped data keys. */
interface PrivateVaultKeyProtector {
    fun wrap(vaultId: String, dataKey: ByteArray): ByteArray
    fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray
}
