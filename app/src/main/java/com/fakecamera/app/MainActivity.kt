package com.fakecamera.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.fakecamera.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var imageJob: Job? = null
    private var selectedUri: Uri? = null
    private var isVideo = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        selectedUri = uri
        isVideo = contentResolver.getType(uri)?.startsWith("video/") == true
        prefs.edit {
            putString(KEY_URI, uri.toString())
            putBoolean(KEY_IS_VIDEO, isVideo)
        }
        showPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        setupPlayer()

        binding.btnPickImage.setOnClickListener { pickMedia.launch(arrayOf("image/*")) }
        binding.btnPickVideo.setOnClickListener { pickMedia.launch(arrayOf("video/*")) }
        binding.btnStart.setOnClickListener { openPlayer() }
        binding.btnHelp.setOnClickListener { showHelp() }

        restoreSelection()
    }

    private fun applyInsets() {
        val root = binding.root
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setupPlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
        }
        player = exoPlayer
        binding.previewVideo.player = exoPlayer
    }

    private fun restoreSelection() {
        val saved = prefs.getString(KEY_URI, null)?.let(Uri::parse) ?: return
        selectedUri = saved
        isVideo = prefs.getBoolean(KEY_IS_VIDEO, false)
        showPreview()
    }

    private fun showPreview() {
        val uri = selectedUri
        val exoPlayer = player ?: return

        if (uri == null) {
            binding.emptyState.isVisible = true
            binding.previewImage.isVisible = false
            binding.previewVideo.isVisible = false
            binding.previewLoading.isVisible = false
            binding.fileName.isVisible = false
            binding.btnStart.isEnabled = false
            return
        }

        binding.emptyState.isVisible = false
        binding.btnStart.isEnabled = true
        binding.fileName.text = displayName(uri)
        binding.fileName.isVisible = true

        if (isVideo) {
            imageJob?.cancel()
            binding.previewImage.isVisible = false
            binding.previewLoading.isVisible = false
            binding.previewVideo.isVisible = true
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            binding.previewVideo.isVisible = false
            binding.previewImage.setImageDrawable(null)
            binding.previewImage.isVisible = false
            binding.previewLoading.isVisible = true
            loadPreviewImage(uri)
        }
    }

    private fun loadPreviewImage(uri: Uri) {
        imageJob?.cancel()
        imageJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageLoader.decode(this@MainActivity, uri, PREVIEW_MAX_SIZE)
            }
            if (selectedUri != uri) return@launch
            binding.previewLoading.isVisible = false
            if (bitmap == null) {
                Toast.makeText(this@MainActivity, R.string.error_media, Toast.LENGTH_SHORT).show()
                return@launch
            }
            binding.previewImage.setImageBitmap(bitmap)
            binding.previewImage.isVisible = true
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: getString(R.string.app_name)
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        name = cursor.getString(index) ?: name
                    }
                }
        } catch (_: Exception) {
        }
        return name
    }

    private fun openPlayer() {
        val uri = selectedUri ?: return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URI, uri.toString())
                .putExtra(PlayerActivity.EXTRA_IS_VIDEO, isVideo)
        )
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(R.string.help_ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (isVideo && selectedUri != null && player?.isPlaying == false) {
            player?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        imageJob?.cancel()
        player?.release()
        player = null
    }

    companion object {
        private const val PREFS = "fake_camera"
        private const val KEY_URI = "selected_uri"
        private const val KEY_IS_VIDEO = "is_video"
        private const val PREVIEW_MAX_SIZE = 1600
    }
}
