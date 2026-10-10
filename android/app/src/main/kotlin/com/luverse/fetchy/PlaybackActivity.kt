package com.luverse.fetchy

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Local documents only; Android's decoder and audio focus, no network player or background audio service. */
class PlaybackActivity : Activity(), SurfaceHolder.Callback {
    private var player: MediaPlayer? = null
    private var ready = false
    @Volatile private var videoRendered = false
    private var position = 0
    private var resumePlaying = true
    private var destroyed = false
    private var seeking = false
    private var fileId = ""
    private var focusRequest: AudioFocusRequest? = null
    private var surface: SurfaceView? = null
    private var viewport: FrameLayout? = null
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var time: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(.2f, .2f)
                AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
            }
        }
    }
    private val update = object : Runnable {
        override fun run() {
            val media = player
            if (ready && media != null) {
                val duration = media.duration.coerceAtLeast(1)
                if (!seeking) seek.progress = ((media.currentPosition.toLong() * 1000) / duration).toInt()
                time.text = "${clock(media.currentPosition)} / ${clock(duration)}"
                play.text = if (media.isPlaying) "일시정지" else "재생"
            }
            if (!destroyed) handler.postDelayed(this, 300)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        fileId = intent.getStringExtra("id").orEmpty()
        val record = Store(this).get("files", fileId)
        if (record == null || !record.optString("mime").let { it.startsWith("video/") || it.startsWith("audio/") }) { finish(); return }
        val uri = Uri.parse(record.getString("uri")); if (uri.scheme != "content") { finish(); return }
        position = state?.getInt("position") ?: 0; resumePlaying = state?.getBoolean("playing") ?: true
        window.statusBarColor = Color.rgb(20, 23, 27); window.navigationBarColor = Color.rgb(20, 23, 27)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(20, 23, 27)); setPadding(dp(20), dp(12), dp(20), dp(24)) }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop, dp(20) + insets.systemWindowInsetRight, dp(24) + insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        val header = LinearLayout(this)
        header.addView(actionButton("닫기") { finish() }, LinearLayout.LayoutParams(dp(64), dp(44)))
        header.addView(TextView(this).apply { text = record.optString("title", record.getString("name")); setTextColor(Color.WHITE); textSize = 18f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), dp(8), 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(header)
        status = TextView(this).apply { text = "재생 준비 중…"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
        if (record.optString("mime").startsWith("video/")) {
            viewport = FrameLayout(this).apply {
                surface = SurfaceView(this@PlaybackActivity).apply { holder.addCallback(this@PlaybackActivity) }
                addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
            }
            layout.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageResource(R.drawable.ic_launcher_fetchy) }
            layout.addView(cover, LinearLayout.LayoutParams(-1, 0, 1f))
            Thread {
                val details = runCatching { MediaDetails(this, Store(this)).read(fileId) }.getOrNull()
                val bitmap = details?.optString("previewPath")?.takeIf { it.isNotBlank() }?.let { android.graphics.BitmapFactory.decodeFile(it) }
                handler.post { if (!destroyed && bitmap != null) cover.setImageBitmap(bitmap) }
            }.start()
        }
        layout.addView(status)
        seek = SeekBar(this).apply { max = 1000; contentDescription = "재생 위치"; val blue = android.content.res.ColorStateList.valueOf(Color.rgb(10, 107, 255)); thumbTintList = blue; progressTintList = blue }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) { if (user && ready) position = ((player!!.duration.toLong() * progress) / 1000).toInt() }
            override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) { seeking = false; if (ready) seekToPosition() }
        })
        layout.addView(seek)
        time = TextView(this).apply { setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }; layout.addView(time)
        play = actionButton("재생", primary = true) { if (player?.isPlaying == true) pause() else start() }.apply { isEnabled = false }; layout.addView(play)
        layout.addView(actionButton("시스템 앱에서 열기") { pause(); runCatching { Storage(this@PlaybackActivity, Store(this@PlaybackActivity)).openFile(fileId) }.onFailure { Toast.makeText(this@PlaybackActivity, "파일을 열 앱이 없습니다.", Toast.LENGTH_SHORT).show() } })
        setContentView(layout)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                setDataSource(this@PlaybackActivity, uri)
                surface?.holder?.takeIf { it.surface.isValid }?.let { setDisplay(it) }
                setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoRendered = true; false }
                setOnPreparedListener { ready = true; fitVideo(); play.isEnabled = true; status.text = ""; seekToPosition(); if (resumePlaying) this@PlaybackActivity.start(); handler.post(update) }
                setOnCompletionListener { this@PlaybackActivity.pause(); seek.progress = 1000 }
                setOnErrorListener { _, _, _ -> this@PlaybackActivity.pause(); ready = false; play.isEnabled = false; status.text = "이 기기에서 재생하지 못했습니다. 시스템 앱에서 열어주세요."; true }
                prepareAsync()
            }
        } catch (_: Exception) { status.text = "파일에 접근할 수 없습니다. 저장 위치를 확인하세요." }
    }
    @Suppress("DEPRECATION")
    private fun start() {
        if (!ready) return
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener(focus).build()
            focusRequest = request; audio.requestAudioFocus(request)
        } else audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status.text = "다른 앱이 오디오를 사용 중입니다."; return }
        player?.start(); resumePlaying = true; status.text = ""; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    @Suppress("DEPRECATION")
    private fun pause() {
        if (ready) runCatching { position = player?.currentPosition ?: position; player?.pause() }
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) } else audio.abandonAudioFocus(focus)
        focusRequest = null; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onPause() { resumePlaying = ready && player?.isPlaying == true; pause(); super.onPause() }
    override fun onResume() { super.onResume(); if (ready && resumePlaying) start() }
    override fun onSaveInstanceState(state: Bundle) { state.putInt("position", if (ready) player?.currentPosition ?: position else position); state.putBoolean("playing", ready && player?.isPlaying == true); super.onSaveInstanceState(state) }
    override fun onDestroy() { destroyed = true; pause(); ready = false; handler.removeCallbacksAndMessages(null); player?.release(); player = null; super.onDestroy() }
    override fun surfaceCreated(holder: SurfaceHolder) { player?.setDisplay(holder) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { player?.setDisplay(holder) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { player?.setDisplay(null) }
    private fun fitVideo() {
        val media = player ?: return; val frame = viewport ?: return; val view = surface ?: return
        if (!ready || media.videoWidth <= 0 || media.videoHeight <= 0 || frame.width == 0 || frame.height == 0) return
        val scale = minOf(frame.width.toFloat() / media.videoWidth, frame.height.toFloat() / media.videoHeight)
        val width = (media.videoWidth * scale).toInt(); val height = (media.videoHeight * scale).toInt()
        if (view.layoutParams.width != width || view.layoutParams.height != height) view.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }
    private fun seekToPosition() {
        if (Build.VERSION.SDK_INT >= 26) player?.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
        else player?.seekTo(position)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun actionButton(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(48); setTextColor(if (primary) Color.rgb(20, 23, 27) else Color.WHITE)
        val shape = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (primary) Color.WHITE else Color.rgb(35, 39, 46)) }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22888888), shape, null)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }
    private fun clock(ms: Int) = "${ms / 60000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"
    internal fun playbackState(): String = if (ready) if (player?.isPlaying == true) "playing" else "paused" else "loading"
    internal fun playbackPosition(): Int = if (ready) player?.currentPosition ?: 0 else 0
    internal fun hasRenderedVideo(): Boolean = videoRendered
}
