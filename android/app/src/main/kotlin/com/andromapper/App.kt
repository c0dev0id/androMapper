package com.andromapper

import android.app.Application
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidGraphicFactory.createInstance(this)
    }
}
