package me.rerere.rikkahub.data.orbis.activities

enum class PetPose(val frame: Int) { IDLE(0), CONTENT(1), GREET(2), EAT(3), PLAY(4), SLEEP(5) }

/** Presentation only: these steps never settle care, spend credits, or call a model. */
data class PetAnimationStep(val action: String, val durationMillis: Long)

fun petAnimationSteps(action: String, aiActions: List<String> = emptyList()): List<PetAnimationStep> {
    val actions = if (action == "ai_reaction") aiActions.filterNot { it in setOf("speak", "observe") }
        .take(3).ifEmpty { listOf("observe") } else listOf(action)
    return actions.flatMap { value ->
        when (value) {
            "feed", "eat" -> listOf(PetAnimationStep("approach_food", 650), PetAnimationStep("feed", 2400), PetAnimationStep("content", 900))
            "water", "drink" -> listOf(PetAnimationStep("approach_water", 650), PetAnimationStep("water", 2400), PetAnimationStep("content", 900))
            "pet", "ask_pet", "mood_up" -> listOf(PetAnimationStep("greet", 550), PetAnimationStep("pet", 2100))
            "play", "ask_play", "mess_up", "explore" -> listOf(PetAnimationStep("greet", 500), PetAnimationStep("play", 2700), PetAnimationStep("content", 750))
            "sleep" -> listOf(PetAnimationStep("content", 550), PetAnimationStep("sleep", 1500))
            else -> listOf(PetAnimationStep(value, 2600))
        }
    }
}

fun petPose(action: String, sleeping: Boolean, phase: Float): PetPose = when {
    action in setOf("feed", "water", "eat", "drink", "hungry", "thirsty") -> PetPose.EAT
    action in setOf("pet", "ask_pet", "mood_up", "content") -> PetPose.CONTENT
    action in setOf("play", "ask_play", "mess_up", "noisy", "explore") -> PetPose.PLAY
    sleeping || action == "sleep" -> PetPose.SLEEP
    action in setOf("greet", "lonely", "observe", "approach_food", "approach_water") -> PetPose.GREET
    phase > .82f -> PetPose.CONTENT // Long enough for the crossfade to reach closed eyes.
    else -> PetPose.IDLE
}

/** A quiet look-around every third breath; it doesn't invent a care event. */
fun petIdlePose(phase: Float, idleCycle: Int): PetPose =
    if (idleCycle % 3 == 2 && phase in 0.20f..0.52f) PetPose.GREET else petPose("", false, phase)
