package com.moodplayer.app

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var tab = "HOME"
    private val queue = mutableListOf<Uri>()
    private val names = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("mood_stats", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(8, 10, 16)
    private val surface = Color.rgb(20, 24, 34)
    private val surface2 = Color.rgb(29, 35, 48)
    private val blue = Color.rgb(55, 137, 255)
    private val muted = Color.rgb(151, 162, 180)
    private val white = Color.rgb(246, 247, 250)
    private val teal = Color.rgb(30, 220, 190)

    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var playButton: TextView
    private lateinit var homeSeek: SeekBar
    private lateinit var nowSeek: SeekBar
    private lateinit var homeElapsed: TextView
    private lateinit var homeDuration: TextView
    private lateinit var nowElapsed: TextView
    private lateinit var nowDuration: TextView
    private var lastArtworkUri: String? = null
    private val artworkViews = mutableListOf<ImageView>()

    private val timer = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val durationMs = p.duration
                if (durationMs > 0 && durationMs < Int.MAX_VALUE) {
                    if (::homeSeek.isInitialized) {
                        homeSeek.max = durationMs.toInt()
                        homeSeek.progress = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        homeElapsed.text = time(p.currentPosition)
                        homeDuration.text = time(durationMs)
                    }
                    if (::nowSeek.isInitialized) {
                        nowSeek.max = durationMs.toInt()
                        nowSeek.progress = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        nowElapsed.text = time(p.currentPosition)
                        nowDuration.text = time(durationMs)
                    }
                }
                updatePlayButtons()
                refreshTrack()
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
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                lastArtworkUri = null
                refreshTrack()
                loadCurrentArtwork()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayButtons() }
        })
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
                refreshMiniPlayer()
                if (tab == "HOME" || tab == "LIBRARY") drawTab()
                Toast.makeText(this, foundUris.size.toString() + " lagu ditemukan.", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun d(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun rounded(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = d(radius).toFloat() }

    private fun gradient(colors: IntArray, radius: Int = 20): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = d(radius).toFloat() }

    private fun pageCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 22)
        setPadding(d(16), d(16), d(16), d(16))
    }

    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 13f, if (primary) Color.WHITE else white, true).apply {
            gravity = Gravity.CENTER
            minHeight = d(46)
            setPadding(d(14), d(10), d(14), d(10))
            background = rounded(if (primary) blue else surface2, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(d(20), d(7), d(20), d(17))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        miniBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 17)
            setPadding(d(10), d(8), d(10), d(8))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { openNowPlaying() }
        }
        val miniArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = gradient(intArrayOf(Color.rgb(24, 74, 105), Color.rgb(39, 45, 91)), 10)
        }
        miniBar.addView(miniArt, LinearLayout.LayoutParams(d(43), d(43)).apply { rightMargin = d(11) })
        artworkViews.add(miniArt)
        val miniCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = text("Nothing playing", 12f, white, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniArtist = text("Choose a song", 10f, muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniCopy.addView(miniTitle)
        miniCopy.addView(miniArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(4) })
        miniBar.addView(miniCopy, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlay = text("▶", 18f, white, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { toggle() }
        }
        miniBar.addView(miniPlay, LinearLayout.LayoutParams(d(42), d(42)))
        root.addView(miniBar, LinearLayout.LayoutParams(-1, d(62)).apply {
            leftMargin = d(12); rightMargin = d(12); bottomMargin = d(7)
        })

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ColorDrawableCompat(bg)
            setPadding(d(7), d(5), d(7), d(5))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, d(58)))
        setContentView(root)
        rebuildNav()
    }

    private fun rebuildNav() {
        if (!::nav.isInitialized) return
        nav.removeAllViews()
        val items = listOf(
            Triple("HOME", "⌂", "Home"),
            Triple("LIBRARY", "♫", "Library"),
            Triple("EQ", "≋", "EQ"),
            Triple("TIDAL", "◈", "TIDAL"),
            Triple("MORE", "•••", "More")
        )
        items.forEach { item ->
            val selected = (tab == item.first) || (tab == "PLAYER" && item.first == "HOME")
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(d(2), d(4), d(2), d(3))
                isClickable = true
                isFocusable = true
                setOnClickListener { tab = item.first; drawTab() }
            }
            itemView.addView(text(item.second, if (item.first == "MORE") 15f else 18f,
                if (selected) blue else muted, selected).apply { gravity = Gravity.CENTER })
            itemView.addView(text(item.third, 9f, if (selected) blue else muted, selected).apply {
                gravity = Gravity.CENTER
                setPadding(0, d(4), 0, 0)
            })
            nav.addView(itemView, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun drawTab() {
        body.removeAllViews()
        artworkViews.clear()
        when (tab) {
            "HOME" -> home()
            "LIBRARY" -> library()
            "EQ" -> eqScreen()
            "TIDAL" -> tidalScreen()
            "PLAYER" -> nowPlaying()
            else -> settings()
        }
        rebuildNav()
        refreshMiniPlayer()
    }

    private fun brandHeader() {
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        line.addView(text("◖", 25f, blue, true))
        line.addView(text("MOOD PLAYER", 12f, white, true).apply {
            letterSpacing = .16f
            setPadding(d(7), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text("1.3.0", 10f, muted))
        body.addView(line, LinearLayout.LayoutParams(-1, d(42)))
    }

    private fun sectionTitle(title: String, subtitle: String? = null) {
        body.addView(text(title, 25f, white, true).apply { setPadding(0, d(17), 0, d(5)) })
        if (subtitle != null) body.addView(text(subtitle, 12f, muted).apply {
            setPadding(0, 0, 0, d(15))
        })
    }

    private fun coverView(size: Int, uri: Uri? = currentUri()): ImageView {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = gradient(intArrayOf(Color.rgb(19, 53, 85), Color.rgb(34, 39, 78), Color.rgb(14, 22, 38)), 16)
            clipToOutline = true
        }
        artworkViews.add(img)
        if (uri != null) loadArtwork(img, uri)
        return img
    }

    private fun home() {
        brandHeader()
        body.addView(text("Your music.\nYour moment.", 30f, white, true).apply {
            setLineSpacing(d(2).toFloat(), 1f)
            setPadding(0, d(20), 0, d(5))
        })
        body.addView(text("A little more room for the music you love.", 12f, muted).apply {
            setPadding(0, 0, 0, d(18))
        })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(intArrayOf(Color.rgb(18, 37, 65), Color.rgb(29, 42, 66), Color.rgb(23, 28, 43)), 22)
            setPadding(d(14), d(14), d(14), d(14))
        }
        val art = coverView(142)
        hero.addView(art, LinearLayout.LayoutParams(d(138), d(154)).apply { rightMargin = d(14) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text("ON YOUR DEVICE", 9f, blue, true).apply { letterSpacing = .13f })
        copy.addView(text("Your library", 20f, white, true).apply {
            setPadding(0, d(9), 0, d(5))
        })
        copy.addView(text(names.size.toString() + " tracks ready to play", 11f, muted).apply {
            maxLines = 2
        })
        copy.addView(action("Open library  ›", true) {
            tab = "LIBRARY"; drawTab()
        }.apply { textSize = 11f; minHeight = d(38); setPadding(d(8), d(5), d(8), d(5)) },
            LinearLayout.LayoutParams(-1, d(38)).apply { topMargin = d(14) })
        hero.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(22) })

        val rowTitle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rowTitle.addView(text("Recently added", 18f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        rowTitle.addView(text("SEE ALL  ›", 9f, blue, true).apply {
            setOnClickListener { tab = "LIBRARY"; drawTab() }
        })
        body.addView(rowTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(11) })
        if (names.isEmpty()) {
            val empty = pageCard()
            empty.addView(text("Your library is waiting.", 15f, white, true))
            empty.addView(text("Scan your device or add audio files to start listening.", 11f, muted).apply {
                setPadding(0, d(6), 0, d(12))
            })
            empty.addView(action("Scan device music", true) { scanDeviceMedia() })
            empty.addView(action("Add audio files") { chooseFiles() }, LinearLayout.LayoutParams(-1, d(44)).apply { topMargin = d(8) })
            body.addView(empty)
        } else {
            for (i in 0 until min(4, names.size)) addTrackRow(i)
        }

        body.addView(text("MADE FOR LISTENING", 9f, muted, true).apply {
            letterSpacing = .16f
            setPadding(0, d(22), 0, d(9))
        })
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(action("↻  Scan music", true) { scanDeviceMedia() }, LinearLayout.LayoutParams(0, d(45), 1f).apply { rightMargin = d(6) })
        quick.addView(action("＋  Add files") { chooseFiles() }, LinearLayout.LayoutParams(0, d(45), 1f).apply { leftMargin = d(6) })
        body.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) })
    }

    private fun addTrackRow(index: Int) {
        if (index !in names.indices) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(7), d(7), d(7))
            background = rounded(surface, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { playIndex(index) }
        }
        val icon = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "♫"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = gradient(intArrayOf(Color.rgb(27, 93, 140), Color.rgb(67, 63, 135)), 11)
        }
        row.addView(icon, LinearLayout.LayoutParams(d(43), d(43)).apply { rightMargin = d(10) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(trackTitle(names[index]), 12f, white, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(text(trackArtist(names[index]), 10f, muted).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, d(4), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("•••", 13f, muted))
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6) })
    }

    private fun library() {
        sectionTitle("Library", "Everything you have saved on this device.")
        val search = EditText(this).apply {
            hint = "Search songs or artists"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 13f
            isSingleLine = true
            background = rounded(surface2, 14)
            setPadding(d(14), d(10), d(14), d(10))
        }
        body.addView(search, LinearLayout.LayoutParams(-1, d(46)).apply { bottomMargin = d(13) })
        val actions = LinearLayout(this)
        actions.addView(action("Scan device", true) { scanDeviceMedia() }, LinearLayout.LayoutParams(0, d(43), 1f).apply { rightMargin = d(5) })
        actions.addView(action("Add files") { chooseFiles() }, LinearLayout.LayoutParams(0, d(43), 1f).apply { leftMargin = d(5) })
        body.addView(actions)
        body.addView(text(names.size.toString() + " TRACKS", 9f, muted, true).apply {
            letterSpacing = .15f; setPadding(d(2), d(20), 0, d(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun fill(filter: String) {
            list.removeAllViews()
            val matches = names.indices.filter { names[it].contains(filter, true) }
            if (matches.isEmpty()) {
                list.addView(text(if (names.isEmpty()) "No music found yet. Scan your device to begin." else "No matching tracks.", 12f, muted).apply {
                    setPadding(d(4), d(20), d(4), d(20))
                })
            }
            matches.forEach { index ->
                val old = body
                body = list
                addTrackRow(index)
                body = old
            }
        }
        search.addTextChangedListener { fill(it?.toString() ?: "") }
        fill("")
    }

    private fun nowPlaying() {
        sectionTitle("Now Playing", "A closer seat to the music.")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val art = coverView(280)
        card.addView(art, LinearLayout.LayoutParams(min(d(300), resources.displayMetrics.widthPixels - d(54)),
            min(d(300), resources.displayMetrics.widthPixels - d(54))).apply {
            bottomMargin = d(23)
        })
        titleView = text("Nothing playing", 21f, white, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subView = text("Choose a track from your library", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(7), 0, d(18))
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        card.addView(subView, LinearLayout.LayoutParams(-1, -2))
        nowSeek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(blue)
            thumbTintList = android.content.res.ColorStateList.valueOf(blue)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(61, 68, 82))
        }
        card.addView(nowSeek, LinearLayout.LayoutParams(-1, d(28)))
        nowSeek.setOnSeekBarChangeListener(seekListener())
        val times = LinearLayout(this)
        nowElapsed = text("0:00", 10f, muted)
        nowDuration = text("0:00", 10f, muted).apply { gravity = Gravity.END }
        times.addView(nowElapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(nowDuration, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(times)
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(19), 0, d(18))
        }
        controls.addView(text("|◀", 20f, white, true).apply {
            gravity = Gravity.CENTER; setOnClickListener { player?.seekToPreviousMediaItem() }
        }, LinearLayout.LayoutParams(d(62), d(54)))
        playButton = text("Ⅱ", 21f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rounded(blue, 28); setOnClickListener { toggle() }
        }
        controls.addView(playButton, LinearLayout.LayoutParams(d(67), d(54)).apply {
            leftMargin = d(22); rightMargin = d(22)
        })
        controls.addView(text("▶|", 20f, white, true).apply {
            gravity = Gravity.CENTER; setOnClickListener { player?.seekToNextMediaItem() }
        }, LinearLayout.LayoutParams(d(62), d(54)))
        card.addView(controls)
        card.addView(text("LOCAL AUDIO  •  MOOD PLAYER", 9f, muted, true).apply { letterSpacing = .14f })
        body.addView(card)
        refreshTrack()
    }

    private fun seekListener() = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
            if (fromUser) {
                if (::homeElapsed.isInitialized && s === homeSeek) homeElapsed.text = time(value.toLong())
                if (::nowElapsed.isInitialized && s === nowSeek) nowElapsed.text = time(value.toLong())
            }
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) { player?.seekTo(s?.progress?.toLong() ?: 0L) }
    }

    private fun eqScreen() {
        sectionTitle("Equalizer", "A little adjustment goes a long way.")
        val box = pageCard()
        box.addView(text("5-BAND GRAPHIC EQ", 9f, blue, true).apply { letterSpacing = .14f })
        box.addView(text("Tune the output to match your headphones.", 11f, muted).apply { setPadding(0, d(6), 0, d(14)) })
        val eq = getEq()
        if (eq == null) {
            box.addView(text("Start playback, then reopen Equalizer.", 12f, muted))
        } else {
            val limits = eq.bandLevelRange
            val lo = limits[0].toInt()
            val hi = limits[1].toInt()
            val labels = listOf("LOW", "LOW MID", "MID", "HIGH MID", "HIGH")
            for (i in 0 until eq.numberOfBands.toInt().coerceAtMost(5)) {
                val band = i.toShort()
                val hz = eq.getCenterFreq(band) / 1000
                val freq = if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000f) else hz.toString() + " Hz"
                val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(text(labels[i], 10f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(text(freq, 10f, muted))
                box.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(9) })
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
                try { for (i in 0 until eq.numberOfBands.toInt()) eq.setBandLevel(i.toShort(), 0); drawTab() } catch (_: Exception) {}
            }, LinearLayout.LayoutParams(-1, d(42)).apply { topMargin = d(9) })
        }
        box.addView(text("Android graphic EQ only. Parametric EQ and adjustable Q are not included.", 10f, muted).apply {
            setPadding(0, d(13), 0, 0)
        })
        body.addView(box)
    }

    private fun tidalScreen() {
        sectionTitle("TIDAL", "Find your next favourite track.")
        val hero = pageCard().apply {
            background = gradient(intArrayOf(Color.rgb(14, 56, 65), Color.rgb(20, 38, 54), Color.rgb(22, 28, 43)), 22)
        }
        hero.addView(text("TIDAL", 28f, Color.WHITE, true).apply { letterSpacing = .11f })
        hero.addView(text("A world of music, one search away.", 12f, Color.rgb(203, 224, 229)).apply {
            setPadding(0, d(7), 0, d(15))
        })
        hero.addView(action("Open TIDAL", true) { openTidal(null) })
        body.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(17) })

        body.addView(text("Search TIDAL", 17f, white, true).apply { setPadding(0, d(2), 0, d(10)) })
        val search = EditText(this).apply {
            hint = "Song, artist or album"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 13f
            singleLine = true
            background = rounded(surface2, 14)
            setPadding(d(14), d(10), d(14), d(10))
        }
        body.addView(search, LinearLayout.LayoutParams(-1, d(47)).apply { bottomMargin = d(9) })
        body.addView(action("Search in TIDAL  ↗", true) { openTidal(search.text?.toString()) },
            LinearLayout.LayoutParams(-1, d(46)))
        body.addView(text("TIDAL opens in its official app when installed, or in your browser.", 10f, muted).apply {
            setPadding(d(2), d(9), d(2), d(17))
        })

        val info = pageCard()
        info.addView(text("ABOUT STREAMING", 9f, teal, true).apply { letterSpacing = .13f })
        info.addView(text("External TIDAL playback", 15f, white, true).apply { setPadding(0, d(7), 0, d(5)) })
        info.addView(text("This version sends searches to TIDAL; audio plays in the official TIDAL app, not inside Mood Player. Full in-app streaming requires TIDAL developer authorization and playback entitlements. No account password is requested or stored here.", 11f, muted))
        body.addView(info)
    }

    private fun settings() {
        sectionTitle("More", "Your player, your settings.")
        val stats = pageCard()
        stats.addView(text("YOUR LISTENING", 9f, blue, true).apply { letterSpacing = .14f })
        stats.addView(text((prefs.getLong("seconds", 0L) / 60).toString() + " minutes", 24f, white, true).apply {
            setPadding(0, d(7), 0, d(4))
        })
        stats.addView(text("Approximate playback time on this device.", 10f, muted))
        body.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10) })
        val cards = listOf(
            Triple("AUDIO OUTPUT", "Android shared audio route", "The system may resample audio. Exclusive USB DAC output is not implemented."),
            Triple("BIT-PERFECT", "Not available / not verified", "Mood Player does not claim bit-perfect output."),
            Triple("EQUALIZER", "5-band graphic EQ", "Parametric EQ with adjustable Q is not included."),
            Triple("ABOUT", "Mood Player 1.3.0", "Developer by Reandra.")
        )
        cards.forEach { item ->
            val c = pageCard()
            c.addView(text(item.first, 9f, blue, true).apply { letterSpacing = .12f })
            c.addView(text(item.second, 13f, white, true).apply { setPadding(0, d(6), 0, d(4)) })
            c.addView(text(item.third, 10f, muted))
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(9) })
        }
    }

    private fun openTidal(query: String?) {
        val clean = query?.trim().orEmpty()
        val url = if (clean.isBlank()) "https://tidal.com/" else "https://tidal.com/search?q=" + Uri.encode(clean)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "TIDAL tidak bisa dibuka di perangkat ini.", Toast.LENGTH_LONG).show()
        }
    }

    private fun getEq(): android.media.audiofx.Equalizer? {
        if (equalizer != null) return equalizer
        val session = player?.audioSessionId ?: 0
        if (session <= 0) return null
        return try {
            android.media.audiofx.Equalizer(0, session).also { it.enabled = true; equalizer = it }
        } catch (_: Exception) { null }
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
            queue.clear(); queue.addAll(uris); names.clear()
            val items = uris.map { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val name = fileName(uri)
                names.add(name)
                MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build()
            }
            p.setMediaItems(items); p.prepare(); p.play()
            prefs.edit().putInt("plays", prefs.getInt("plays", 0) + 1).apply()
            lastArtworkUri = null
            refreshMiniPlayer()
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

    private fun playIndex(index: Int) {
        val p = player ?: return
        if (index in queue.indices) {
            p.seekTo(index, 0L)
            p.play()
            prefs.edit().putInt("plays", prefs.getInt("plays", 0) + 1).apply()
            lastArtworkUri = null
            refreshTrack()
            loadCurrentArtwork()
        }
    }

    private fun currentUri(): Uri? = queue.getOrNull(player?.currentMediaItemIndex ?: -1)

    private fun trackTitle(value: String): String = value.substringBefore(" — ").ifBlank { value }
    private fun trackArtist(value: String): String = value.substringAfter(" — ", "Local audio")

    private fun refreshTrack() {
        val index = player?.currentMediaItemIndex ?: -1
        val name = names.getOrNull(index)
        if (::titleView.isInitialized) titleView.text = name?.let { trackTitle(it) } ?: "Nothing playing"
        if (::subView.isInitialized) subView.text = name?.let { trackArtist(it) } ?: "Choose a track from your library"
        if (::miniTitle.isInitialized) {
            miniTitle.text = name?.let { trackTitle(it) } ?: "Nothing playing"
            miniArtist.text = name?.let { trackArtist(it) } ?: "Choose a song"
        }
        refreshMiniPlayer()
        val uri = currentUri()
        if (uri?.toString() != lastArtworkUri) {
            lastArtworkUri = uri?.toString()
            loadCurrentArtwork()
        }
    }

    private fun refreshMiniPlayer() {
        if (!::miniBar.isInitialized) return
        miniBar.visibility = if (queue.isNotEmpty()) View.VISIBLE else View.GONE
        val name = names.getOrNull(player?.currentMediaItemIndex ?: -1)
        if (::miniTitle.isInitialized) miniTitle.text = name?.let { trackTitle(it) } ?: "Ready when you are"
        if (::miniArtist.isInitialized) miniArtist.text = name?.let { trackArtist(it) } ?: "Mood Player"
    }

    private fun loadCurrentArtwork() {
        val uri = currentUri() ?: return
        artworkViews.toList().forEach { loadArtwork(it, uri) }
    }

    private fun loadArtwork(target: ImageView, uri: Uri) {
        Thread {
            var bitmap: Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val bytes = retriever.embeddedPicture
                if (bytes != null) bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                retriever.release()
            } catch (_: Exception) {}
            val result = bitmap
            runOnUiThread {
                if (!isFinishing && target.parent != null) {
                    if (result != null) target.setImageBitmap(result) else target.setImageDrawable(null)
                }
            }
        }.start()
    }

    private fun updatePlayButtons() {
        val playing = player?.isPlaying == true
        if (::playButton.isInitialized) playButton.text = if (playing) "Ⅱ" else "▶"
        if (::miniPlay.isInitialized) miniPlay.text = if (playing) "Ⅱ" else "▶"
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayButtons()
    }

    private fun openNowPlaying() {
        tab = "PLAYER"
        drawTab()
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

    private class ColorDrawableCompat(color: Int) : android.graphics.drawable.ColorDrawable(color)
}
