package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod

/**
 * Signup screen with Supabase email/password registration + optional profile insert.
 * Hardened with stricter validation, rate limiting, generic error responses, and input normalization.
 */
class SignupActivity : AppCompatActivity() {

    private lateinit var inputName: EditText
    private lateinit var inputPhone: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPassword: EditText
    private lateinit var inputConfirmPassword: EditText
    private lateinit var btnSignup: Button
    private lateinit var linkToLogin: TextView
    private lateinit var progressBar: ProgressBar
    // New views for password help
    private var tvPasswordRequirements: TextView? = null
    private var tvPasswordStatus: TextView? = null
    private lateinit var btnTogglePassword: ImageButton
    private lateinit var btnToggleConfirmPassword: ImageButton
    private var passwordVisible = false
    private var confirmPasswordVisible = false

    private val supabaseClient get() = SupabaseProvider.client

    private var activeSignupJob: Job? = null
    private var lastAttemptTs: Long = 0L
    private val attemptWindowMs = 10_000L // 10s window
    private val maxAttemptsInWindow = 3
    private var attemptsInWindow = 0

    // Precompiled phone regex (international, digits and common symbols) - sanitized further
    private val PHONE_REGEX = Regex("^[0-9+\\s()\\-]{6,20}$")
    private val PASSWORD_MIN_LENGTH = 8 // stronger than Supabase default

    companion object { private const val TAG = "SignupActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        bindViews()
        hardenInputs()
        setupValidationWatcher()
        setupClicks()
    }

    private fun bindViews() {
        inputName = findViewById(R.id.inputName)
        inputPhone = findViewById(R.id.inputPhone)
        inputEmail = findViewById(R.id.inputEmail)
        inputPassword = findViewById(R.id.inputPassword)
        inputConfirmPassword = findViewById(R.id.inputConfirmPassword)
        btnSignup = findViewById(R.id.btnSignup)
        linkToLogin = findViewById(R.id.linkToLogin)
        progressBar = findViewById(R.id.progressBar)
        tvPasswordRequirements = findViewById(R.id.tvPasswordRequirements)
        tvPasswordStatus = findViewById(R.id.tvPasswordStatus)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        btnToggleConfirmPassword = findViewById(R.id.btnToggleConfirmPassword)
    }

    private fun setupClicks() {
        linkToLogin.setOnClickListener { navigateToLogin() }
        tvPasswordRequirements?.setOnClickListener { showPasswordRequirementsDialog() }
        btnTogglePassword.setOnClickListener { togglePasswordVisibility(inputPassword, true) }
        btnToggleConfirmPassword.setOnClickListener { togglePasswordVisibility(inputConfirmPassword, false) }
        btnSignup.setOnClickListener {
            val form = collectForm()
            if (validateForm(form, showErrors = true)) {
                performSignup(form)
            } else {
                showToast(requirementSummary(form))
                // Also highlight password popup if password issues persist
                if (passwordIssues(form.password).isNotEmpty()) {
                    tvPasswordStatus?.let { it.text = requirementSummary(form); it.setTextColor(0xFFFFCC66.toInt()) }
                }
            }
        }
    }

