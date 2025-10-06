package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Login screen with Supabase email/password authentication.
 * Only allows navigation if the user's email is confirmed.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var inputEmail: EditText
    private lateinit var inputPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var linkToSignup: TextView
    private var progressBar: ProgressBar? = null // optional if layout has it

    private val supabaseClient get() = SupabaseProvider.client

    companion object { private const val TAG = "LoginActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        bindViews()
        setupValidationWatcher()
        setupClicks()
        btnLogin.isEnabled = false
    }

    private fun bindViews() {
        inputEmail = findViewById(R.id.inputEmail)
        inputPassword = findViewById(R.id.inputPassword)
        btnLogin = findViewById(R.id.btnLogin)
        linkToSignup = findViewById(R.id.linkToSignup)
        progressBar = findViewById(R.id.progressBar) // if exists
    }

    private fun setupClicks() {
        linkToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
        btnLogin.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            val password = inputPassword.text.toString()
            if (validateFields(email, password, showErrors = true)) {
                performLogin(email, password)
            }
        }
    }

    private fun setupValidationWatcher() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnLogin.isEnabled = validateFields(
                    inputEmail.text.toString().trim(),
                    inputPassword.text.toString(),
                    showErrors = false
                )
            }
        }
        inputEmail.addTextChangedListener(watcher)
        inputPassword.addTextChangedListener(watcher)
    }

    private fun validateFields(email: String, password: String, showErrors: Boolean): Boolean {
        var ok = true
        fun err(view: EditText, msg: String) { if (showErrors) view.error = msg; ok = false }
        if (email.isEmpty()) err(inputEmail, "Email required")
        else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) err(inputEmail, "Invalid email")
        if (password.length < 6) err(inputPassword, "Min 6 chars")
        return ok
    }

    private fun setLoading(loading: Boolean) {
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
        inputEmail.isEnabled = !loading
        inputPassword.isEnabled = !loading
        linkToSignup.isEnabled = !loading
    }

    private fun performLogin(email: String, password: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                supabaseClient.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                val user = supabaseClient.auth.currentUserOrNull()
                if (user == null) {
                    showToast("Login failed: no active session")
                    return@launch
                }
                // Robust confirmation detection: some versions expose emailConfirmedAt, others confirmedAt
                val confirmed = runCatching {
                    val emailConfirmed = runCatching { user.emailConfirmedAt }.getOrNull()
                    val generalConfirmed = runCatching { user.confirmedAt }.getOrNull()
                    (emailConfirmed != null) || (generalConfirmed != null)
                }.getOrDefault(false) // fail closed: default false if evaluation fails
                if (!confirmed) {
                    runCatching { supabaseClient.auth.signOut() }
                    showToast("Email not confirmed. Please check your inbox.")
                    return@launch
                }
                navigateToHome()
            } catch (t: Throwable) {
                Log.w(TAG, "Login error", t)
                showToast("Login failed: ${t.message ?: "Unknown error"}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
