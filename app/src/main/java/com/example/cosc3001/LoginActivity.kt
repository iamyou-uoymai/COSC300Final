package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * UI-only Login screen. No authentication logic implemented yet.
 */
class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Login button navigation
        findViewById<Button>(R.id.btnLogin)?.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
        // Link to Signup UI (no auth logic)
        findViewById<TextView>(R.id.linkToSignup)?.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }
}
