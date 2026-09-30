package me.rerere.rikkahub.data.ai

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.lover.connect.LcExternalRecoveryGate

/** Opt-in test runner only: do not start RikkaHubApp/Koin/its background jobs for this fixture. */
class IsolatedGenerationLoopRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        LcExternalRecoveryGate.disableForIsolatedTestProcess()
        return super.newApplication(cl, Application::class.java.name, context)
    }
}
