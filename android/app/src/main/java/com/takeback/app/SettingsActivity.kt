package com.takeback.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.takeback.app.databinding.ActivitySettingsBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events

/**
 * SettingsActivity lets the user point the app at a different take-back server.
 * Changing it clears the current session and returns to the login screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverUrl.setText(ApiClient.base)
        binding.resetBtn.setOnClickListener { binding.serverUrl.setText(ApiClient.defaultServer()) }
        binding.saveBtn.setOnClickListener { save() }
    }

    private fun save() {
        val url = binding.serverUrl.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.error.text = "URL must start with http:// or https://"
            return
        }
        if (url == ApiClient.base) {
            finish()
            return
        }
        // Switch servers: drop any live session/socket and restart at login.
        Events.stop()
        ApiClient.setServer(url)
        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
