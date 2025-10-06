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
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod

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
    private lateinit var btnTogglePassword: ImageButton
    private var passwordVisible = false

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
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
    }

    private fun setupClicks() {
        linkToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
        btnTogglePassword.setOnClickListener { togglePasswordVisibility() }
        btnLogin.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            val password = inputPassword.text.toString()
            if (validateFields(email, password, showErrors = true)) {
                performLogin(email, password)
            } else {
                showToast(requirementSummary(email, password))
            }
        }
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        val method = if (passwordVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
        inputPassword.transformationMethod = method
        inputPassword.setSelection(inputPassword.text?.length ?: 0)
        btnTogglePassword.setImageResource(if (passwordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        btnTogglePassword.contentDescription = if (passwordVisible) "Hide password" else "Show password"
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

    private fun requirementSummary(email: String, password: String): String {
        val issues = mutableListOf<String>()
        if (email.isBlank()) issues += "Email required"
        else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) issues += "Email invalid"
        if (password.length < 6) issues += "Password min 6 chars"
        return if (issues.isEmpty()) "All requirements satisfied" else issues.joinToString(" • ")
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
        btnTogglePassword.isEnabled = !loading
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
                val confirmed = runCatching {
                    val emailConfirmed = runCatching { user.emailConfirmedAt }.getOrNull()
                    val generalConfirmed = runCatching { user.confirmedAt }.getOrNull()
                    (emailConfirmed != null) || (generalConfirmed != null)
                }.getOrDefault(false)
                if (!confirmed) {
                    runCatching { supabaseClient.auth.signOut() }
                    showToast("Email not confirmed. Please check your inbox.")
                    return@launch
                }
                // Ensure metadata has display name & phone (full_name + phone keys) if user allowed login.
                val meta = user.userMetadata
                val nameInput = inputEmail.text.toString() // placeholder; real display name not on login screen
                val phonePlaceholder = "" // we don't collect phone here; leave blank if not provided
                if (meta == null || meta["full_name"] == null || meta["phone"] == null) {
                    safeUpdateAuthMetadata(nameInput, phonePlaceholder)
                }
                // NEW: make sure a profile row exists / is updated with phone + name/email metadata
                ensureProfileSynced()
                navigateToHome()
            } catch (t: Throwable) {
                Log.w(TAG, "Login error", t)
                showToast("Login failed: ${t.message ?: "Unknown error"}")
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun safeUpdateAuthMetadata(name: String, phone: String) {
        runCatching {
            supabaseClient.auth.updateUser {
                data = buildJsonObject {
                    if (name.isNotBlank()) {
                        put("name", name.take(80))
                        put("full_name", name.take(80))
                    }
                    if (phone.isNotBlank()) put("phone", phone.take(24))
                }
            }
        }.onFailure { Log.w(TAG, "Auth metadata refresh skipped: ${it.javaClass.simpleName}") }
    }

    private suspend fun ensureProfileSynced() {
        val user = supabaseClient.auth.currentUserOrNull() ?: return
        val meta = user.userMetadata
        val name = meta?.get("full_name")?.jsonPrimitive?.contentOrNull
            ?: meta?.get("name")?.jsonPrimitive?.contentOrNull
        val phone = meta?.get("phone")?.jsonPrimitive?.contentOrNull
        val email = runCatching { user.email }.getOrNull()
        if (phone.isNullOrBlank() && name.isNullOrBlank() && email.isNullOrBlank()) return
        val id = user.id
        val payload = buildMap<String, Any> {
            put("id", id)
            if (!name.isNullOrBlank()) put("name", name.take(80))
            if (!phone.isNullOrBlank()) put("phone", phone.take(24))
            if (!email.isNullOrBlank()) put("email", email.take(254))
        }
        if (payload.size <= 1) return
        runCatching {
            supabaseClient.postgrest["profiles"].insert(payload)
        }.onFailure { insertErr ->
            // Attempt update if row exists (simulate upsert)
            runCatching {
                val updateMap = payload - "id"
                if (updateMap.isNotEmpty()) {
                    supabaseClient.postgrest["profiles"].update(updateMap) { filter { eq("id", id) } }
                }
            }.onFailure { updateErr ->
                Log.w(TAG, "Profile sync suppressed: ${updateErr.javaClass.simpleName} (after ${insertErr.javaClass.simpleName})")
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
