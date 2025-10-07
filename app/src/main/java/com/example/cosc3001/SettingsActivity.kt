package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private val supabaseClient get() = SupabaseProvider.client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Home button navigation
        findViewById<android.widget.ImageButton>(R.id.homeButton)?.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        // User details
        val userNameText = findViewById<TextView>(R.id.userNameValue)
        val userEmailText = findViewById<TextView>(R.id.userEmailValue)
        val changePasswordButton = findViewById<Button>(R.id.changePasswordButton)
        val logoutButton = findViewById<Button>(R.id.logoutButton)

        lifecycleScope.launch {
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                val name = user.userMetadata?.get("full_name")?.toString() ?: user.userMetadata?.get("name")?.toString() ?: "Unknown"
                val email = user.email ?: "No email"
                userNameText.text = name
                userEmailText.text = email

                // Enable change password button
                changePasswordButton.isEnabled = true
                changePasswordButton.setOnClickListener {
                    lifecycleScope.launch {
                        try {
                            supabaseClient.auth.resetPasswordForEmail(email)
                            Toast.makeText(this@SettingsActivity, "Password reset link sent to $email", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@SettingsActivity, "Failed to send reset link: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                userNameText.text = "Not logged in"
                userEmailText.text = "Not logged in"
                changePasswordButton.isEnabled = false
            }
        }

        // Logout button
        logoutButton.setOnClickListener {
            lifecycleScope.launch {
                supabaseClient.auth.signOut()
                val intent = Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