    // Build a concise summary of unmet requirements for toast display
    private fun requirementSummary(form: FormData): String {
        val issues = mutableListOf<String>()
        if (form.name.isEmpty()) issues += "Name required"
        if (form.phone.isEmpty()) issues += "Phone required" else if (!PHONE_REGEX.matches(form.phone)) issues += "Phone invalid"
        if (form.email.isEmpty()) issues += "Email required" else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(form.email).matches()) issues += "Email invalid"
        val pwdIssues = passwordIssues(form.password)
        if (pwdIssues.isNotEmpty()) issues += pwdIssues
        if (form.confirm != form.password) issues += "Passwords must match"
        return if (issues.isEmpty()) "All requirements satisfied" else issues.joinToString(separator = " • ")
    }

    private data class FormData(
        val name: String,
        val phone: String,
        val email: String,
        val password: String,
        val confirm: String
    )

    private fun collectForm() = FormData(
        name = inputName.text.toString().trim(),
        phone = inputPhone.text.toString().trim(),
        email = inputEmail.text.toString().trim(),
        password = inputPassword.text.toString(),
        confirm = inputConfirmPassword.text.toString()
    )

    private fun validateForm(form: FormData, showErrors: Boolean): Boolean {
        var ok = true
        fun err(field: EditText, msg: String) { if (showErrors) field.error = msg; ok = false }

        if (form.name.isEmpty()) err(inputName, "Name required")
        if (form.phone.isEmpty()) err(inputPhone, "Phone required") else if (!PHONE_REGEX.matches(form.phone)) err(inputPhone, "Invalid phone")
        if (form.email.isEmpty()) err(inputEmail, "Email required") else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(form.email).matches()) err(inputEmail, "Invalid email")

        val pwdIssues = passwordIssues(form.password)
        if (pwdIssues.isNotEmpty()) err(inputPassword, pwdIssues.joinToString("; "))
        if (form.confirm != form.password) err(inputConfirmPassword, "Passwords differ")
        return ok
    }

    private fun passwordIssues(pw: String): List<String> {
        val issues = mutableListOf<String>()
        if (pw.length < PASSWORD_MIN_LENGTH) issues += "Min $PASSWORD_MIN_LENGTH chars"
        if (!pw.any { it.isDigit() }) issues += "Add a digit"
        if (!pw.any { it.isUpperCase() }) issues += "Add an uppercase letter"
        if (!pw.any { it.isLowerCase() }) issues += "Add a lowercase letter"
        if (!pw.any { !it.isLetterOrDigit() }) issues += "Add a special char"
        return issues
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignup.isEnabled = !loading
        listOf(inputName, inputPhone, inputEmail, inputPassword, inputConfirmPassword).forEach { it.isEnabled = !loading }
    }

    private fun performSignup(form: FormData) {
        // Simple client rate limiting to reduce automated abuse
        val now = System.currentTimeMillis()
        if (now - lastAttemptTs > attemptWindowMs) {
            lastAttemptTs = now
            attemptsInWindow = 0
        }
        if (attemptsInWindow >= maxAttemptsInWindow) {
            showToast("Too many attempts. Please wait a moment.")
            return
        }
        attemptsInWindow++

        if (activeSignupJob?.isActive == true) {
            showToast("Signup already in progress")
            return
        }

        val normalizedEmail = form.email.lowercase(Locale.US)
        val sanitizedPhone = form.phone.replace(Regex("[^0-9+()\\- ]"), "").trim()

        setLoading(true)
        activeSignupJob = lifecycleScope.launch {
            try {
                // Include metadata so display name & phone are stored in user_metadata (auth dashboard shows Display name from full_name or name)
                supabaseClient.auth.signUpWith(Email) {
                    email = normalizedEmail
                    password = form.password
                    data = buildJsonObject {
                        put("name", form.name.take(80))
                        put("full_name", form.name.take(80))
                        put("phone", sanitizedPhone.take(24))
                    }
                }
                // Determine user id from current user or session (may be null if email confirmation required)
                val userId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: supabaseClient.auth.currentSessionOrNull()?.user?.id

                // If session/user present, push metadata again via update (covers cases where signUp metadata ignored until confirmation)
                if (supabaseClient.auth.currentUserOrNull() != null) {
                    updateAuthUserMetadata(form.name, sanitizedPhone)
                    // Also attempt profile sync now that we have a session
                    userId?.let { syncProfile(it, form.copy(email = normalizedEmail, phone = sanitizedPhone)) }
                }

                val message = if (userId == null) {
                    "Signup started. Check your email for confirmation."
                } else {
                    "Signup successful!"
                }
                showToast(message)
                navigateToLogin()
            } catch (t: Throwable) {
                // Avoid leaking internal messages or revealing if email already exists
                val userMessage = when {
                    t.message?.contains("rate", ignoreCase = true) == true -> "Please wait and try again."
                    t.message?.contains("email", ignoreCase = true) == true && t.message?.contains("exists", ignoreCase = true) == true ->
                        "Signup failed. Please check your email for confirmation or try password reset."
                    else -> "Signup failed. Please try again later."
                }
                Log.w(TAG, "Signup error (sanitized): ${t.javaClass.simpleName}")
                showToast(userMessage)
            } finally {
                setLoading(false)
                // Clear sensitive fields after processing to reduce shoulder-surf / memory exposure
                inputPassword.text?.clear()
                inputConfirmPassword.text?.clear()
                // Reset password visibility states and icons
                passwordVisible = false
                confirmPasswordVisible = false
                btnTogglePassword.setImageResource(R.drawable.ic_eye)
                btnToggleConfirmPassword.setImageResource(R.drawable.ic_eye)
                activeSignupJob = null
            }
        }
    }

    private suspend fun updateAuthUserMetadata(name: String, phone: String) {
        runCatching {
            supabaseClient.auth.updateUser {
                data = buildJsonObject {
                    put("name", name.take(80))
                    put("full_name", name.take(80))
                    put("phone", phone.take(24))
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Auth metadata update skipped: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun syncProfile(userId: String, form: FormData) {
        val name = form.name.take(80)
        val phone = form.phone.take(24)
        val email = form.email.take(254)
        val payload = buildMap<String, Any> {
            put("id", userId)
            if (name.isNotBlank()) put("name", name)
            if (phone.isNotBlank()) put("phone", phone)
            if (email.isNotBlank()) put("email", email)
        }
        if (payload.size <= 1) return // only id present
        runCatching {
            supabaseClient.postgrest["profiles"].insert(payload)
        }.onFailure { insertErr ->
            // Fallback: attempt update if row already exists (simulated upsert)
            runCatching {
                val updateMap = payload - "id"
                if (updateMap.isNotEmpty()) {
                    supabaseClient.postgrest["profiles"].update(updateMap) { filter { eq("id", userId) } }
                }
            }.onFailure { updateErr ->
                Log.w(TAG, "Profile sync suppressed: ${updateErr.javaClass.simpleName} (after ${insertErr.javaClass.simpleName})")
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun hardenInputs() {
        // Limit phone input characters proactively (defense-in-depth)
        val allowedPhoneChars = Regex("[0-9+()\\- ]+")
        inputPhone.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            if (source != null && !allowedPhoneChars.matches(source)) "" else source
        })
    }

    private fun setupValidationWatcher() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val form = collectForm()
                btnSignup.isEnabled = validateForm(form, showErrors = false)
                updatePasswordStatus(form.password)
            }
        }
        listOf(inputName, inputPhone, inputEmail, inputPassword, inputConfirmPassword).forEach { it.addTextChangedListener(watcher) }
        btnSignup.isEnabled = false
        updatePasswordStatus("")
    }

    private fun updatePasswordStatus(pw: String) {
        val statusView = tvPasswordStatus ?: return
        if (pw.isEmpty()) {
            statusView.text = ""
            return
        }
        val issues = passwordIssues(pw)
        if (issues.isEmpty()) {
            statusView.text = getString(R.string.password_requirements_all_satisfied)
            statusView.setTextColor(0xFF66CC66.toInt()) // green-ish
        } else {
            statusView.text = issues.joinToString(" • ")
            statusView.setTextColor(0xFFFFCC66.toInt()) // amber warning
        }
    }

    private fun showPasswordRequirementsDialog() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.password_requirements_title))
            .setMessage(getString(R.string.password_requirements_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun togglePasswordVisibility(field: EditText, primary: Boolean) {
        val showing = if (primary) passwordVisible else confirmPasswordVisible
        val newShowing = !showing
        val method = if (newShowing) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
        field.transformationMethod = method
        field.setSelection(field.text?.length ?: 0)
        if (primary) {
            passwordVisible = newShowing
            btnTogglePassword.setImageResource(if (newShowing) R.drawable.ic_eye_off else R.drawable.ic_eye)
            btnTogglePassword.contentDescription = if (newShowing) "Hide password" else "Show password"
        } else {
            confirmPasswordVisible = newShowing
            btnToggleConfirmPassword.setImageResource(if (newShowing) R.drawable.ic_eye_off else R.drawable.ic_eye)
            btnToggleConfirmPassword.contentDescription = if (newShowing) "Hide confirm password" else "Show confirm password"
        }
    }
}
