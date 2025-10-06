package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<android.widget.ImageButton>(R.id.homeButton)?.setOnClickListener {
            // Use explicit class name to avoid potential resolver issues
            val homeIntent = Intent().setClassName(
                this@AboutActivity,
                "com.example.cosc3001.HomeActivity"
            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(homeIntent)
        }
    }
}
