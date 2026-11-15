package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicLong

/** A stopped producer permanently loses write ownership, including in NonCancellable cleanup. */
internal class ConversationWriteFence {
    private val epoch = AtomicLong()
    private val owner = ThreadLocal<Long?>()
    fun context() = owner.asContextElement(epoch.get())
    fun revoke() { epoch.incrementAndGet() }
    fun isCurrent(): Boolean = owner.get()?.let { it == epoch.get() } ?: true
    fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("旧生成已停止，当前聊天由新任务保存")
    }
}
