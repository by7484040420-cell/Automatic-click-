package com.bipin.clicker

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/** Beech me video ad dikhata hai. Ad na mile to dabba chhupa rehta hai (khali jagah nahi dikhti). */
object NativeVideoAd {

    private var current: NativeAd? = null

    fun load(activity: Activity, box: NativeAdView) {
        val options = NativeAdOptions.Builder()
            .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
            .build()
        val loader = AdLoader.Builder(activity, AdIds.NATIVE_VIDEO)
            .forNativeAd { ad ->
                if (activity.isDestroyed || activity.isFinishing) {
                    ad.destroy()
                } else {
                    current?.destroy()
                    current = ad
                    bind(box, ad)
                    box.visibility = View.VISIBLE
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    box.visibility = View.GONE
                }
            })
            .withNativeAdOptions(options)
            .build()
        loader.loadAd(AdRequest.Builder().build())
    }

    private fun bind(box: NativeAdView, ad: NativeAd) {
        val media = box.findViewById<MediaView>(R.id.adMedia)
        val head = box.findViewById<TextView>(R.id.adHeadline)
        val cta = box.findViewById<Button>(R.id.adCta)

        box.setMediaView(media)
        box.setHeadlineView(head)
        box.setCallToActionView(cta)

        head.text = ad.headline
        val action = ad.callToAction
        if (action == null) {
            cta.visibility = View.GONE
        } else {
            cta.visibility = View.VISIBLE
            cta.text = action
        }
        val mc = ad.mediaContent
        if (mc != null) media.setMediaContent(mc)
        box.setNativeAd(ad)
    }

    fun destroy() {
        current?.destroy()
        current = null
    }
}
