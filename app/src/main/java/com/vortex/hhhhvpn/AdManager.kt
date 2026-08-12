package com.vortex.hhhhvpn

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {

    private const val TAG = "YohanAds"

    // Real AdMob Unit IDs provided by user
    private const val APP_OPEN_AD_UNIT = "ca-app-pub-6988527982574142/4804737092"
    private const val INTERSTITIAL_AD_UNIT = "ca-app-pub-6988527982574142/4098426012"

    private var appOpenAd: AppOpenAd? = null
    private var interstitialAd: InterstitialAd? = null
    private var isLoadingAppOpen = false
    private var isLoadingInterstitial = false

    // ─── App Open Ad ────────────────────────────────────────

    fun loadAppOpenAd(context: Context) {
        if (isLoadingAppOpen || appOpenAd != null) return
        isLoadingAppOpen = true

        val request = AdRequest.Builder().build()
        AppOpenAd.load(
            context,
            APP_OPEN_AD_UNIT,
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAppOpen = false
                    Log.d(TAG, "App Open Ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenAd = null
                    isLoadingAppOpen = false
                    Log.e(TAG, "App Open Ad failed: ${error.message}")
                }
            }
        )
    }

    fun showAppOpenAdIfAvailable(activity: Activity, onDismissed: (() -> Unit)? = null) {
        val ad = appOpenAd
        if (ad == null) {
            loadAppOpenAd(activity)
            onDismissed?.invoke()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                YohanApp.instance.setShowingAd(false)
                loadAppOpenAd(activity) // preload next
                onDismissed?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                YohanApp.instance.setShowingAd(false)
                loadAppOpenAd(activity)
                onDismissed?.invoke()
            }

            override fun onAdShowedFullScreenContent() {
                YohanApp.instance.setShowingAd(true)
            }
        }

        YohanApp.instance.setShowingAd(true)
        ad.show(activity)
    }

    // ─── Interstitial Ad (after connect) ────────────────────

    fun loadInterstitial(context: Context) {
        if (isLoadingInterstitial || interstitialAd != null) return
        isLoadingInterstitial = true

        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            INTERSTITIAL_AD_UNIT,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoadingInterstitial = false
                    Log.d(TAG, "Interstitial Ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isLoadingInterstitial = false
                    Log.e(TAG, "Interstitial Ad failed: ${error.message}")
                }
            }
        )
    }

    fun showInterstitial(activity: Activity, onClosed: () -> Unit) {
        val ad = interstitialAd
        if (ad == null) {
            loadInterstitial(activity)
            onClosed()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitial(activity)
                onClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                loadInterstitial(activity)
                onClosed()
            }
        }

        ad.show(activity)
    }
}
