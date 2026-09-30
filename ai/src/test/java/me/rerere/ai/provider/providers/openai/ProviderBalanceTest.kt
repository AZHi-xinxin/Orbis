package me.rerere.ai.provider.providers.openai

import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** All HTTP calls are intercepted in process; no live provider, account or model is contacted. */
class ProviderBalanceTest {
    private val official = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.345"}]}"""
    private fun setting() = ProviderSetting.OpenAI(baseUrl = "https://gateway.example.org/v1",
        apiKey = "synthetic-key", balanceOption = BalanceOption(true, "/credits", "data.total_usage"))

    @Test fun officialAccountEndpointDoesNotInheritVersionPrefix() {
        for (base in listOf("https://api.deepseek.com", "https://api.deepseek.com/v1/", "https://api.deepseek.com/beta")) {
            assertEquals("https://api.deepseek.com/user/balance", balanceUrl(base, "/user/balance").toString())
        }
    }
    @Test fun genericGatewayPrefixesArePreservedAndLookalikeHostsAreNotOfficial() {
        assertEquals("https://gateway.example.org/v1/user/balance", balanceUrl(setting().baseUrl, "/user/balance").toString())
        assertEquals("https://api.deepseek.com.evil.invalid/v1/user/balance", balanceUrl("https://api.deepseek.com.evil.invalid/v1", "/user/balance").toString())
    }
    @Test fun absoluteOrAmbiguousApiPathsNeverSendAnyRequest() = runBlocking {
        var calls = 0
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            calls++; response(chain.request(), 200, official)
        }.build())
        listOf("https://other.example.org/user/balance", "https://gateway.example.org/user/balance",
            "//other.example.org/user/balance", "\\\\other.example.org/user/balance", "/\\other.example.org/user/balance",
            "/user\\balance", "/user/balance\n", " /user/balance").forEach { path ->
            assertTrue(runCatching { provider.getBalance(setting().copy(balanceOption = BalanceOption(true, path, "balance_infos[0].total_balance"))) }.isFailure)
        }
        assertEquals(0, calls)
    }
    @Test fun ambiguousBaseUrlsNeverSendAnyRequest() = runBlocking {
        var calls = 0
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            calls++; response(chain.request(), 200, official)
        }.build())
        listOf("https://user:synthetic-secret@gateway.example.org/v1", "https://@gateway.example.org/v1",
            "https://gateway.example.org/v1?forward=other", "https://gateway.example.org/v1#other",
            "https://gateway.example.org\\@other.example.org/v1", "https://gateway.example.org/v1\n",
            "file:///private", "https://gateway.example.org/v1 ").forEach { base ->
            val failure = runCatching { provider.getBalance(setting().copy(baseUrl = base)) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure?.message.orEmpty().contains("synthetic-secret"))
        }
        assertEquals(0, calls)
    }
    @Test fun fallbackRequiresExactLegacyDefaultsAnd404() {
        assertNotNull(legacyBalanceFallback(setting(), 404))
        listOf(200, 401, 403, 429, 500).forEach { assertNull(legacyBalanceFallback(setting(), it)) }
        assertNull(legacyBalanceFallback(setting().copy(balanceOption = BalanceOption(true, "/custom", "data.total_usage")), 404))
        assertNull(legacyBalanceFallback(setting().copy(balanceOption = BalanceOption(true, "/credits", "custom")), 404))
    }
    @Test fun cacheIdentityChangesOnRoutingCredentialOrParserButContainsNoKey() {
        val initial = setting()
        val identity = initial.balanceCacheIdentity()
        assertEquals(identity, initial.copy().balanceCacheIdentity())
        assertFalse(identity.contains(initial.apiKey))
        listOf(initial.copy(apiKey = "other-synthetic"), initial.copy(baseUrl = "https://other.example.org/v1"),
            initial.copy(balanceOption = initial.balanceOption.copy(resultPath = "different"))).forEach {
            assertNotEquals(identity, it.balanceCacheIdentity())
        }
    }
    @Test fun currenciesStaySeparateWithDecimalPrecisionAndLocaleIndependentFormatting() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            val body = json.parseToJsonElement("""{"balance_infos":[{"currency":"CNY","total_balance":"100000000.125"},{"currency":"USD","total_balance":"0"}]}""").jsonObject
            assertEquals("100000000.13 CNY · 0.00 USD", formatProviderBalance(body, "unused", true))
        } finally { Locale.setDefault(before) }
    }
    @Test fun officialMissingInvalidAndUnknownValuesNeverBecomeZero() {
        listOf("{}", """{"balance_infos":[]}""", """{"balance_infos":[{"currency":"CNY"}]}""",
            """{"balance_infos":[{"currency":"EUR","total_balance":"1"}]}""",
            """{"balance_infos":[{"currency":"CNY","total_balance":"NaN"}]}""",
            """{"balance_infos":[{"currency":"CNY","total_balance":"1e999999999"}]}""").forEach {
            assertTrue(runCatching { formatProviderBalance(json.parseToJsonElement(it).jsonObject, "unused", true) }.isFailure)
        }
    }
    @Test fun legacy404UsesSameCredentialAndOriginOnlyOnceThenOfficialShape() = runBlocking {
        val paths = mutableListOf<String>()
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("gateway.example.org", chain.request().url.host)
            assertEquals("Bearer synthetic-key", chain.request().header("Authorization"))
            paths += chain.request().url.encodedPath
            response(chain.request(), if (paths.size == 1) 404 else 200, if (paths.size == 1) "" else official)
        }.build())
        assertEquals("12.35 CNY", provider.getBalance(setting()))
        assertEquals(listOf("/v1/credits", "/v1/user/balance"), paths)
    }
    @Test fun non404ErrorNeverProbesFallbackAndNeverExposesRemoteErrorBody() = runBlocking {
        var calls = 0
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            calls++; response(chain.request(), 401, "synthetic-sensitive-error")
        }.build())
        val error = runCatching { provider.getBalance(setting()) }.exceptionOrNull()!!
        assertEquals(1, calls)
        assertFalse(error.message.orEmpty().contains("synthetic-sensitive-error"))
    }
    @Test fun noCredentialMakesNoNetworkRequest() = runBlocking {
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { error("must not send") }.build())
        assertEquals("未配置此连接的密钥", runCatching { provider.getBalance(setting().copy(apiKey = "")) }.exceptionOrNull()?.message)
    }
    @Test fun nonOfficialCustomResultStillWorks() = runBlocking {
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, """{"data":{"available":"99999999.125"}}""")
        }.build())
        assertEquals("99999999.13", provider.getBalance(setting().copy(balanceOption = BalanceOption(true, "/custom", "data.available"))))
    }
    @Test fun unknownContentLengthIsReadUntilEofWithinBound() = runBlocking {
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, official).newBuilder().body(object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source() = Buffer().writeUtf8(official)
            }).build()
        }.build())
        assertEquals("12.35 CNY", provider.getBalance(setting().copy(balanceOption = BalanceOption(true, "/user/balance", "balance_infos[0].total_balance"))))
    }
    @Test fun oversizedBodyRejectedWithoutEchoingBody() = runBlocking {
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, "x".repeat(65_537))
        }.build())
        assertEquals("余额接口返回异常", runCatching { provider.getBalance(setting()) }.exceptionOrNull()?.message)
    }
    @Test fun retiredOfficialSiliconFlowDefaultIsLocalNoticeEvenWhenBalanceToggleOff() {
        val officialSetting = setting().copy(baseUrl = "https://api.siliconflow.cn/v1",
            balanceOption = BalanceOption())
        assertEquals(SILICON_FLOW_RETIRED_BALANCE_NOTICE, officialSetting.retiredSiliconFlowBalanceNotice())
        assertFalse(officialSetting.balanceOption.enabled) // No persistent migration/toggle change.
        listOf("https://api.siliconflow.cn", "https://api.siliconflow.cn/v1/", "https://api.siliconflow.cn:443/v1").forEach {
            assertNotNull(officialSetting.copy(baseUrl = it).retiredSiliconFlowBalanceNotice())
        }
    }
    @Test fun retiredOfficialEndpointMakesNoRequestAndDoesNotRequireCredential() = runBlocking {
        var requests = 0
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            requests++; response(chain.request(), 200, "{}")
        }.build())
        listOf(BalanceOption(true, "/user/info", "data.totalBalance"), BalanceOption(true),
            BalanceOption(true, "/user/balance", "balance_infos[0].total_balance")).forEach { option ->
            val original = setting().copy(baseUrl = "https://api.siliconflow.cn/v1", apiKey = "", balanceOption = option)
            assertEquals(SILICON_FLOW_RETIRED_BALANCE_NOTICE, runCatching { provider.getBalance(original) }.exceptionOrNull()?.message)
            assertEquals(option, original.balanceOption)
        }
        assertEquals(0, requests)
    }
    @Test fun retirementNeverMatchesDisplayNameProxyLookalikeOrUnverifiedOrigins() {
        listOf("https://proxy.example.org/v1", "https://api.siliconflow.cn.evil.invalid/v1",
            "https://api.siliconflow.com/v1", "http://api.siliconflow.cn/v1",
            "https://api.siliconflow.cn:8443/v1", "https://api.siliconflow.cn/custom/v1",
            "https://synthetic-key@api.siliconflow.cn/v1", "https://api.siliconflow.cn/v1?other=1",
            "https://api.siliconflow.cn/v1#other", "https://api.siliconflow.cn/other/../v1").forEach { base ->
            assertNull(setting().copy(name = "硅基流动", baseUrl = base).retiredSiliconFlowBalanceNotice())
        }
    }
    @Test fun customOfficialBalancePathAndCustomLegacyParserArePreserved() {
        val original = setting().copy(baseUrl = "https://api.siliconflow.cn/v1")
        listOf(BalanceOption(true, "/custom/balance", "data.available"),
            BalanceOption(true, "/credits", "data.available"),
            BalanceOption(true, "/user/balance", "data.available")).forEach { option ->
            assertNull(original.copy(balanceOption = option).retiredSiliconFlowBalanceNotice())
        }
    }
    @Test fun proxyUserInfoContractStillUsesOnlyOriginalCredentialAndParser() = runBlocking {
        var calls = 0
        val provider = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("gateway.example.org", chain.request().url.host)
            assertEquals("/v1/user/info", chain.request().url.encodedPath)
            assertEquals("Bearer synthetic-key", chain.request().header("Authorization"))
            response(chain.request(), 200, """{"data":{"totalBalance":"3.25"}}""")
        }.build())
        assertEquals("3.25", provider.getBalance(setting().copy(balanceOption = BalanceOption(true, "/user/info", "data.totalBalance"))))
        assertEquals(1, calls)
    }
    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
