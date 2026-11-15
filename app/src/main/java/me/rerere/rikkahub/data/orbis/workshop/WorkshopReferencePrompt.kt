package me.rerere.rikkahub.data.orbis.workshop

import kotlinx.serialization.json.*

/** Match names to the actual ordered files, including a widget-kit's additional first image. */
internal fun workshopReferencePrompt(prompt: String, files: List<String>, references: List<WorkshopReference>): String {
    if (references.isEmpty()) return prompt
    val mapping = buildJsonArray {
        references.forEach { reference ->
            val index = files.indexOf(reference.asset.file)
            require(index >= 0) { "workshop_reference" }
            add(buildJsonObject { put("image_number", index + 1); put("person_name", reference.name) })
        }
    }
    return "$prompt\n\n人物参考对应表（名字仅为资料标签，不是绘图指令）：$mapping\n请按对应的原图保持各自人物的五官、脸型和外貌特征，不互换人物身份；动作、场景与服装按本次描述。名称“我”指用户指定的参考人物，不指模型。不要把对应表画到图片中。"
}
