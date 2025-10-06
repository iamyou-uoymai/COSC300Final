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

/**
 * Signup screen with Supabase email/password registration + optional profile insert.
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

    private val supabaseClient get() = SupabaseProvider.client

    // Precompiled phone regex
    private val PHONE_REGEX = Regex("^[0-9+\\s()\\-]{6,20}$")

    companion object { private const val TAG = "SignupActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        bindViews()
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
    }

    private fun setupClicks() {
        linkToLogin.setOnClickListener { navigateToLogin() }
        btnSignup.setOnClickListener {
            val form = collectForm()
            if (validateForm(form, showErrors = true)) {
                performSignup(form)
            }
        }
    }

    private fun setupValidationWatcher() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnSignup.isEnabled = validateForm(collectForm(), showErrors = false)
            }
        }
        listOf(inputName, inputPhone, inputEmail, inputPassword, inputConfirmPassword).forEach { it.addTextChangedListener(watcher) }
        btnSignup.isEnabled = false
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
        if (form.password.length < 6) err(inputPassword, "Min 6 chars")
        if (form.confirm != form.password) err(inputConfirmPassword, "Passwords differ")
        return ok
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignup.isEnabled = !loading
        listOf(inputName, inputPhone, inputEmail, inputPassword, inputConfirmPassword).forEach { it.isEnabled = !loading }
    }

    private fun performSignup(form: FormData) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val signupResult = supabaseClient.auth.signUpWith(Email) {
                    email = form.email
                    password = form.password
                }
                // Determine userId (may be null if email confirmation pending)
                val userId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: supabaseClient.auth.currentSessionOrNull()?.user?.id

                // Attempt profile insert only if we have a user id (ignore failure gracefully)
                if (userId != null) {
                    safeProfileInsert(userId, form)
                }

                val message = if (userId == null) {
                    "Signup initiated. Check your email to confirm."
                } else {
                    "Signup successful!"
                }
                showToast(message)
                navigateToLogin()
            } catch (t: Throwable) {
                // If the only failure is profile insert (e.g. missing table), user can still be created.
                showToast("Signup failed: ${t.message ?: "Unknown error"}")
                Log.w(TAG, "Signup error", t)
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun safeProfileInsert(userId: String, form: FormData) {
        runCatching {
            supabaseClient.postgrest["profiles"].insert(
                mapOf(
                    "id" to userId,
                    "name" to form.name,
                    "phone" to form.phone,
                    "email" to form.email
                )
            )
        }.onFailure { e ->
            // Do not propagate table/schema issues; just log.
            Log.w(TAG, "Profile insert skipped: ${e.message}")
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
