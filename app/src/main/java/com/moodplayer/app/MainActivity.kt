package com.moodplayer.app

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var tab = "HOME"
    private val queue = mutableListOf<Uri>()
    private val names = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("mood_stats", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(8, 11, 18)
    private val surface = Color.rgb(19, 23, 33)
    private val surface2 = Color.rgb(27, 33, 46)
    private val blue = Color.rgb(65, 145, 255)
    private val muted = Color.rgb(157, 165, 181)
    private val white = Color.rgb(246, 247, 250)

    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var playButton: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView

    private val timer = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && tab == "HOME" && ::seek.isInitialized) {
                if (p.duration > 0) {
                    seek.max = p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    seek.progress = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    elapsed.text = time(p.currentPosition)
                    duration.text = time(p.duration)
                }
                playButton.text = if (p.isPlaying) "Ⅱ" else "▶"
                if (p.isPlaying) prefs.edit().putLong("seconds", prefs.getLong("seconds", 0L) + 1L).apply()
                refreshTrack()
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

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanDeviceMedia()
        else Toast.makeText(this, "Izin audio diperlukan untuk memindai musik perangkat.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        player = ExoPlayer.Builder(this).build()
        buildLayout()
        drawTab()
        ensureAudioPermission()
        handler.post(timer)
    }

    private fun ensureAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                         else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) scanDeviceMedia()
        else audioPermission.launch(permission)
    }

    private fun scanDeviceMedia() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                         else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(permission)
            return
        }
        Thread {
            val foundUris = mutableListOf<Uri>()
            val foundNames = mutableListOf<String>()
            val mediaUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
            var scanError: String? = null
            try {
                contentResolver.query(mediaUri, projection, MediaStore.Audio.Media.DURATION + " > 0", null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val title = cursor.getString(titleColumn)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unknown track"
                        val artist = cursor.getString(artistColumn)?.trim().takeUnless { it.isNullOrEmpty() || it == "<unknown>" }
                        foundUris.add(ContentUris.withAppendedId(mediaUri, id))
                        foundNames.add(if (artist == null) title else title + " — " + artist)
                    }
                }
            } catch (e: Exception) {
                scanError = e.message ?: "Media scan failed"
            }
            runOnUiThread {
                if (scanError != null) {
                    Toast.makeText(this, "Scan gagal: " + scanError, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                queue.clear()
                queue.addAll(foundUris)
                names.clear()
                names.addAll(foundNames)
                val items = foundUris.mapIndexed { index, uri ->
                    MediaItem.Builder().setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(foundNames[index]).build()).build()
                }
                player?.setMediaItems(items)
                player?.prepare()
                drawTab()
                Toast.makeText(this, foundUris.size.toString() + " lagu ditemukan.", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun d(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun rounded(color: Int, radius: Int = 18): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = d(radius).toFloat()
        }
    }

    private fun surfaceCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20)
            setPadding(d(16), d(16), d(16), d(16))
        }
    }

    private fun action(label: String, primary: Boolean = false, click: () -> Unit): TextView {
        return text(label, 14f, if (primary) Color.WHITE else white, true).apply {
            gravity = Gravity.CENTER
            minHeight = d(48)
            setPadding(d(14), d(10), d(14), d(10))
            background = rounded(if (primary) blue else surface2, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(d(20), d(8), d(20), d(18))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(body, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(15, 19, 28))
            setPadding(d(7), d(7), d(7), d(7))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, d(66)))
        setContentView(root)
        rebuildNav()
    }

    private fun rebuildNav() {
        if (!::nav.isInitialized) return
        nav.removeAllViews()
        val items = listOf(
            Triple("HOME", "⌂", "HOME"),
            Triple("LIBRARY", "♫", "LIBRARY"),
            Triple("EQ", "≋", "EQ"),
            Triple("WRAPPED", "◷", "WRAPPED"),
            Triple("MORE", "•••", "MORE")
        )
        items.forEach { item ->
            val selected = tab == item.first
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (selected) rounded(Color.rgb(27, 43, 67), 14) else rounded(Color.TRANSPARENT, 14)
                setPadding(d(2), d(5), d(2), d(5))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    tab = item.first
                    drawTab()
                }
            }
            itemView.addView(text(item.second, if (item.first == "MORE") 15f else 19f, if (selected) blue else muted, true).apply {
                gravity = Gravity.CENTER
            })
            itemView.addView(text(item.third, 9f, if (selected) blue else muted, selected).apply {
                gravity = Gravity.CENTER
                setPadding(0, d(3), 0, 0)
            })
            nav.addView(itemView, LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = d(2)
                rightMargin = d(2)
            })
        }
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
        rebuildNav()
    }

    private fun pageHeader(title: String, subtitle: String) {
        body.addView(text(title, 30f, white, true).apply {
            setPadding(0, d(17), 0, d(6))
        })
        body.addView(text(subtitle, 13f, muted).apply {
            setPadding(0, 0, 0, d(17))
        })
    }

    private fun home() {
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(text("◖", 23f, blue, true))
        brand.addView(text("MOOD PLAYER", 13f, white, true).apply {
            letterSpacing = .18f
            setPadding(d(7), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(text("1.2.0", 11f, muted))
        body.addView(brand, LinearLayout.LayoutParams(-1, d(43)))

        body.addView(text("Listen Now", 31f, white, true).apply {
            setPadding(0, d(18), 0, d(4))
        })
        body.addView(text("Your music, in your own space.", 14f, muted).apply {
            setPadding(0, 0, 0, d(13))
        })

        val artwork = MoodArtworkView()
        body.addView(artwork, LinearLayout.LayoutParams(-1, d(280)).apply {
            bottomMargin = d(17)
        })

        body.addView(text("NOW PLAYING", 10f, blue, true).apply { letterSpacing = .15f })
        titleView = text("Nothing playing", 20f, white, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, d(7), 0, d(5))
        }
        subView = text("Choose a track from your library", 13f, muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        body.addView(titleView)
        body.addView(subView)

        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(blue)
            thumbTintList = android.content.res.ColorStateList.valueOf(blue)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 70, 82))
            setPadding(0, 0, 0, 0)
        }
        body.addView(seek, LinearLayout.LayoutParams(-1, d(32)).apply {
            topMargin = d(9)
        })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser && ::elapsed.isInitialized) elapsed.text = time(v.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { player?.seekTo(s?.progress?.toLong() ?: 0L) }
        })

        val times = LinearLayout(this)
        elapsed = text("0:00", 11f, muted)
        duration = text("0:00", 11f, muted).apply { gravity = Gravity.END }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(duration, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(times)

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(14), 0, d(16))
        }
        val prev = text("Ⅰ◀", 21f, white, true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { player?.seekToPreviousMediaItem() }
        }
        playButton = text("▶", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(blue, 28)
            isClickable = true
            setOnClickListener { toggle() }
        }
        val next = text("▶Ⅰ", 21f, white, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { player?.seekToNextMediaItem() }
        }
        controls.addView(prev, LinearLayout.LayoutParams(0, d(54), 1f))
        controls.addView(playButton, LinearLayout.LayoutParams(d(62), d(54)).apply {
            leftMargin = d(22)
            rightMargin = d(22)
        })
        controls.addView(next, LinearLayout.LayoutParams(0, d(54), 1f))
        body.addView(controls)

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(action("↻  Scan music", true) { scanDeviceMedia() }, LinearLayout.LayoutParams(0, d(48), 1f).apply {
            rightMargin = d(6)
        })
        quick.addView(action("＋  Add files") { chooseFiles() }, LinearLayout.LayoutParams(0, d(48), 1f).apply {
            leftMargin = d(6)
        })
        body.addView(quick)

        val libraryCard = surfaceCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(15), d(14), d(15), d(14))
        }
        val libCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        libCopy.addView(text("YOUR LIBRARY", 10f, blue, true))
        libCopy.addView(text(names.size.toString() + " tracks on this device", 14f, white, true).apply {
            setPadding(0, d(5), 0, 0)
        })
        libraryCard.addView(libCopy, LinearLayout.LayoutParams(0, -2, 1f))
        libraryCard.addView(text("›", 27f, muted))
        libraryCard.setOnClickListener {
            tab = "LIBRARY"
            drawTab()
        }
        body.addView(libraryCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = d(16)
            bottomMargin = d(10)
        })
        refreshTrack()
    }

    private inner class MoodArtworkView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = d(22).toFloat()
            val bounds = RectF(0f, 0f, w, h)
            val clip = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
            val save = canvas.save()
            canvas.clipPath(clip)

            paint.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.rgb(19, 42, 77), Color.rgb(25, 72, 119), Color.rgb(15, 26, 48)),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null

            paint.color = Color.argb(32, 101, 177, 255)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(w * .83f, h * .22f, w * .42f, paint)
            paint.color = Color.argb(22, 105, 133, 255)
            canvas.drawCircle(w * .12f, h * .86f, w * .38f, paint)

            paint.color = Color.argb(70, 180, 215, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(1).toFloat()
            for (i in 0..3) {
                val r = w * (.24f + i * .09f)
                canvas.drawCircle(w * .5f, h * .48f, r, paint)
            }

            val wave = Path()
            for (i in 0..100) {
                val x = w * i / 100f
                val y = h * .54f + (sin(i * .19).toFloat() * h * .065f) +
                    (sin(i * .075).toFloat() * h * .035f)
                if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            paint.color = Color.argb(160, 130, 194, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(2).toFloat()
            canvas.drawPath(wave, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(245, 255, 255, 255)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            paint.textSize = w * .31f
            canvas.drawText("M", w * .5f, h * .54f, paint)
            paint.color = Color.argb(210, 222, 234, 250)
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = d(10).toFloat()
            paint.letterSpacing = .2f
            canvas.drawText("MOOD PLAYER  •  LOCAL AUDIO", w * .5f, h * .89f, paint)
            canvas.restoreToCount(save)
        }
    }

    private fun library() {
        pageHeader("Library", "All the music stored on your device.")
        val search = EditText(this).apply {
            hint = "Search songs or artists"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 14f
            singleLine = true
            background = rounded(surface2, 15)
            setPadding(d(14), d(11), d(14), d(11))
        }
        body.addView(search, LinearLayout.LayoutParams(-1, d(48)).apply {
            bottomMargin = d(11)
        })
        val actions = LinearLayout(this)
        actions.addView(action("Scan device", true) { scanDeviceMedia() }, LinearLayout.LayoutParams(0, d(46), 1f).apply {
            rightMargin = d(5)
        })
        actions.addView(action("Add files") { chooseFiles() }, LinearLayout.LayoutParams(0, d(46), 1f).apply {
            leftMargin = d(5)
        })
        body.addView(actions)
        body.addView(text(names.size.toString() + " SONGS", 10f, muted, true).apply {
            letterSpacing = .12f
            setPadding(d(2), d(20), 0, d(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun fill(filter: String) {
            list.removeAllViews()
            val matches = names.indices.filter { names[it].contains(filter, true) }
            if (matches.isEmpty()) {
                list.addView(text(if (names.isEmpty()) "Your library is empty. Scan the device or add audio files." else "No matching tracks.", 13f, muted).apply {
                    setPadding(d(3), d(18), d(3), d(18))
                })
            }
            matches.forEach { index ->
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(d(10), d(10), d(8), d(10))
                    background = rounded(surface, 15)
                    isClickable = true
                    setOnClickListener {
                        player?.seekTo(index, 0)
                        player?.play()
                    }
                }
                val icon = text("♫", 19f, blue, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(Color.rgb(31, 48, 73), 12)
                }
                row.addView(icon, LinearLayout.LayoutParams(d(42), d(42)).apply { rightMargin = d(11) })
                val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                copy.addView(text(names[index], 13f, white, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                copy.addView(text("Local audio file", 11f, muted).apply {
                    setPadding(0, d(4), 0, 0)
                })
                row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("•••", 14f, muted))
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = d(7)
                })
            }
        }
        search.addTextChangedListener { editable -> fill(editable?.toString() ?: "") }
        fill("")
    }

    private fun eqScreen() {
        pageHeader("Equalizer", "Shape the sound to match your headphones.")
        val box = surfaceCard()
        box.addView(text("5-BAND GRAPHIC EQ", 10f, blue, true))
        box.addView(text("Audio effect availability depends on the device and playback session.", 12f, muted).apply {
            setPadding(0, d(7), 0, d(13))
        })
        val eq = getEq()
        if (eq == null) {
            box.addView(text("Start playback, then reopen Equalizer.", 13f, muted))
        } else {
            val limits = eq.bandLevelRange
            val lo = limits[0].toInt()
            val hi = limits[1].toInt()
            val labels = listOf("LOW", "LOW MID", "MID", "HIGH MID", "HIGH")
            for (i in 0 until eq.numberOfBands.toInt().coerceAtMost(5)) {
                val band = i.toShort()
                val freq = eq.getCenterFreq(band) / 1000
                val freqLabel = if (freq >= 1000) String.format(Locale.US, "%.1f kHz", freq / 1000f) else freq.toString() + " Hz"
                val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(text(labels[i], 11f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(text(freqLabel, 11f, muted))
                box.addView(line)
                box.addView(SeekBar(this).apply {
                    max = hi - lo
                    progress = (eq.getBandLevel(band).toInt() - lo).coerceIn(0, max)
                    progressTintList = android.content.res.ColorStateList.valueOf(blue)
                    thumbTintList = android.content.res.ColorStateList.valueOf(blue)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                            if (fromUser) try { eq.setBandLevel(band, (value + lo).toShort()) } catch (_: Exception) {}
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                })
            }
            box.addView(action("Reset to flat") {
                try {
                    for (i in 0 until eq.numberOfBands.toInt()) eq.setBandLevel(i.toShort(), 0)
                    drawTab()
                } catch (_: Exception) {}
            }, LinearLayout.LayoutParams(-1, d(45)).apply { topMargin = d(8) })
        }
        box.addView(text("This is a 5-band Android graphic EQ. Parametric EQ with adjustable Q is not included.", 11f, muted).apply {
            setPadding(0, d(14), 0, 0)
        })
        body.addView(box)
    }

    private fun getEq(): android.media.audiofx.Equalizer? {
        if (equalizer != null) return equalizer
        val session = player?.audioSessionId ?: 0
        if (session <= 0) return null
        return try {
            android.media.audiofx.Equalizer(0, session).also {
                it.enabled = true
                equalizer = it
            }
        } catch (_: Exception) { null }
    }

    private fun wrapped() {
        pageHeader("Your Wrapped", "A little recap of your listening.")
        val data = listOf(
            Triple("TRACK SESSIONS", prefs.getInt("plays", 0).toString(), "Times you started a queue"),
            Triple("LISTENING TIME", (prefs.getLong("seconds", 0L) / 60).toString() + " min", "Approximate time spent listening"),
            Triple("MUSIC LIBRARY", names.size.toString(), "Tracks found on this device")
        )
        data.forEach { item ->
            val c = surfaceCard()
            c.addView(text(item.first, 10f, blue, true))
            c.addView(text(item.second, 25f, white, true).apply { setPadding(0, d(7), 0, d(4)) })
            c.addView(text(item.third, 12f, muted))
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(9) })
        }
        body.addView(text("Your listening stats stay on this device.", 11f, muted).apply {
            setPadding(0, d(5), 0, 0)
        })
    }

    private fun settings() {
        pageHeader("More", "The details behind your listening setup.")
        val cards = listOf(
            Triple("AUDIO OUTPUT", "Android shared audio route", "The system mixer may resample audio. Exclusive USB DAC output is not implemented."),
            Triple("BIT-PERFECT", "Not available / not verified", "This version does not claim bit-perfect playback."),
            Triple("EQUALIZER", "5-band graphic EQ", "Parametric EQ with adjustable Q and bandwidth is not included."),
            Triple("ABOUT MOOD PLAYER", "Version 1.2.0", "Built for local listening. Developer by Reandra.")
        )
        cards.forEach { item ->
            val c = surfaceCard()
            c.addView(text(item.first, 10f, blue, true))
            c.addView(text(item.second, 15f, white, true).apply { setPadding(0, d(6), 0, d(5)) })
            c.addView(text(item.third, 12f, muted))
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(9) })
        }
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
            prefs.edit().putInt("plays", prefs.getInt("plays", 0) + 1).apply()
            drawTab()
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
        if (!::titleView.isInitialized || !::subView.isInitialized) return
        val index = player?.currentMediaItemIndex ?: -1
        titleView.text = names.getOrNull(index) ?: "Nothing playing"
        subView.text = if (names.isEmpty()) "Choose a track from your library" else "Local audio  •  " + names.size + " tracks"
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        if (::playButton.isInitialized) playButton.text = if (p.isPlaying) "Ⅱ" else "▶"
    }

    private fun time(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
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
