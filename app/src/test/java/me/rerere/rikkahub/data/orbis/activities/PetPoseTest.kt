package me.rerere.rikkahub.data.orbis.activities
import org.junit.Assert.*
import org.junit.Test

class PetPoseTest {
    @Test fun feedingPlayingSleepingAndPettingAreDifferentPictures() {
        val poses = listOf("feed","play","sleep","pet").map { petPose(it,false,0f) }
        assertEquals(4, poses.distinct().size)
    }
    @Test fun careWakesSleepingPet() {
        assertEquals(PetPose.EAT, petPose("water",true,0f))
        assertEquals(PetPose.CONTENT, petPose("pet",true,0f))
        assertEquals(PetPose.SLEEP, petPose("",true,0f))
    }
    @Test fun neutralIdleAndBlinkUseOwnFrames() {
        assertEquals(PetPose.IDLE, petPose("",false,0f))
        assertEquals(PetPose.CONTENT, petPose("",false,.96f))
    }
    @Test fun aiActionsFollowSameStateMapping() {
        assertEquals(petPose("feed",false,0f), petPose("eat",false,0f))
        assertEquals(PetPose.GREET, petPose("greet",false,0f))
    }
    @Test fun careAnimationApproachesActsAndSettlesWithoutDuplicatingTheCare() {
        assertEquals(listOf("approach_food","feed","content"),petAnimationSteps("feed").map { it.action })
        assertEquals(listOf("approach_water","water","content"),petAnimationSteps("water").map { it.action })
        assertTrue(petAnimationSteps("play").sumOf { it.durationMillis } in 3000L..5000L)
    }
    @Test fun allAllowedAiActionsPlayInOrderInsteadOfOnlyTheFirstAction() {
        val steps=petAnimationSteps("ai_reaction",listOf("speak","eat","ask_pet"))
        assertEquals(listOf("approach_food","feed","content","greet","pet"),steps.map { it.action })
        assertEquals(listOf("observe"),petAnimationSteps("ai_reaction",listOf("speak")).map { it.action })
    }
    @Test fun quietIdleLooksAroundAndBlinkHasTimeToFinishCrossfade() {
        assertEquals(PetPose.IDLE,petIdlePose(.3f,0))
        assertEquals(PetPose.GREET,petIdlePose(.3f,2))
        assertEquals(PetPose.CONTENT,petIdlePose(.90f,2))
        assertEquals(PetPose.SLEEP,petPose("",true,.9f))
    }
}
