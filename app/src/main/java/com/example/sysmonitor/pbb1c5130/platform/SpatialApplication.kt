package com.example.sysmonitor.pbb1c5130.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.sysmonitor.pbb1c5130.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
