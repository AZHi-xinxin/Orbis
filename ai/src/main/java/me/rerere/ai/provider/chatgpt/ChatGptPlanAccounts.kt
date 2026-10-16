package me.rerere.ai.provider.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.common.http.awaitAndUse
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class PlanCredential(
    val id: String = UUID.randomUUID().toString(), val clientId: String, val subject: String,
    val email: String = "", val accessToken: String = "", val refreshToken: String = "",
    val idToken: String = "", val scopes: Set<String> = emptySet(), val expiresAt: Long = 0,
    val welcomeSeen: Boolean = false,
    // Rotated credentials are encrypted immediately, but cannot be used until identity is verified.
    val identityPending: Boolean = false, val pendingNonce: String? = null,
) { override fun toString(): String = "ChatGptPlanCredential(redacted)" }

@Serializable
internal data class PlanVault(val hostId: String = "urn:uuid:${UUID.randomUUID()}", val accounts: List<PlanCredential> = emptyList()) {
    override fun toString(): String = "ChatGptPlanVault(redacted)"
}

data class ChatGptPlanAccount(val id: String, val label: String, val signedIn: Boolean, val planEnabled: Boolean,
    val welcomeSeen: Boolean, val verificationPending: Boolean = false)

internal val PlanCredential.accountLabel: String
    get() = "${email.ifBlank { "ChatGPT 账号" }} · ${id.take(8)}"

internal fun requireWritablePlanVaultSize(size: Int) {
    require(size in 29..1_048_576) { "siwc_vault_invalid" }
}

