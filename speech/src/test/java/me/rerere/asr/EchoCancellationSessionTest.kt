package me.rerere.asr

import org.junit.Assert.*
import org.junit.Test

class EchoCancellationSessionTest {
    @Test fun ordinaryDictationDoesNotProbeOrEnableEffects() {
        val factory = Factory()
        val states = mutableListOf<EchoCancellationState>()
        EchoCancellationSession(factory, states::add).start(false, 42)
        assertEquals(0, factory.probes)
        assertTrue(states.none { it.active || it.available })
    }

    @Test fun activeRequiresAvailableCreatedEnabledAndControlledEffectOnCorrectSession() {
        val factory = Factory()
        val states = mutableListOf<EchoCancellationState>()
        val session = EchoCancellationSession(factory, states::add)
        session.start(true, 91)
        assertEquals(listOf(91), factory.ids)
        assertEquals(EchoCancellationState(true, true), states.last())
        session.close()
        assertEquals(EchoCancellationState(), states.last())
        assertEquals(1, factory.effects.single().releases)
    }

    @Test fun unavailableNeverCreatesOrClaimsActive() {
        val factory = Factory(available = false)
        val states = mutableListOf<EchoCancellationState>()
        EchoCancellationSession(factory, states::add).start(true, 5)
        assertTrue(factory.ids.isEmpty())
        assertEquals(EchoCancellationState(), states.last())
    }

    @Test fun availableButNoEffectIsNotActive() {
        val states = mutableListOf<EchoCancellationState>()
        EchoCancellationSession(object : EchoCancellationFactory {
            override fun isAvailable() = true
            override fun create(audioSessionId: Int): EchoCancellationEffect? = null
        }, states::add).start(true, 6)
        assertEquals(EchoCancellationState(true, false), states.last())
    }

    @Test fun failedEnableOrLackOfControlNeverAdvertisesDuplex() {
        for (mode in 0..2) {
            val states = mutableListOf<EchoCancellationState>()
            val factory = Factory(make = { Effect(enableResult = mode != 0, enabled = mode != 1, control = mode != 2) })
            EchoCancellationSession(factory, states::add).start(true, 7)
            assertEquals(EchoCancellationState(true, false), states.last())
        }
    }

    @Test fun disabledOrLostControlUpdatesLiveStateAndRecoveryIsMeasured() {
        val states = mutableListOf<EchoCancellationState>()
        val factory = Factory()
        EchoCancellationSession(factory, states::add).start(true, 8)
        val effect = factory.effects.single()
        effect.enabled = false
        effect.listener?.invoke()
        assertFalse(states.last().active)
        effect.enabled = true
        effect.control = false
        effect.listener?.invoke()
        assertFalse(states.last().active)
        effect.control = true
        effect.listener?.invoke()
        assertTrue(states.last().active)
    }

    @Test fun closeIsIdempotentAndLateNativeCallbackCannotReenable() {
        val states = mutableListOf<EchoCancellationState>()
        val factory = Factory()
        val session = EchoCancellationSession(factory, states::add)
        session.start(true, 9)
        val late = factory.effects.single().listener!!
        session.close()
        session.close()
        late()
        assertEquals(EchoCancellationState(), states.last())
        assertEquals(1, factory.effects.single().releases)
    }

    @Test fun replacedCaptureReleasesOldEffectAndIgnoresItsLateCallback() {
        val states = mutableListOf<EchoCancellationState>()
        val factory = Factory()
        val session = EchoCancellationSession(factory, states::add)
        session.start(true, 10)
        val old = factory.effects.single()
        val late = old.listener!!
        session.start(true, 11)
        old.enabled = false
        late()
        assertEquals(listOf(10, 11), factory.ids)
        assertEquals(1, old.releases)
        assertTrue(states.last().active)
    }

    @Test fun nativeExceptionsFailClosedAndReleaseCreatedEffect() {
        val states = mutableListOf<EchoCancellationState>()
        var released = 0
        EchoCancellationSession(object : EchoCancellationFactory {
            override fun isAvailable() = true
            override fun create(audioSessionId: Int) = object : EchoCancellationEffect {
                override fun enable(): Boolean = throw IllegalStateException("synthetic native failure")
                override fun isEnabled() = true
                override fun hasControl() = true
                override fun listen(onChange: (() -> Unit)?) {}
                override fun release() { released++ }
            }
        }, states::add).start(true, 12)
        assertEquals(1, released)
        assertFalse(states.last().active)
    }

    private class Factory(val available: Boolean = true, val make: () -> Effect = { Effect() }) : EchoCancellationFactory {
        var probes = 0
        val ids = mutableListOf<Int>()
        val effects = mutableListOf<Effect>()
        override fun isAvailable(): Boolean { probes++; return available }
        override fun create(audioSessionId: Int): EchoCancellationEffect {
            ids += audioSessionId
            return make().also(effects::add)
        }
    }
    private class Effect(val enableResult: Boolean = true, var enabled: Boolean = true, var control: Boolean = true) : EchoCancellationEffect {
        var listener: (() -> Unit)? = null
        var releases = 0
        override fun enable() = enableResult
        override fun isEnabled() = enabled
        override fun hasControl() = control
        override fun listen(onChange: (() -> Unit)?) { listener = onChange }
        override fun release() { releases++ }
    }
}
