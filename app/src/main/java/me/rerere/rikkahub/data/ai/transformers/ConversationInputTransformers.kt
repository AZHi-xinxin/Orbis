package me.rerere.rikkahub.data.ai.transformers

/** Ordinary chat and an interrupted chat use the same attachment/prompt projection.
 * Only call-archive access is optional: a fresh human turn never waits for its old writer.
 */
internal fun conversationInputTransformers(
    includeVoiceArchive: Boolean = true,
    includeVideoCall: Boolean = true,
): List<InputMessageTransformer> = buildList {
    add(TimeReminderTransformer)
    add(PromptInjectionTransformer)
    add(PlaceholderTransformer)
    add(DocumentAsPromptTransformer)
    add(OcrTransformer)
    add(OrbisEventTransformer)
    if (includeVoiceArchive) add(OrbisVoiceCallTransformer)
    if (includeVideoCall) add(OrbisVideoCallTransformer)
}
