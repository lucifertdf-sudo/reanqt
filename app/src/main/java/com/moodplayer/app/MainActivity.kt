package com.moodplayer.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private var tab = "HOME"
    private val queue = mutableListOf<Uri>()
    private val names = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("mood_stats", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Color.rgb(7, 12, 22)
    private val panel = Color.rgb(16, 25, 42)
    private val blue = Color.rgb(57, 145, 255)
    private val muted = Color.rgb(145, 164, 190)
    private lateinit var body: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var playButton: Button
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView

    private val timer = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                if (p.duration > 0) {
                    seek.max = p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    seek.progress = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    elapsed.text = time(p.currentPosition)
                    duration.text = time(p.duration)
                }
                playButton.text = if (p.isPlaying) "Ⅱ  PAUSE" else "▶  PLAY"
                if (p.isPlaying) prefs.edit().putLong("seconds", prefs.getLong("seconds", 0L) + 1L).apply()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                ?: data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) openTracks(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        player = ExoPlayer.Builder(this).build()
        buildLayout()
        drawTab()
        handler.post(timer)
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 14f, color: Int = Color.WHITE) =
        TextView(this).apply { text = value; textSize = size; setTextColor(color) }
    private fun button(value: String, primary: Boolean = false, click: () -> Unit) =
        Button(this).apply { text = value; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(if (primary) blue else panel); setOnClickListener { click() } }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(d(15), d(15), d(15), d(15)) }

    private fun buildLayout() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(d(18), d(12), d(18), d(7)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("◖  MOOD PLAYER", 17f, blue).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = .16f }, LinearLayout.LayoutParams(0, d(46), 1f))
        top.addView(text("v1.1", 11f, muted))
        root.addView(top)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(5), 0, d(14)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        buildNav(root)
    }

    private fun buildNav(root: LinearLayout) {
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setBackgroundColor(panel); setPadding(d(2), d(5), d(2), d(5)) }
        listOf("HOME" to "⌂", "LIBRARY" to "♫", "EQ" to "≋", "WRAPPED" to "◷", "MORE" to "⋯").forEach { item ->
            nav.addView(TextView(this).apply {
                text = item.second + "\n" + item.first
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(if (tab == item.first) blue else muted)
                setPadding(d(2), d(8), d(2), d(8))
                setOnClickListener {
                    tab = item.first
                    drawTab()
                    root.removeViewAt(root.childCount - 1)
                    buildNav(root)
                }
            }, LinearLayout.LayoutParams(0, d(48), 1f))
        }
        root.addView(nav)
    }

    private fun drawTab() {
        body.removeAllViews()
        when (tab) {
            "HOME" -> home()
            "LIBRARY" -> library()
            "EQ" -> eqScreen()
            "WRAPPED" -> wrapped()
            else -> settings()
        }
    }

    private fun pageTitle(title: String, sub: String) {
        body.addView(text(title, 27f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, d(12), 0, d(4)) })
        body.addView(text(sub, 12f, muted).apply { setPadding(0, 0, 0, d(16)) })
    }

    private fun home() {
        pageTitle("Your music.\nYour way.", "LOCAL AUDIO  •  BUILT FOR LISTENING")
        val art = card().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 29, 48))
            addView(text("M", 90f, blue).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
            addView(text("MOOD PLAYER", 10f, muted).apply { letterSpacing = .2f })
        }
        body.addView(art, LinearLayout.LayoutParams(-1, d(175)).apply { bottomMargin = d(17) })
        body.addView(text("NOW PLAYING", 10f, blue))
        titleView = text("Nothing playing", 21f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, d(6), 0, d(4)) }
        subView = text("Choose local audio to begin", 13f, muted)
        body.addView(titleView)
        body.addView(subView)
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(blue)
            thumbTintList = android.content.res.ColorStateList.valueOf(blue)
        }
        body.addView(seek, LinearLayout.LayoutParams(-1, d(35)).apply { topMargin = d(9) })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) elapsed.text = time(v.toLong()) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { player?.seekTo(s?.progress?.toLong() ?: 0L) }
        })
        val times = LinearLayout(this)
        elapsed = text("0:00", 11f, muted)
        duration = text("0:00", 11f, muted).apply { gravity = Gravity.END }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(duration, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(times)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, d(16), 0, d(10)) }
        controls.addView(button("PREV") { player?.seekToPreviousMediaItem() })
        playButton = button("▶  PLAY", true) { toggle() }
        controls.addView(playButton, LinearLayout.LayoutParams(0, d(49), 1f).apply { leftMargin = d(7); rightMargin = d(7) })
        controls.addView(button("NEXT") { player?.seekToNextMediaItem() })
        body.addView(controls)
        body.addView(button("＋  OPEN MUSIC FILES", true) { chooseFiles() }, LinearLayout.LayoutParams(-1, d(51)))
        val info = card().apply {
            addView(text("AUDIO PATH", 10f, blue))
            addView(text("Android shared audio route", 14f).apply { setPadding(0, d(5), 0, d(3)) })
            addView(text("USB bit-perfect is not verified in this build.", 11f, muted))
        }
        body.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(17) })
        refreshTrack()
    }

    private fun library() {
        pageTitle("Library", "Local tracks and playback queue")
        body.addView(button("＋  ADD AUDIO FILES", true) { chooseFiles() }, LinearLayout.LayoutParams(-1, d(50)))
        val search = EditText(this).apply { hint = "Search queue"; setHintTextColor(muted); setTextColor(Color.WHITE); setSingleLine(); setBackgroundColor(panel); setPadding(d(12), d(8), d(12), d(8)) }
        body.addView(search, LinearLayout.LayoutParams(-1, d(48)).apply { topMargin = d(10); bottomMargin = d(10) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun fill(filter: String) {
            list.removeAllViews()
            val matches = names.indices.filter { names[it].contains(filter, true) }
            if (matches.isEmpty()) list.addView(text(if (names.isEmpty()) "Library is empty. Add FLAC, WAV, MP3 or M4A files." else "No matches.", 13f, muted).apply { setPadding(d(8), d(22), 0, d(22)) })
            matches.forEach { index ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(panel); setPadding(d(12), d(14), d(12), d(14)); setOnClickListener { player?.seekTo(index, 0); player?.play() } }
                row.addView(text("♫", 20f, blue), LinearLayout.LayoutParams(d(33), -2))
                row.addView(text(names[index], 13f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("▶", 12f, muted))
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(5) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { fill(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        fill("")
    }

    private fun eqScreen() {
        pageTitle("Equalizer", "5-band Android audio effect • device support varies")
        val box = card()
        box.addView(text("GRAPHIC EQUALIZER", 10f, blue))
        box.addView(text("Adjust the tonal balance during playback.", 12f, muted).apply { setPadding(0, d(6), 0, d(12)) })
        val eq = getEq()
        if (eq == null) {
            box.addView(text("Start playback, then reopen EQ. The audio session is not ready.", 13f, muted))
        } else {
            val limits = eq.bandLevelRange
            val lo = limits[0].toInt()
            val hi = limits[1].toInt()
            for (i in 0 until eq.numberOfBands.toInt().coerceAtMost(5)) {
                val b = i.toShort()
                val f = eq.getCenterFreq(b) / 1000
                val hz = if (f >= 1000) String.format(Locale.US, "%.1f kHz", f / 1000f) else f.toString() + " Hz"
                box.addView(text(listOf("LOW", "LOW MID", "MID", "HIGH MID", "HIGH")[i] + "  •  " + hz, 12f).apply { setPadding(0, d(8), 0, 0) })
                box.addView(SeekBar(this).apply {
                    max = hi - lo
                    progress = (eq.getBandLevel(b).toInt() - lo).coerceIn(0, max)
                    progressTintList = android.content.res.ColorStateList.valueOf(blue)
                    thumbTintList = android.content.res.ColorStateList.valueOf(blue)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) try { eq.setBandLevel(b, (v + lo).toShort()) } catch (_: Exception) {} }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                })
            }
            box.addView(button("RESET FLAT") {
                try { for (i in 0 until eq.numberOfBands.toInt()) eq.setBandLevel(i.toShort(), 0); drawTab() } catch (_: Exception) {}
            })
        }
        box.addView(text("This release has a 5-band graphic EQ, not parametric EQ with adjustable Q and bandwidth.", 11f, muted).apply { setPadding(0, d(12), 0, 0) })
        body.addView(box)
    }

    private fun getEq(): Equalizer? {
        if (equalizer != null) return equalizer
        val session = player?.audioSessionId ?: 0
        if (session <= 0) return null
        return try { Equalizer(0, session).also { it.enabled = true; equalizer = it } } catch (_: Exception) { null }
    }

    private fun wrapped() {
        pageTitle("Your Wrapped", "A private listening recap on this device")
        val plays = prefs.getInt("plays", 0)
        val secs = prefs.getLong("seconds", 0L)
        val best = prefs.getString("favorite", "Not enough data yet") ?: "Not enough data yet"
        listOf(
            Triple("TRACK SESSIONS", plays.toString(), "Times you started a queue"),
            Triple("LISTENING TIME", (secs / 60).toString() + " min", "Approximate time while playing"),
            Triple("LAST TRACK", prefs.getString("last", "Nothing played yet") ?: "Nothing played yet", "Most recent selection")
        ).forEach { item ->
            val c = card()
            c.addView(text(item.first, 10f, blue))
            c.addView(text(item.second, 22f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, d(5), 0, d(3)) })
            c.addView(text(item.third, 11f, muted))
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(9) })
        }
        body.addView(text("Stats are stored locally. Collection starts in version 1.1; no account or cloud sync.", 11f, muted))
    }

    private fun settings() {
        pageTitle("Settings", "Playback and technical information")
        val c = card()
        c.addView(text("OUTPUT", 10f, blue))
        c.addView(text("Android shared audio route", 16f).apply { setPadding(0, d(5), 0, d(4)) })
        c.addView(text("The system mixer can resample audio. Exclusive USB DAC output is not implemented.", 12f, muted))
        c.addView(text("BIT-PERFECT", 10f, blue).apply { setPadding(0, d(18), 0, d(5)) })
        c.addView(text("Not available / not verified", 15f))
        c.addView(text("Do not assume bit-perfect output. This build has no exclusive USB driver.", 11f, muted))
        c.addView(text("DSP", 10f, blue).apply { setPadding(0, d(18), 0, d(5)) })
        c.addView(text("Turn off EQ and system sound effects for an unprocessed path.", 12f, muted))
        c.addView(text("ABOUT", 10f, blue).apply { setPadding(0, d(18), 0, d(5)) })
        c.addView(text("Mood Player 1.1  •  Android 8.0+", 13f))
        body.addView(c)
    }

    private fun chooseFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        picker.launch(intent)
    }

    private fun openTracks(uris: List<Uri>) {
        val p = player ?: return
        try {
            queue.clear()
            queue.addAll(uris)
            names.clear()
            val items = uris.map { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val name = fileName(uri)
                names.add(name)
                MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build()
            }
            p.setMediaItems(items)
            p.prepare()
            p.play()
            prefs.edit().putInt("plays", prefs.getInt("plays", 0) + 1).putString("last", names.firstOrNull() ?: "").apply()
            if (tab == "LIBRARY") drawTab() else refreshTrack()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open audio: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }

    private fun fileName(uri: Uri): String {
        var value = uri.lastPathSegment ?: "Unknown track"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) value = it.getString(0) ?: value
            }
        } catch (_: Exception) {}
        return value.substringBeforeLast('.', value)
    }

    private fun refreshTrack() {
        if (!::titleView.isInitialized) return
        val i = player?.currentMediaItemIndex ?: -1
        titleView.text = names.getOrNull(i) ?: "Nothing playing"
        subView.text = if (names.isEmpty()) "Choose local audio to begin" else "Local audio  •  " + names.size + " tracks in queue"
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        playButton.text = if (p.isPlaying) "Ⅱ  PAUSE" else "▶  PLAY"
    }

    private fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
