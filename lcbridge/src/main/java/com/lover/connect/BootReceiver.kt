package com.lover.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // An isolated instrumentation process must not let an asynchronous
        // system broadcast bypass its synthetic Context/storage wrapper.
        if (!LcExternalRecoveryGate.isAllowed()) return
        val action = intent?.action
        if (!McpServiceLifecyclePolicy.handlesRestoreBroadcast(action)) return

        McpServiceController.restoreForBroadcast(context, action)
        LocationSafetyManager.restoreAfterBoot(context)
        val pendingResult = goAsync()
        AndroidCompanionAlarms.restoreAsync(context) { pendingResult.finish() }
    }
}
