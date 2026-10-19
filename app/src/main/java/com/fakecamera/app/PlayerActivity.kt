package com.fakecamera.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.fakecamera.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var imageJob: Job? = null
    private var isVideo = false
    private var fillScreen = true
    private var muted = true
    private var controlsVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControlsVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        val uri = intent.getStringExtra(EXTRA_URI)?.toUri()
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        if (uri == null) {
            Toast.makeText(this, R.string.error_media, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tapCatcher.setOnClickListener { toggleControls() }
        binding.btnClose.setOnClickListener { finish() }
        binding.btnFit.setOnClickListener {
            fillScreen = !fillScreen
            applyScaleMode()
            scheduleHide()
        }
        binding.btnMute.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            updateMuteButton()
            scheduleHide()
        }

        if (isVideo) {
            setupVideo(uri)
        } else {
            setupImage(uri)
        }

        applyScaleMode()
        setControlsVisible(true)
    }

    private fun setupImage(uri: Uri) {
        binding.videoView.isVisible = false
        binding.imageView.isVisible = true
        binding.btnMute.isVisible = false

        imageJob = lifecycleScope.launch {
            val maxSize = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            val bitmap = withContext(Dispatchers.IO) {
                ImageLoader.decode(this@PlayerActivity, uri, maxSize)
            }
            if (bitmap == null) {
                Toast.makeText(this@PlayerActivity, R.string.error_media, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            binding.imageView.setImageBitmap(bitmap)
        }
    }

    private fun setupVideo(uri: Uri) {
        binding.imageView.isVisible = false
        binding.videoView.isVisible = true
        binding.btnMute.isVisible = true

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (muted) 0f else 1f
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, R.string.error_media, Toast.LENGTH_SHORT).show()
                }
            })
            prepare()
        }
        player = exoPlayer
        binding.videoView.player = exoPlayer
        updateMuteButton()
    }

    private fun applyScaleMode() {
        binding.imageView.scaleType =
            if (fillScreen) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        binding.videoView.resizeMode =
            if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.btnFit.text = getString(if (fillScreen) R.string.fit_screen else R.string.crop_fill)
    }

    private fun updateMuteButton() {
        binding.btnMute.text = getString(if (muted) R.string.sound_on else R.string.sound_off)
        binding.btnMute.setIconResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
        )
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.controls.animate().cancel()
        handler.removeCallbacks(hideControls)

        if (visible) {
            binding.controls.isVisible = true
            binding.controls.animate().alpha(1f).setDuration(180).start()
            scheduleHide()
        } else {
            binding.controls.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction { binding.controls.isVisible = false }
                .start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (controlsVisible) {
            handler.postDelayed(hideControls, 4000)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(hideControls)
        imageJob?.cancel()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }
}
