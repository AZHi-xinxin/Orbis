package com.lover.connect

import org.junit.Assert.*
import org.junit.Test

class ScreenObservationGuidanceTest {
    @Test fun statusGuidanceDistinguishesConfigurationFromEvidence() {
        assertTrue(SCREEN_OBSERVATION_GUIDANCE.contains("不代表实际观察成功"))
        assertTrue(SCREEN_OBSERVATION_GUIDANCE.contains("不能证明某品牌不兼容"))
        listOf("小L", "小 L", "Little L", "eyes_enabled").forEach {
            assertFalse(SCREEN_OBSERVATION_GUIDANCE.contains(it))
        }
    }
}
