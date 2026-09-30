package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.balanceCacheIdentity
import me.rerere.ai.provider.providers.openai.retiredSiliconFlowBalanceNotice
import me.rerere.ai.provider.providers.openai.SILICON_FLOW_BALANCE_WEBSITE
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.rikkahub.utils.SimpleCache
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

private val cache = SimpleCache.builder<String, String>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

@Composable
fun ProviderBalanceText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    if (providerSetting is ProviderSetting.OpenAI && providerSetting.retiredSiliconFlowBalanceNotice() != null) {
        val uriHandler = LocalUriHandler.current
        // A local capability notice, not a balance or a request; keep the saved toggle untouched.
        Text("余额接口已停用 · 官网查看", modifier = modifier.clickable(onClickLabel = "打开硅基流动官方控制台") {
            runCatching { uriHandler.openUri(SILICON_FLOW_BALANCE_WEBSITE) }
        }, style = style, color = color.takeOrElse { LocalContentColor.current }, maxLines = 1)
        return
    }
    if (!providerSetting.balanceOption.enabled || providerSetting !is ProviderSetting.OpenAI) {
        // Balance option is disabled or provider is not OpenAI type
        return
    }

    val providerManager = koinInject<ProviderManager>()

    val identity = remember(providerSetting.id, providerSetting.baseUrl, providerSetting.apiKey,
        providerSetting.balanceOption) { providerSetting.balanceCacheIdentity() }
    var refresh by remember(identity) { mutableIntStateOf(0) }
    val value = produceState(initialValue = "查询余额…", key1 = identity, key2 = refresh) {
        value = "查询余额…"
        // Check cache first
        val cachedBalance = if (refresh == 0) cache.getIfPresent(identity) else null
        if (cachedBalance != null) {
            value = cachedBalance
        } else {
            // Fetch balance from API
            try {
                val balance = providerManager.getProviderByType(providerSetting).getBalance(providerSetting)
                // Cache the result
                cache.put(identity, balance)
                value = balance
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Never put raw remote bodies, URLs or credentials into the model picker.
                value = when {
                    providerSetting.apiKey.isBlank() -> "未配置此连接的密钥"
                    failure is IllegalStateException && failure.message?.startsWith("余额") == true -> failure.message!!
                    failure is IllegalStateException && failure.message == "此连接未提供余额接口" -> failure.message!!
                    else -> "余额暂不可用，点此刷新"
                }
            }
        }
    }

    Row(
        modifier = modifier.clickable(onClickLabel = "刷新余额") { refresh++ },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = HugeIcons.MoneyBag02,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current }
        )
        Text(
            text = value.value,
            style = style,
            maxLines = 1,
            color = color
        )
    }
}
