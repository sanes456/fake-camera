package com.fakecamera.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.fakecamera.app.databinding.OverlayMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(UnstableApi::class)
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var binding: OverlayMediaBinding
    private var params: WindowManager.LayoutParams? = null
    private var player: ExoPlayer? = null
    private var imageJob: Job? = null
    private var isShowing = false
    private var isVideo = false
    private var isFullscreen = false
    private var fillScreen = true
    private var muted = true
    private var controlsVisible = true
    private var statusBarInset = 0
    private var baseTopMargin = 0

    private var windowX = 0
    private var windowY = 0
    private var windowWidth = 0
    private var windowHeight = 0

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeTouchX = 0f
    private var resizeTouchY = 0f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        baseTopMargin = (8 * resources.displayMetrics.density).toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.takeIf { it.isNotBlank() }?.toUri()
                if (uri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.overlay_permission, Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                isRunning = true
                showOverlay(uri, intent.getBooleanExtra(EXTRA_IS_VIDEO, false))
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        createNotificationChannel()
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_video)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.overlay_stop), stopPendingIntent)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showOverlay(uri: Uri, video: Boolean) {
        if (isShowing) teardownOverlay()

        isFullscreen = false
        controlsVisible = true
        fillScreen = video
        muted = true
        isVideo = video
        val themed = ContextThemeWrapper(this, R.style.Theme_FakeCamera)
        binding = OverlayMediaBinding.inflate(LayoutInflater.from(themed))

        binding.mediaTapCatcher.setOnClickListener { toggleControls() }
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP && isFullscreen
            ) {
                setFullscreen(false)
                true
            } else {
                false
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.mediaControls) { view, insets ->
            statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            applyControlsMargin()
            insets
        }
        binding.btnClose.setOnClickListener { stopSelf() }
        binding.btnMode.setOnClickListener {
            setFullscreen(!isFullscreen)
            setControlsVisible(true)
        }
        binding.btnMute.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            updateMuteButton()
            scheduleHide()
        }
        binding.btnFit.setOnClickListener {
            fillScreen = !fillScreen
            applyScaleMode()
            scheduleHide()
        }
        setupDrag()
        setupResize()

        createLayoutParams()
        try {
            windowManager.addView(binding.root, params)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isShowing = true

        applyScaleMode()
        updateMuteButton()
        updateModeButton()
        applyControlsMargin()
        binding.dragHandle.isVisible = true
        binding.resizeHandle.isVisible = true

        if (isVideo) {
            startVideo(uri)
        } else {
            startImage(uri)
        }

        setControlsVisible(true)
        Toast.makeText(this, R.string.overlay_toast, Toast.LENGTH_LONG).show()
    }

    private fun createLayoutParams() {
        val metrics = resources.displayMetrics
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START

        windowWidth = (metrics.widthPixels * 0.82f).toInt()
        windowHeight = (windowWidth * 9f / 16f).toInt()
            .coerceAtMost((metrics.heightPixels * 0.55f).toInt())
        windowX = (metrics.widthPixels - windowWidth) / 2
        windowY = (metrics.heightPixels - windowHeight) / 3

        layoutParams.width = windowWidth
        layoutParams.height = windowHeight
        layoutParams.x = windowX
        layoutParams.y = windowY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        params = layoutParams
    }

    private fun startVideo(uri: Uri) {
        binding.mediaImage.isVisible = false
        binding.mediaVideo.isVisible = true
        binding.btnMute.isVisible = true

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (muted) 0f else 1f
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@OverlayService, R.string.error_media, Toast.LENGTH_SHORT)
                        .show()
                    stopSelf()
                }
            })
            prepare()
        }
        player = exoPlayer
        binding.mediaVideo.player = exoPlayer
    }

    private fun startImage(uri: Uri) {
        binding.mediaVideo.isVisible = false
        binding.mediaImage.isVisible = true
        binding.btnMute.isVisible = false

        imageJob = scope.launch {
            val maxSize = max(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
            val bitmap = withContext(Dispatchers.IO) {
                ImageLoader.decode(this@OverlayService, uri, maxSize)
            }
            if (!isShowing) return@launch
            if (bitmap == null) {
                Toast.makeText(this@OverlayService, R.string.error_media, Toast.LENGTH_SHORT).show()
                stopSelf()
                return@launch
            }
            binding.mediaImage.setImageBitmap(bitmap)
        }
    }

    private fun applyScaleMode() {
        binding.mediaImage.scaleType =
            if (fillScreen) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        binding.mediaVideo.resizeMode =
            if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.btnFit.setImageResource(
            if (fillScreen) R.drawable.ic_fit_screen else R.drawable.ic_crop
        )
        binding.btnFit.contentDescription =
            getString(if (fillScreen) R.string.fit_screen else R.string.crop_fill)
    }

    private fun updateMuteButton() {
        binding.btnMute.contentDescription =
            getString(if (muted) R.string.sound_on else R.string.sound_off)
        binding.btnMute.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
        )
    }

    private fun updateModeButton() {
        binding.btnMode.setImageResource(
            if (isFullscreen) R.drawable.ic_window else R.drawable.ic_fullscreen
        )
        binding.btnMode.contentDescription =
            getString(if (isFullscreen) R.string.window_mode else R.string.fullscreen_mode)
    }

    private fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen == isFullscreen) return
        val layoutParams = params ?: return

        if (fullscreen) {
            windowX = layoutParams.x
            windowY = layoutParams.y
            windowWidth = layoutParams.width
            windowHeight = layoutParams.height
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.x = 0
            layoutParams.y = 0
            layoutParams.flags = (layoutParams.flags or
                WindowManager.LayoutParams.FLAG_FULLSCREEN) and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            layoutParams.width = windowWidth
            layoutParams.height = windowHeight
            layoutParams.x = windowX
            layoutParams.y = windowY
            layoutParams.flags = (layoutParams.flags and
                WindowManager.LayoutParams.FLAG_FULLSCREEN.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        isFullscreen = fullscreen
        windowManager.updateViewLayout(binding.root, layoutParams)
        binding.root.isFocusable = fullscreen
        binding.root.isFocusableInTouchMode = fullscreen
        if (fullscreen) {
            binding.root.requestFocus()
        } else {
            binding.root.clearFocus()
        }
        applyControlsMargin()
        binding.dragHandle.isVisible = !fullscreen
        binding.resizeHandle.isVisible = !fullscreen && controlsVisible
        updateModeButton()
        binding.root.post { applyImmersive(fullscreen) }
    }

    private fun applyControlsMargin() {
        if (!::binding.isInitialized) return
        val layoutParams = binding.mediaControls.layoutParams as? ViewGroup.MarginLayoutParams
            ?: return
        val topMargin = baseTopMargin + if (isFullscreen) statusBarInset else 0
        if (layoutParams.topMargin != topMargin) {
            layoutParams.topMargin = topMargin
            binding.mediaControls.layoutParams = layoutParams
        }
    }

    private fun applyImmersive(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = binding.root.windowInsetsController ?: return
            if (fullscreen) {
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            binding.root.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                0
            }
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (!isShowing) return
        controlsVisible = visible
        binding.mediaControls.animate().cancel()
        handler.removeCallbacks(hideControlsRunnable)

        if (visible) {
            binding.mediaControls.isVisible = true
            binding.mediaControls.animate().alpha(1f).setDuration(160).start()
            binding.resizeHandle.isVisible = !isFullscreen
            scheduleHide()
        } else {
            binding.mediaControls.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction {
                    if (!controlsVisible) binding.mediaControls.isVisible = false
                }
                .start()
            binding.resizeHandle.isVisible = false
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) {
            handler.postDelayed(hideControlsRunnable, 4000)
        }
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setupDrag() {
        binding.dragHandle.setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = layoutParams.x
                    dragStartY = layoutParams.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    handler.removeCallbacks(hideControlsRunnable)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    val maxX = max(0, metrics.widthPixels - layoutParams.width)
                    val maxY = max(0, metrics.heightPixels - layoutParams.height)
                    layoutParams.x = (dragStartX + (event.rawX - dragTouchX).toInt())
                        .coerceIn(0, maxX)
                    layoutParams.y = (dragStartY + (event.rawY - dragTouchY).toInt())
                        .coerceIn(0, maxY)
                    windowManager.updateViewLayout(binding.root, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleHide()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupResize() {
        binding.resizeHandle.setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartWidth = layoutParams.width
                    resizeStartHeight = layoutParams.height
                    resizeTouchX = event.rawX
                    resizeTouchY = event.rawY
                    handler.removeCallbacks(hideControlsRunnable)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    val minWidth = (140 * metrics.density).toInt()
                    val minHeight = (90 * metrics.density).toInt()
                    val maxWidth = max(minWidth, metrics.widthPixels - layoutParams.x)
                    val maxHeight = max(minHeight, metrics.heightPixels - layoutParams.y)
                    layoutParams.width = (resizeStartWidth + (event.rawX - resizeTouchX).toInt())
                        .coerceIn(minWidth, maxWidth)
                    layoutParams.height = (resizeStartHeight + (event.rawY - resizeTouchY).toInt())
                        .coerceIn(minHeight, maxHeight)
                    windowManager.updateViewLayout(binding.root, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleHide()
                    true
                }

                else -> false
            }
        }
    }

    private fun teardownOverlay() {
        handler.removeCallbacks(hideControlsRunnable)
        imageJob?.cancel()
        imageJob = null

        if (::binding.isInitialized) {
            binding.mediaImage.setImageDrawable(null)
            binding.mediaVideo.player = null
        }
        player?.release()
        player = null

        if (isShowing && ::binding.isInitialized) {
            try {
                windowManager.removeView(binding.root)
            } catch (_: Exception) {
            }
        }
        isShowing = false
        params = null
    }

    override fun onDestroy() {
        isRunning = false
        teardownOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "fake_camera_overlay"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.fakecamera.app.action.START_OVERLAY"
        private const val ACTION_STOP = "com.fakecamera.app.action.STOP_OVERLAY"
        private const val EXTRA_URI = "overlay_uri"
        private const val EXTRA_IS_VIDEO = "overlay_is_video"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, uri: Uri, isVideo: Boolean) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_IS_VIDEO, isVideo)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
