package com.example.phonecamera

import android.app.Application
import com.example.phonecamera.di.AppContainer
import com.example.phonecamera.di.AppContainerImpl

class PhoneCameraApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainerImpl(this)
    }
}
