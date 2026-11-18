package com.bipin.clicker

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Naya update aane par ye screen user ke saamne dikhti hai.
 * Download/install user khud karta hai ("Update Karein" dabake). Kuch bhi chup-chap install nahi hota.
 */
class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_FORCED = "forced"
    }

    private var forced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val notes = intent.getStringArrayListExtra(EXTRA_NOTES) ?: arrayListOf()
        forced = intent.getBooleanExtra(EXTRA_FORCED, false)

        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Naya Update Available"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Aapka version: ${UpdateManager.currentName(this@UpdateActivity)}\nNaya version: $name"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        })

        if (forced) {
            root.addView(TextView(this).apply {
                text = "Ye update zaroori hai. Update karne ke baad hi app chalega."
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, pad / 2)
            })
        }

        if (notes.isNotEmpty()) {
            val sv = ScrollView(this)
            sv.addView(TextView(this).apply {
                text = notes.joinToString("\n") { "• $it" }
                textSize = 15f
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            })
            root.addView(sv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            root.addView(android.view.View(this), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root.addView(Button(this).apply {
            text = "Update Karein"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@UpdateActivity, "Link nahi khul paya", Toast.LENGTH_LONG).show()
                }
            }
        })

        if (!forced) {
            root.addView(Button(this).apply {
                text = "Baad Me"
                setOnClickListener {
                    UpdateManager.snooze(this@UpdateActivity, code)
                    finish()
                }
            })
        }

        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Zaroori update me back se band nahi hoga
        if (!forced) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateManager.markClosed()
    }
}
