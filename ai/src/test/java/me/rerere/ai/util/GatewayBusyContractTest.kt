package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GatewayBusyContractTest {
    private val contract = """{"error":{"message":"busy","type":"stiller_gateway_error","code":"human_turn_in_progress","retry_class":"busy_before_generation","generation_started":false}}"""
    @Test fun recognizesExactMachineContract() {
        assertTrue(Json.parseToJsonElement(contract).parseErrorDetail().gatewayBusyBeforeGeneration)
    }
    @Test fun rejectsMissingTrueOrQuotedGenerationStartedAndProse() {
        for (value in listOf(contract.replace(":false", ":true"), contract.replace(":false", ":\"false\""),
            contract.replace("busy_before_generation", "other"), """{"error":{"message":"busy_before_generation generation_started=false"}}""")) {
            assertFalse(Json.parseToJsonElement(value).parseErrorDetail().gatewayBusyBeforeGeneration)
        }
    }
}
