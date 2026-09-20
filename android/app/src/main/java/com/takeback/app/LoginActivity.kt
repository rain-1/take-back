package com.takeback.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivityLoginBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.ApiException
import com.takeback.app.net.Events
import kotlinx.coroutines.launch

/**
 * LoginActivity is the entry point: it resumes an existing session if the
 * persisted cookie is still valid, otherwise shows a login/register form.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var registerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Try to resume a saved session before showing the form.
        lifecycleScope.launch {
            try {
                ApiClient.me()
                goHome()
            } catch (_: Exception) { /* not logged in; show form */ }
        }

        binding.toggle.setOnClickListener { toggleMode() }
        binding.submit.setOnClickListener { submit() }
        binding.settingsLink.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.serverLabel.text = "${ApiClient.base} · app v${BuildConfig.VERSION_NAME}"
        checkServerCompatibility()
    }

    /**
     * Warn if the server speaks a different wire protocol than this build —
     * MAJOR/PROTOCOL mismatch means the app must be updated (see
     * internal/version). Best-effort: silent when offline.
     */
    private fun checkServerCompatibility() {
        lifecycleScope.launch {
            val v = runCatching { ApiClient.serverVersion() }.getOrNull() ?: return@launch
            binding.serverLabel.text =
                "${ApiClient.base} · app v${BuildConfig.VERSION_NAME} · server v${v.version}"
            applyRegistrationPolicy(v.openRegistration)
            if (!v.compatible) {
                AlertDialog.Builder(this@LoginActivity)
                    .setTitle("Update required")
                    .setMessage(
                        "This app speaks protocol ${BuildConfig.PROTOCOL} but the server " +
                            "(v${v.version}) speaks protocol ${v.protocol}.\n\n" +
                            "Please install a newer take-back app."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * With signups closed on the server, don't offer a Register option that will
     * only be refused after someone has filled the form in — say so instead.
     * Matches the web login page.
     */
    private fun applyRegistrationPolicy(open: Boolean?) {
        if (open != false) {
            if (!binding.toggle.isEnabled) {
                binding.toggle.isEnabled = true
                binding.toggle.setText(if (registerMode) R.string.have_account else R.string.need_account)
                binding.toggle.setOnClickListener { toggleMode() }
            }
            return
        }
        if (registerMode) toggleMode() // back to the login form
        binding.toggle.isEnabled = false
        binding.toggle.setOnClickListener(null)
        binding.toggle.setText(R.string.registration_closed)
    }

    private fun toggleMode() {
        registerMode = !registerMode
        binding.authTitle.setText(if (registerMode) R.string.create_account else R.string.log_in)
        binding.submit.setText(if (registerMode) R.string.register else R.string.log_in)
        binding.toggle.setText(if (registerMode) R.string.have_account else R.string.need_account)
    }

    private fun submit() {
        binding.error.text = ""
        val nick = binding.nick.text.toString().trim()
        val password = binding.password.text.toString()
        binding.submit.isEnabled = false
        lifecycleScope.launch {
            try {
                if (registerMode) ApiClient.register(nick, password) else ApiClient.login(nick, password)
                goHome()
            } catch (e: ApiException) {
                binding.error.text = e.message
            } catch (e: Exception) {
                binding.error.text = "Network error: ${e.message}"
            } finally {
                binding.submit.isEnabled = true
            }
        }
    }

    private fun goHome() {
        Events.start(applicationContext)
        ConnectionService.start(this) // keep listening after you close the app
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
