package com.mmtube.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Small in-app player for downloaded videos.
 *
 * Keeps the last playback position per history id, offers playback speed and
 * picture-in-picture, and saves the position when the user leaves.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var video: VideoView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var title: TextView
    private lateinit var playButton: Button
    private lateinit var speedButton: Button
    private lateinit var pipButton: Button
    private lateinit var controls: View

    private val prefs by lazy { getSharedPreferences("mmtube_player", Context.MODE_PRIVATE) }
    private var mediaId: String = ""
    private var videoWidth = 16
    private var videoHeight = 9
    private var durationMs = 0
    private var dragging = false
    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
    private var speedIndex = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            video.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        video = findViewById(R.id.player_video)
        seek = findViewById(R.id.player_seek)
        time = findViewById(R.id.player_time)
        title = findViewById(R.id.player_title)
        playButton = findViewById(R.id.player_play)
        speedButton = findViewById(R.id.player_speed)
        pipButton = findViewById(R.id.player_pip)
        controls = findViewById(R.id.player_controls)

        val uri = intent.data
        mediaId = intent.getStringExtra(EXTRA_ID).orEmpty()
        title.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        if (uri == null) {
            finish()
            return
        }

        findViewById<Button>(R.id.player_close).setOnClickListener { finish() }
        playButton.setOnClickListener { togglePlay() }
        speedButton.setOnClickListener { cycleSpeed() }
        pipButton.setOnClickListener { enterPip() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            pipButton.visibility = View.GONE
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) {
                    time.text = "${format(progress.toLong())} / ${format(durationMs.toLong())}"
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                dragging = false
                if (durationMs > 0) video.seekTo((durationMs * (bar?.progress ?: 0) / 100).toInt())
            }
        })

        video.setVideoURI(uri)
        video.setOnPreparedListener { player ->
            durationMs = player.duration.coerceAtLeast(0)
            videoWidth = player.videoWidth.takeIf { it > 0 } ?: 16
            videoHeight = player.videoHeight.takeIf { it > 0 } ?: 9
            val saved = if (mediaId.isNotBlank()) prefs.getInt("pos_$mediaId", 0) else 0
            if (saved in 1..(durationMs - 3_000)) {
                video.seekTo(saved)
            }
            player.isLooping = false
            video.start()
            applySpeed()
            updatePlayLabel()
            video.post(ticker)
        }
        video.setOnCompletionListener {
            savePosition(0)
            updatePlayLabel()
        }
        video.setOnErrorListener { _, _, _ ->
            android.widget.Toast.makeText(
                this,
                getString(R.string.player_error),
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            true
        }
    }

    private fun togglePlay() {
        if (video.isPlaying) {
            video.pause()
            savePosition(video.currentPosition)
        } else {
            video.start()
            applySpeed()
        }
        updatePlayLabel()
    }

    private fun updatePlayLabel() {
        playButton.text = getString(if (video.isPlaying) R.string.player_pause else R.string.player_play)
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        applySpeed()
        speedButton.text = String.format(Locale.US, "%.2fx", speeds[speedIndex])
    }

    private fun applySpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!video.isPlaying) return
        runCatching {
            video.setPlaybackParams(video.playbackParams.setSpeed(speeds[speedIndex]))
        }
    }

    private fun updateProgress() {
        if (durationMs <= 0) return
        if (!dragging) {
            val position = video.currentPosition
            seek.progress = ((position.toLong() * 100) / durationMs).toInt().coerceIn(0, 100)
            time.text = "${format(position.toLong())} / ${format(durationMs.toLong())}"
        }
        updatePlayLabel()
    }

    private fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun savePosition(position: Int) {
        if (mediaId.isBlank()) return
        prefs.edit().putInt("pos_$mediaId", position.coerceAtLeast(0)).apply()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val width = videoWidth.coerceAtLeast(1)
        val height = videoHeight.coerceAtLeast(1)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (video.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        savePosition(video.currentPosition)
        if (!isInPictureInPictureMode) video.pause()
        video.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        if (!video.isPlaying && durationMs > 0) video.start()
        video.post(ticker)
    }

    override fun onDestroy() {
        savePosition(video.currentPosition)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "player_id"
        private const val EXTRA_TITLE = "player_title"

        fun start(context: Context, uri: Uri, title: String, id: String) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
