package io.novadeck.control

import android.app.Application
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine

class NovaDeckApp : Application() {
    lateinit var performanceController: PerformanceTierController
        private set

    lateinit var telemetryEngine: TelemetryEngine
        private set

    override fun onCreate() {
        super.onCreate()
        performanceController = PerformanceTierController(this)
        telemetryEngine = TelemetryEngine(this)
    }
}
