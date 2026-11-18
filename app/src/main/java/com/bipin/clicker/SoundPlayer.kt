// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/SoundPlayer.kt
package com.bipin.clicker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Order uthte hi ek awaz bajti hai.
 *
 * Agar aapne res/raw me apni khud ki sound file daali hai (jaise Paytm/coin
 * jaisi "cha-ching" ki mp3), to wahi bajegi. File ka naam bilkul ye hona chahiye:
 *   res/raw/order_sound.mp3   (ya .wav / .ogg)
 * Koi file na ho to app khud ek chhota "cha-ching" jaisa 2-tone chime bana kar bajata hai,
 * isके liye koi file chahiye hi nahi.
 */
object SoundPlayer {

    private val handler = Handler(Looper.getMainLooper())

    fun playOrderSound(context: Context) {
        val app = context.applicationContext
        handler.post {
            val resId = app.resources.getIdentifier("order_sound", "raw", app.packageName)
            if (resId != 0) {
                playCustomFile(app, resId)
            } else {
                playBuiltInChime()
            }
        }
    }

    private fun playCustomFile(context: Context, resId: Int) {
        try {
            val mp = MediaPlayer.create(context, resId) ?: return playBuiltInChime()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.start()
        } catch (e: Exception) {
            playBuiltInChime()
        }
    }

    /** File na ho to ye "cha-ching" jaisa chhota do-tone chime khud bana kar bajata hai. */
    private fun playBuiltInChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            tg.startTone(ToneGenerator.TONE_CDMA_PIP, 90)
            handler.postDelayed({
                try {
                    tg.startTone(ToneGenerator.TONE_CDMA_PIP, 90)
                } catch (_: Exception) {
                }
            }, 110L)
            handler.postDelayed({
                try {
                    tg.release()
                } catch (_: Exception) {
                }
            }, 400L)
        } catch (e: Exception) {
        }
    }
}
