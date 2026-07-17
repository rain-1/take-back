package com.takeback.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivitySettingsBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events
import kotlinx.coroutines.launch

/**
 * SettingsActivity lets the user point the app at a different take-back server.
 * Changing it clears the current session and returns to the login screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var myNick = "?"
    private var myAvatar = ""

    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadAvatar(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverUrl.setText(ApiClient.base)
        binding.resetBtn.setOnClickListener { binding.serverUrl.setText(ApiClient.defaultServer()) }
        binding.saveBtn.setOnClickListener { save() }
        binding.setAvatarBtn.setOnClickListener { pickAvatar.launch("image/*") }

        lifecycleScope.launch {
            runCatching { ApiClient.me() }.onSuccess { me ->
                myNick = me.nick; myAvatar = me.avatarUrl; renderAvatar()
            }
        }
    }

    private fun renderAvatar() {
        binding.avatarPreview.removeAllViews()
        binding.avatarPreview.addView(Avatars.view(this, myNick, myAvatar, 56))
    }

    private fun uploadAvatar(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val name = (uri.lastPathSegment ?: "avatar").substringAfterLast('/')
                myAvatar = ApiClient.setAvatar(name, bytes)
                renderAvatar()
                android.widget.Toast.makeText(this@SettingsActivity, "Profile picture updated", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@SettingsActivity, e.message ?: "Upload failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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
