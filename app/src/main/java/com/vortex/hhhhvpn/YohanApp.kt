package com.vortex.hhhhvpn

import android.app.Application
import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.atomic.AtomicBoolean

class YohanApp : Application(), ActivityLifecycleCallbacks {

    private var currentActivity: Activity? = null
    private val isShowingAd = AtomicBoolean(false)

    companion object {
        lateinit var instance: YohanApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)

        // Initialize AdMob
        MobileAds.initialize(this) {}
    }

    fun getCurrentActivity(): Activity? = currentActivity

    fun setShowingAd(showing: Boolean) {
        isShowingAd.set(showing)
    }

    fun isShowingAd(): Boolean = isShowingAd.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) currentActivity = null
    }
}
