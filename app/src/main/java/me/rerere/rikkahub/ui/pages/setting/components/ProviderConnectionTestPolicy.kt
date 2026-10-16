package me.rerere.rikkahub.ui.pages.setting.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import kotlin.uuid.Uuid

internal fun preferredConnectionTestModel(models: List<Model>, currentModelId: Uuid?): Model? =
    models.firstOrNull { it.type == ModelType.CHAT && it.id == currentModelId }
        ?: models.firstOrNull { it.type == ModelType.CHAT }

/** Each step is a new request: never parallelize, repeat, or continue after a failed prerequisite. */
internal suspend fun runConnectionTestSteps(steps: List<suspend () -> Unit>, onFailure: (Int, Exception) -> Unit): Int {
    for ((index, step) in steps.withIndex()) {
        currentCoroutineContext().ensureActive()
        try { step(); currentCoroutineContext().ensureActive() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(index, error); return index }
    }
    return steps.size
}
