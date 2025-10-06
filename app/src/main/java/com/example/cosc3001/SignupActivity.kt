package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * UI-only Signup screen. No backend or validation logic implemented yet.
 */
class SignupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        // Link to Login
        findViewById<TextView>(R.id.linkToLogin)?.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }
}
