package com.moodplayer.app

import androidx.activity.ComponentActivity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var statusView: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var playButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.duration > 0) {
                seek.max = p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                seek.progress = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                elapsed.text = time(p.currentPosition)
                duration.text = time(p.duration)
                playButton.text = if (p.isPlaying) "PAUSE" else "PLAY"
            }
            handler.postDelayed(this, 500)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } ?: data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) playFiles(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 12, 22)
        window.navigationBarColor = Color.rgb(7, 12, 22)
        window.decorView.systemUiVisibility = 0
        player = ExoPlayer.Builder(this).build()
        buildUi()
        handler.post(tick)
    }

    private fun buildUi() {
        val bg = Color.rgb(7, 12, 22)
        val panel = Color.rgb(15, 24, 40)
        val blue = Color.rgb(57, 145, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24)
            setBackgroundColor(bg)
        }
        val brand = TextView(this).apply {
            text = "MOOD PLAYER"
            textSize = 13f
            letterSpacing = .22f
            setTextColor(blue)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        root.addView(brand)
        val heading = TextView(this).apply {
            text = "Your music.\nYour way."
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 28, 0, 24)
        }
        root.addView(heading)
        val art = TextView(this).apply {
            text = "M"
            textSize = 92f
            gravity = Gravity.CENTER
            setTextColor(blue)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(-1, 230).apply { bottomMargin = 24 }
        }
        root.addView(art)
        titleView = TextView(this).apply {
            text = "Nothing playing"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)
        artistView = TextView(this).apply {
            text = "Choose local audio to begin"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 6, 0, 18)
        }
        root.addView(artistView)
        seek = SeekBar(this).apply { progressTintList = android.content.res.ColorStateList.valueOf(blue); thumbTintList = android.content.res.ColorStateList.valueOf(blue) }
        root.addView(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) elapsed.text = time(progress.toLong()) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { player?.seekTo(s?.progress?.toLong() ?: 0L) }
        })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = TextView(this).apply { text = "0:00"; setTextColor(Color.LTGRAY) }
        duration = TextView(this).apply { text = "0:00"; gravity = Gravity.END; setTextColor(Color.LTGRAY) }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(duration, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(times)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 24, 0, 24) }
        val prev = Button(this).apply { text = "PREV"; setTextColor(Color.WHITE); setBackgroundColor(panel); setOnClickListener { player?.seekToPreviousMediaItem() } }
        playButton = Button(this).apply { text = "PLAY"; setTextColor(Color.WHITE); setBackgroundColor(blue); setOnClickListener { togglePlayback() } }
        val next = Button(this).apply { text = "NEXT"; setTextColor(Color.WHITE); setBackgroundColor(panel); setOnClickListener { player?.seekToNextMediaItem() } }
        controls.addView(prev)
        controls.addView(playButton)
        controls.addView(next)
        root.addView(controls)
        val open = Button(this).apply {
            text = "＋  OPEN MUSIC FILES"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener { openPicker() }
        }
        root.addView(open, LinearLayout.LayoutParams(-1, 56))
        statusView = TextView(this).apply {
            text = "LOCAL LIBRARY  •  FLAC / WAV / MP3 / M4A"
            textSize = 11f
            setTextColor(Color.rgb(140, 160, 185))
            setPadding(0, 22, 0, 0)
        }
        root.addView(statusView)
        val note = TextView(this).apply {
            text = "USB DAC output follows Android audio routing. Bit-perfect output is not yet verified; disable system DSP/effects for the cleanest path."
            textSize = 11f
            setTextColor(Color.rgb(140, 160, 185))
            setPadding(0, 12, 0, 0)
        }
        root.addView(note)
        setContentView(root)
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        picker.launch(intent)
    }

    private fun playFiles(uris: List<Uri>) {
        val p = player ?: return
        try {
            val items = uris.map { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val name = queryName(uri)
                MediaItem.Builder().setUri(uri).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(name).build()
                ).build()
            }
            p.setMediaItems(items)
            p.prepare()
            p.play()
            titleView.text = queryName(uris.first())
            artistView.text = if (uris.size > 1) "${uris.size} tracks in queue" else "Local audio"
            statusView.text = "PLAYING LOCAL FILE  •  ANDROID AUDIO ROUTE"
            playButton.text = "PAUSE"
        } catch (e: Exception) {
            statusView.text = "Could not open file: ${e.message ?: "unknown error"}"
        }
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Unknown track"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (_: Exception) {}
        return name.substringBeforeLast('.', name)
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        playButton.text = if (p.isPlaying) "PAUSE" else "PLAY"
    }

    private fun time(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        super.onDestroy()
    }
}