/** Tokens never enter Settings, Android backup, export/share, logs, or model custom headers. */
class ChatGptPlanAccounts(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "chatgpt-plan-v1.enc"))
    // Dedicated client: no application/body logging interceptors, redirects, cookies or automatic replay.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    private val codec = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): PlanVault {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return PlanVault().also(::write)
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1_048_576) { "siwc_vault_invalid" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size in 29..1_048_576) { "siwc_vault_invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("Orbis.ChatGPT.Plan.v1".toByteArray())
        return codec.decodeFromString(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
    }
    private fun write(vault: PlanVault) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("Orbis.ChatGPT.Plan.v1".toByteArray())
        val bytes = cipher.iv + cipher.doFinal(codec.encodeToString(vault).toByteArray())
        requireWritablePlanVaultSize(bytes.size)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Throwable) { file.failWrite(output); throw error }
    }
    fun accounts(): List<ChatGptPlanAccount> = synchronized(storageLock) {
        read().accounts.map { ChatGptPlanAccount(it.id,
            it.accountLabel, it.accessToken.isNotBlank() && !it.identityPending,
            ChatGptPlanProtocol.PERMISSION in it.scopes && it.accessToken.isNotBlank() && !it.identityPending,
            it.welcomeSeen, it.identityPending) }
    }
    fun authorizationUrl(accountId: String?, redirect: String, state: String, nonce: String, verifier: String): String = synchronized(storageLock) {
        val vault = read()
        val account = accountId?.let { id -> vault.accounts.singleOrNull { it.id == id } ?: error("siwc_not_signed_in") }
        ChatGptPlanProtocol.authorize(vault.hostId, redirect, state, nonce, verifier, account?.clientId, account?.email, account?.idToken)
    }
    suspend fun finishSignIn(accountId: String?, redirect: String, state: String, actualState: String,
        nonce: String, verifier: String, issuedClient: String?, code: String?, error: String?): ChatGptPlanAccount =
        withContext(Dispatchers.IO) { sessionMutex.withLock {
            var previous = synchronized(storageLock) { read().accounts.singleOrNull { it.id == accountId } }
            require(accountId == null || previous != null) { "siwc_not_signed_in" }
            val clientId = ChatGptPlanProtocol.callbackClient(state, actualState, previous?.clientId, issuedClient, error)
            require(!code.isNullOrBlank()) { "siwc_registration_incomplete" }
            // Retain a newly issued registration even if this one-use code exchange fails.
            // It is NOT a verified/signed-in identity until signature + claims checks succeed.
            if (previous == null) {
                previous = PlanCredential(clientId = clientId, subject = "")
                synchronized(storageLock) { val vault = read(); write(vault.copy(accounts = vault.accounts + checkNotNull(previous))) }
            }
            // Resolve keys before spending a one-use code. A temporary auth-host failure must
            // not repeatedly consume codes or rotate refresh tokens before we can verify them.
            jwks()
            val tokens = postToken(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
                .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", ChatGptPlanProtocol.RESOURCE).build(), ChatGptRequestStage.TOKEN_EXCHANGE)
            val idToken = tokens["id_token"]?.jsonPrimitive?.contentOrNull ?: error("siwc_identity_invalid")
            val pending = fromTokens(checkNotNull(previous), tokens).copy(idToken = idToken,
                identityPending = true, pendingNonce = nonce)
            replaceCredential(pending) // Not signed in; an interrupted JWKS fetch can resume locally.
            val verified = verifyPendingIdentity(pending)
            synchronized(storageLock) { accounts().single { it.id == verified.id } }
        } }
    fun acknowledgeWelcome(accountId: String) = synchronized(storageLock) {
        val vault = read(); write(vault.copy(accounts = vault.accounts.map { if (it.id == accountId) it.copy(welcomeSeen = true) else it }))
    }

    private fun fromTokens(previous: PlanCredential, body: JsonObject): PlanCredential {
        require(body["token_type"]?.jsonPrimitive?.contentOrNull.equals("Bearer", ignoreCase = true)) { "siwc_token_type" }
        val access = body["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 65_536 } ?: error("siwc_token_invalid")
        val refresh = body["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 65_536 } ?: error("siwc_token_invalid")
        val lifetime = body["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..86_400 } ?: error("siwc_token_invalid")
        val scopes = body["scope"]?.jsonPrimitive?.contentOrNull?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: previous.scopes
        return previous.copy(accessToken = access, refreshToken = refresh, expiresAt = System.currentTimeMillis() / 1000 + lifetime, scopes = scopes)
    }
    suspend fun accessToken(accountId: String): String = withContext(Dispatchers.IO) { sessionMutex.withLock {
        var account = synchronized(storageLock) { read().accounts.singleOrNull { it.id == accountId } } ?: error("siwc_not_signed_in")
        account = verifyPendingIdentity(account)
        require(account.accessToken.isNotBlank()) { "siwc_not_signed_in" }
        require(ChatGptPlanProtocol.PERMISSION in account.scopes) { "siwc_permission_missing" }
        if (account.expiresAt <= System.currentTimeMillis() / 1000 + 60) {
            jwks()
            val body = postToken(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", account.clientId)
                .add("refresh_token", account.refreshToken).add("resource", ChatGptPlanProtocol.RESOURCE).build(), ChatGptRequestStage.TOKEN_REFRESH)
            val id = body["id_token"]?.jsonPrimitive?.contentOrNull
            account = fromTokens(account, body).copy(idToken = id ?: account.idToken,
                identityPending = id != null, pendingNonce = null)
            replaceCredential(account) // Retain the NEW refresh token before any further network call.
            account = verifyPendingIdentity(account)
        }
        require(ChatGptPlanProtocol.PERMISSION in account.scopes) { "siwc_permission_missing" }
        account.accessToken
    } }

    suspend fun models(accountId: String): List<Model> {
        val token = accessToken(accountId)
        val request = Request.Builder().url("${ChatGptPlanProtocol.RESOURCE}/models").header("Authorization", "Bearer $token").build()
        val call = client.newCall(request)
        val tracked = track(accountId, token) { call.cancel() }
        try {
            val body = call.awaitAndUse { response ->
                if (!response.isSuccessful) throw ChatGptPlanFailure.http(null, response, ChatGptRequestStage.MODEL_LIST)
                parseBounded(response.body.source())
            }
            return body["models"]?.jsonArray?.mapNotNull { element ->
                val model = element.jsonObject
                if (model["visibility"]?.jsonPrimitive?.contentOrNull != "list") return@mapNotNull null
                val slug = model["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ChatGptPlanCapabilities.withKnownModelAbilities(
                    Model(modelId = slug, displayName = model["display_name"]?.jsonPrimitive?.contentOrNull ?: slug,
                        abilities = listOf(ModelAbility.TOOL), inputModalities = listOf(Modality.TEXT, Modality.IMAGE)))
            } ?: error("siwc_models_unavailable")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (failure: ChatGptPlanFailure) { throw failure }
          catch (failure: java.io.IOException) {
            throw ChatGptPlanFailure.from(failure, stage = ChatGptRequestStage.MODEL_LIST)
        } finally { untrack(tracked) }
    }

    /** Cancels this registration's in-flight requests. Return false means remote revoke unconfirmed. */
    suspend fun signOut(accountId: String): Boolean = withContext(Dispatchers.IO) { sessionMutex.withLock {
        val account = synchronized(storageLock) {
            cancelCalls(accountId)
            val vault = read(); val account = vault.accounts.singleOrNull { it.id == accountId } ?: return@withLock true
            write(vault.copy(accounts = vault.accounts.map { if (it.id == accountId) it.copy(accessToken = "", refreshToken = "", idToken = "", scopes = emptySet(), expiresAt = 0,
                identityPending = false, pendingNonce = null) else it }))
            account
        }
        if (account.refreshToken.isBlank()) return@withLock true
        try {
            val metadata = getJson("${ChatGptPlanProtocol.ISSUER}/.well-known/openid-configuration", ChatGptRequestStage.DISCOVERY)
            val endpoint = metadata["revocation_endpoint"]?.jsonPrimitive?.contentOrNull ?: return@withLock false
            require(endpoint.startsWith("${ChatGptPlanProtocol.ISSUER}/") && !endpoint.contains('#'))
            val request = Request.Builder().url(endpoint).post(FormBody.Builder().add("token", account.refreshToken)
                .add("token_type_hint", "refresh_token").add("client_id", account.clientId).build()).build()
            client.newCall(request).awaitAndUse { it.code == 200 }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { false }
    } }

    private fun replaceCredential(account: PlanCredential) = synchronized(storageLock) {
        val vault = read()
        check(vault.accounts.any { it.id == account.id && it.clientId == account.clientId }) { "siwc_not_signed_in" }
        cancelCalls(account.id)
        write(vault.copy(accounts = vault.accounts.map { if (it.id == account.id) account else it }))
    }
    private suspend fun verifyPendingIdentity(account: PlanCredential): PlanCredential {
        if (!account.identityPending) return account
        val claims = try {
            ChatGptPlanProtocol.verifyIdToken(account.idToken, jwks(), account.clientId, account.pendingNonce)
        } catch (error: IllegalStateException) {
            if (error.message != "siwc_identity_key") throw error
            // The provider may rotate signing keys during sign-in/refresh. Retry keys only,
            // never exchange the code or rotate the refresh token for a second time.
            ChatGptPlanProtocol.verifyIdToken(account.idToken, jwks(force = true), account.clientId, account.pendingNonce)
        }
        val subject = claims.getValue("sub").jsonPrimitive.content
        require(account.subject.isBlank() || subject == account.subject) { "siwc_account_mismatch" }
        return account.copy(subject = subject, email = claims["email"]?.jsonPrimitive?.contentOrNull ?: account.email,
            identityPending = false, pendingNonce = null).also(::replaceCredential)
    }
    private suspend fun postToken(form: FormBody, stage: ChatGptRequestStage): JsonObject = requestJson(
        Request.Builder().url(ChatGptPlanProtocol.TOKEN).post(form).build(), stage)
    private suspend fun getJson(url: String, stage: ChatGptRequestStage): JsonObject = requestJson(Request.Builder().url(url).build(), stage)
    private suspend fun requestJson(request: Request, stage: ChatGptRequestStage): JsonObject = try {
        client.newCall(request).awaitAndUse {
            if (!it.isSuccessful) throw ChatGptPlanFailure.http(null, it, stage)
            parseBounded(it.body.source())
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (error: Exception) { throw ChatGptPlanFailure.from(error, stage = stage) }
    private suspend fun jwks(force: Boolean = false): JsonObject {
        val now = System.nanoTime() / 1_000_000L
        cachedKeys?.takeIf { !force && now - it.first in 0 until 3_600_000 }?.let { return it.second }
        return getJson("${ChatGptPlanProtocol.ISSUER}/.well-known/jwks.json", ChatGptRequestStage.IDENTITY_KEYS)
            .also { cachedKeys = now to it }
    }
    private fun parseBounded(source: okio.BufferedSource): JsonObject {
        require(!source.request(1_048_577)) { "siwc_response_too_large" }
        return codec.parseToJsonElement(source.buffer.readUtf8()).jsonObject
    }
    fun track(accountId: String, token: String, cancel: () -> Unit): String = synchronized(storageLock) {
        require(read().accounts.any { it.id == accountId && !it.identityPending && it.accessToken.isNotBlank() && it.accessToken == token }) { "siwc_not_signed_in" }
        UUID.randomUUID().toString().also { calls[it] = accountId to cancel }
    }
    fun untrack(id: String) { synchronized(storageLock) { calls.remove(id) } }
    private fun cancelCalls(accountId: String) {
        calls.filterValues { it.first == accountId }.keys.toList().forEach { calls.remove(it)?.second?.invoke() }
    }
    companion object {
        private const val KEY_ALIAS = "orbis-chatgpt-plan-v1"
        private val storageLock = Any()
        private val sessionMutex = Mutex()
        private val calls = mutableMapOf<String, Pair<String, () -> Unit>>()
        @Volatile private var cachedKeys: Pair<Long, JsonObject>? = null
    }
}
