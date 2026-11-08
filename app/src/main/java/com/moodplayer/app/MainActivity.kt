package com.moodplayer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import java.io.File
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
import com.yuka.musicplayer.audio.AudioEngine
import com.yuka.musicplayer.audio.UsbAudioController
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private val usbEngine by lazy { AudioEngine() }
    private var usbController: UsbAudioController? = null
    @Volatile private var usbReady = false
    @Volatile private var directPlayback = false
    private var directDurationMs: Long = 0L
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
                val duration = if (directPlayback) directDurationMs else p.duration
                val position = if (directPlayback) try { (usbEngine.getPosition() * 1000.0).toLong().coerceAtLeast(0L) } catch (_: Exception) { 0L } else p.currentPosition
                if (duration > 0 && duration < Int.MAX_VALUE) {
                    if (::homeSeek.isInitialized) { homeSeek.max=duration.toInt(); homeSeek.progress=position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); homeElapsed.text=time(position); homeDuration.text=time(duration) }
                    if (::nowSeek.isInitialized) { nowSeek.max=duration.toInt(); nowSeek.progress=position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); nowElapsed.text=time(position); nowDuration.text=time(duration) }
                }
                updatePlayButtons()
                refreshTrack()
                if (isAudioPlaying()) prefs.edit().putLong("seconds",prefs.getLong("seconds",0L)+1L).apply()
            }
            handler.postDelayed(this,1000)
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
        window.statusBarColor=bg
        window.navigationBarColor=bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        player=ExoPlayer.Builder(this).build()
        player?.addListener(object:Player.Listener {
            override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) { lastArtworkUri=null; refreshTrack(); loadCurrentArtwork() }
            override fun onIsPlayingChanged(isPlaying:Boolean) { updatePlayButtons() }
        })
        buildLayout(); drawTab(); ensureAudioPermission(); setupUsbAudio(); handler.post(timer)
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
        val line=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        line.addView(text("◖",26f,blue,true))
        line.addView(text("MOOD",13f,white,true).apply { letterSpacing=.18f; setPadding(d(8),0,0,0) })
        line.addView(text("PLAYER",13f,blue,true).apply { letterSpacing=.18f; setPadding(d(4),0,0,0) })
        line.addView(LinearLayout(this),LinearLayout.LayoutParams(0,1,1f))
        line.addView(text("1.5.0",10f,muted))
        body.addView(line,LinearLayout.LayoutParams(-1,d(44)))
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
        body.addView(text("Listen Now",32f,white,true).apply { setPadding(0,d(19),0,d(2)) })
        body.addView(text("Your music. Your space.",13f,muted).apply { setPadding(0,0,0,d(21)) })
        if(names.isEmpty()) {
            body.addView(text("Start with your library",19f,white,true).apply { setPadding(0,d(24),0,d(7)) })
            body.addView(text("Bring the music stored on this device into Mood Player.",12f,muted).apply { setPadding(0,0,0,d(17)) })
            body.addView(action("＋  Add music",true){chooseFiles()},LinearLayout.LayoutParams(-1,d(48)))
            body.addView(action("Scan device library"){scanDeviceMedia()},LinearLayout.LayoutParams(-1,d(46)).apply { topMargin=d(9) })
        } else {
            val feature=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            val label=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            label.addView(text("PICK UP WHERE YOU LEFT OFF",9f,blue,true).apply { letterSpacing=.14f },LinearLayout.LayoutParams(0,-2,1f))
            label.addView(text("LOCAL MUSIC",9f,muted,true))
            feature.addView(label)
            feature.addView(coverView(270,currentUri()?:queue.firstOrNull()),LinearLayout.LayoutParams(-1,d(250)).apply { topMargin=d(15); bottomMargin=d(15) })
            val index=(player?.currentMediaItemIndex?:0).coerceIn(0,names.lastIndex)
            feature.addView(text(trackTitle(names[index]),21f,white,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
            feature.addView(text(trackArtist(names[index]),12f,muted).apply { setPadding(0,d(5),0,d(14)); maxLines=1 })
            val buttons=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            buttons.addView(action(if(isAudioPlaying()) "Ⅱ  Now playing" else "▶  Play",true){ if(isAudioPlaying()) openNowPlaying() else playIndex(index) },LinearLayout.LayoutParams(0,d(46),1f).apply { rightMargin=d(5) })
            buttons.addView(action("＋  Add music"){chooseFiles()},LinearLayout.LayoutParams(0,d(46),1f).apply { leftMargin=d(5) })
            feature.addView(buttons)
            body.addView(feature,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(28) })
        }
        val shelfTitle=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        shelfTitle.addView(text("Recently Added",20f,white,true),LinearLayout.LayoutParams(0,-2,1f))
        shelfTitle.addView(text("See All  ›",11f,blue,true).apply { setOnClickListener { tab="LIBRARY"; drawTab() } })
        body.addView(shelfTitle,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(13) })
        if(names.isNotEmpty()) {
            val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
            val shelf=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            for(i in 0 until min(10,names.size)) {
                val tile=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; isClickable=true; isFocusable=true; setOnClickListener { playIndex(i) } }
                tile.addView(coverView(d(138),queue.getOrNull(i)),LinearLayout.LayoutParams(d(138),d(138)))
                tile.addView(text(trackTitle(names[i]),12f,white,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,d(8),0,d(3)) })
                tile.addView(text(trackArtist(names[i]),10f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                shelf.addView(tile,LinearLayout.LayoutParams(d(138),-2).apply { rightMargin=d(13) })
            }
            scroll.addView(shelf); body.addView(scroll,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(24) })
        } else body.addView(text("Your recently added music will appear here.",12f,muted).apply { setPadding(0,0,0,d(25)) })
        val tidal=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,d(14),0,d(14)) }
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        copy.addView(text("DISCOVER MORE",9f,teal,true).apply { letterSpacing=.12f })
        copy.addView(text("Explore TIDAL catalogue",14f,white,true).apply { setPadding(0,d(5),0,0) })
        tidal.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        tidal.addView(action("Open  ↗"){tab="TIDAL";drawTab()},LinearLayout.LayoutParams(d(100),d(40)))
        body.addView(tidal,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(12) })
    }

    private fun addTrackRow(index: Int) {
        if(index !in names.indices) return
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,d(8),0,d(8)); isClickable=true; isFocusable=true; setOnClickListener { playIndex(index) } }
        row.addView(coverView(d(52),queue.getOrNull(index)),LinearLayout.LayoutParams(d(52),d(52)).apply { rightMargin=d(12) })
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        copy.addView(text(trackTitle(names[index]),13f,white,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        copy.addView(text(trackArtist(names[index]),11f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,d(5),0,0) })
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(text(if(index==player?.currentMediaItemIndex && isAudioPlaying()) "Ⅱ" else "•••",13f,if(index==player?.currentMediaItemIndex && isAudioPlaying()) blue else muted))
        body.addView(row,LinearLayout.LayoutParams(-1,-2))
        body.addView(View(this).apply { setBackgroundColor(Color.rgb(34,39,51)) },LinearLayout.LayoutParams(-1,d(1)))
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
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        top.addView(text("⌄",28f,white,true).apply { gravity=Gravity.CENTER; setOnClickListener { tab="HOME";drawTab() } },LinearLayout.LayoutParams(d(44),d(44)))
        val heading=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        heading.addView(text("NOW PLAYING",10f,white,true).apply { letterSpacing=.2f;gravity=Gravity.CENTER })
        heading.addView(text(if(directPlayback) "USB DAC OUTPUT" else "MOOD PLAYER",8f,muted).apply { gravity=Gravity.CENTER;setPadding(0,d(4),0,0) })
        top.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(text("•••",18f,muted).apply { gravity=Gravity.CENTER;setOnClickListener { showTrackDiagnostics() } },LinearLayout.LayoutParams(d(44),d(44)))
        body.addView(top,LinearLayout.LayoutParams(-1,d(48)).apply { bottomMargin=d(12) })
        val size=min(d(326),resources.displayMetrics.widthPixels-d(52))
        val art=coverView(size,currentUri()); art.elevation=d(13).toFloat()
        body.addView(art,LinearLayout.LayoutParams(size,size).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=d(27) })
        val meta=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        titleView=text("Nothing playing",22f,white,true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        subView=text("Choose a track from your library",13f,muted).apply { setPadding(0,d(6),0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        copy.addView(titleView);copy.addView(subView);meta.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        meta.addView(text("♡",24f,muted).apply { gravity=Gravity.CENTER;setOnClickListener { Toast.makeText(this@MainActivity,"Favorites are not available yet.",Toast.LENGTH_SHORT).show() } },LinearLayout.LayoutParams(d(42),d(42)))
        body.addView(meta,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(17) })
        nowSeek=SeekBar(this).apply { progressTintList=android.content.res.ColorStateList.valueOf(blue);thumbTintList=android.content.res.ColorStateList.valueOf(blue);progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(61,68,82)) }
        body.addView(nowSeek,LinearLayout.LayoutParams(-1,d(28)));nowSeek.setOnSeekBarChangeListener(seekListener())
        val times=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        nowElapsed=text("0:00",10f,muted);nowDuration=text("0:00",10f,muted).apply { gravity=Gravity.END }
        times.addView(nowElapsed,LinearLayout.LayoutParams(0,-2,1f));times.addView(nowDuration,LinearLayout.LayoutParams(0,-2,1f))
        body.addView(times,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(19) })
        val controls=LinearLayout(this).apply { gravity=Gravity.CENTER;setPadding(0,d(2),0,d(13)) }
        controls.addView(text("|◀",24f,white,true).apply { gravity=Gravity.CENTER;setOnClickListener { val n=(player?.currentMediaItemIndex?:-1)-1;if(n in queue.indices)playIndex(n) } },LinearLayout.LayoutParams(d(60),d(58)))
        playButton=text("▶",24f,Color.WHITE,true).apply { gravity=Gravity.CENTER;background=rounded(blue,32);setOnClickListener { toggle() } }
        controls.addView(playButton,LinearLayout.LayoutParams(d(72),d(62)).apply { leftMargin=d(18);rightMargin=d(18) })
        controls.addView(text("▶|",24f,white,true).apply { gravity=Gravity.CENTER;setOnClickListener { val n=(player?.currentMediaItemIndex?:-1)+1;if(n in queue.indices)playIndex(n) } },LinearLayout.LayoutParams(d(60),d(58)))
        body.addView(controls)
        val out=if(directPlayback) "USB DAC DIRECT OUTPUT" else if(usbReady) "USB DAC READY · FLAC/WAV" else "ANDROID AUDIO OUTPUT"
        body.addView(text(out,9f,if(directPlayback)teal else muted,true).apply { gravity=Gravity.CENTER;letterSpacing=.12f;setPadding(0,d(13),0,d(13));background=rounded(surface,13) },LinearLayout.LayoutParams(-1,d(42)))
        refreshTrack()
    }

    private fun seekListener()=object:SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s:SeekBar?,v:Int,fromUser:Boolean){if(fromUser){if(::homeElapsed.isInitialized&&s===homeSeek)homeElapsed.text=time(v.toLong());if(::nowElapsed.isInitialized&&s===nowSeek)nowElapsed.text=time(v.toLong())}}
        override fun onStartTrackingTouch(s:SeekBar?){}
        override fun onStopTrackingTouch(s:SeekBar?){val p=s?.progress?.toLong()?:0L;if(directPlayback)try{usbEngine.seekTo(p/1000.0)}catch(_:Exception){}else player?.seekTo(p)}
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
            isSingleLine = true
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
        sectionTitle("More","Playback, output and app details.")
        val output=pageCard()
        output.addView(text("USB DAC OUTPUT",9f,blue,true).apply { letterSpacing=.14f })
        val verified=if(directPlayback)try{usbEngine.isBitPerfectIntegerPassActive()}catch(_:Exception){false}else false
        val state=when { directPlayback&&verified->"Direct USB · bit-perfect pass active";directPlayback->"Direct USB · audio is playing";usbReady->"DAC connected · FLAC/WAV ready";else->"No USB DAC connected" }
        output.addView(text(state,16f,white,true).apply { setPadding(0,d(8),0,d(5)) })
        output.addView(text("Direct output uses the USB device connection. MP3/AAC and unsupported formats continue through Android audio. Bit-perfect status appears only when the engine confirms an active integer pass.",11f,muted))
        output.addView(action("Scan / connect USB DAC",true){usbController?.scanAndRequestPermission()},LinearLayout.LayoutParams(-1,d(44)).apply { topMargin=d(14) })
        output.addView(action("USB diagnostics"){showTrackDiagnostics()},LinearLayout.LayoutParams(-1,d(42)).apply { topMargin=d(8) })
        body.addView(output,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(12) })
        val stats=pageCard()
        stats.addView(text("YOUR LISTENING",9f,blue,true))
        stats.addView(text((prefs.getLong("seconds",0L)/60).toString()+" minutes",24f,white,true).apply { setPadding(0,d(7),0,d(4)) })
        stats.addView(text("Approximate playback time on this device.",10f,muted))
        body.addView(stats,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(12) })
        val eq=pageCard()
        eq.addView(text("EQUALIZER",9f,blue,true))
        eq.addView(text("5-band graphic EQ",14f,white,true).apply { setPadding(0,d(6),0,d(4)) })
        eq.addView(text("Android AudioEffect applies only to Android output. Direct USB playback bypasses this system EQ.",11f,muted))
        body.addView(eq,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=d(12) })
        val about=pageCard();about.addView(text("ABOUT MOOD PLAYER",9f,blue,true));about.addView(text("Mood Player 1.5.0",14f,white,true).apply { setPadding(0,d(6),0,d(4)) });about.addView(text("A local music player by Reandra.",11f,muted));body.addView(about)
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

    private fun setupUsbAudio() {
        try {
            usbEngine.onTrackFinishedCallback={handler.post{val next=(player?.currentMediaItemIndex?:-1)+1;if(next in queue.indices)playIndex(next)else{directPlayback=false;directDurationMs=0L;updatePlayButtons()}}}
            usbEngine.onUsbStallFaultCallback={handler.post{val i=player?.currentMediaItemIndex?:-1;val pos=try{(usbEngine.getPosition()*1000.0).toLong()}catch(_:Exception){0L};directPlayback=false;directDurationMs=0L;if(i in queue.indices){player?.seekTo(i,pos);player?.play()};Toast.makeText(this,"USB audio stalled. Switched to Android output.",Toast.LENGTH_LONG).show();updatePlayButtons()}}
            usbEngine.onDeviceForceDisconnectedCallback={handler.post{usbReady=false;val i=player?.currentMediaItemIndex?:-1;directPlayback=false;directDurationMs=0L;if(i in queue.indices)player?.play();Toast.makeText(this,"USB DAC disconnected.",Toast.LENGTH_LONG).show();updatePlayButtons()}}
            usbController=UsbAudioController(this).apply {
                onDeviceReady={fd->Thread{val ready=try{usbEngine.initUsbDac(fd)}catch(_:Exception){false};runOnUiThread{usbReady=ready;Toast.makeText(this@MainActivity,if(ready)"USB DAC ready. FLAC/WAV can use direct USB."else"USB DAC detected, direct initialization failed.",Toast.LENGTH_LONG).show();if(tab=="MORE")drawTab()}}.start()}
                onDeviceClosing={try{usbEngine.stopAudio();usbEngine.closeUsbDac()}catch(_:Exception){}}
                onDeviceDetached={
                    val wasDirect=directPlayback;val i=player?.currentMediaItemIndex?:-1
                    val pos=if(wasDirect)try{(usbEngine.getPosition()*1000.0).toLong().coerceAtLeast(0L)}catch(_:Exception){0L}else 0L
                    runOnUiThread{usbReady=false;directPlayback=false;directDurationMs=0L;if(wasDirect&&i in queue.indices){player?.seekTo(i,pos);player?.play()};Toast.makeText(this@MainActivity,"USB DAC disconnected. Android output restored.",Toast.LENGTH_SHORT).show();updatePlayButtons();if(tab=="MORE"||tab=="PLAYER")drawTab()}
                }
                onDeviceAttached={runOnUiThread{Toast.makeText(this@MainActivity,"USB audio device detected.",Toast.LENGTH_SHORT).show()}}
            }
            usbController?.scanAndRequestPermission()
        }catch(e:Exception){usbReady=false;Toast.makeText(this,"USB audio initialization unavailable: "+(e.message?:"unknown"),Toast.LENGTH_LONG).show()}
    }

    private fun resolveDirectAudioFile(uri:Uri):File? {
        var source:File?=null
        if(uri.scheme=="file")uri.path?.let{source=File(it)}
        if(source==null)try{contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DATA),null,null,null)?.use{c->if(c.moveToFirst()){val i=c.getColumnIndex(MediaStore.MediaColumns.DATA);if(i>=0)c.getString(i)?.let{source=File(it)}}}}catch(_:Exception){}
        if(source?.exists()==true&&source?.isFile==true){val ext=source!!.extension.lowercase(Locale.US);if(ext=="flac"||ext=="wav")return source}
        var name="audio"
        try{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)?:name}}catch(_:Exception){}
        if(name=="audio")name=uri.lastPathSegment?:"audio"
        val ext=name.substringAfterLast('.', "").lowercase(Locale.US)
        if(ext!="flac"&&ext!="wav")return null
        val dst=File(cacheDir,"mood_direct_"+uri.toString().hashCode()+"."+ext)
        return try{if(!dst.exists()||dst.length()==0L)contentResolver.openInputStream(uri)?.use{input->dst.outputStream().use{output->input.copyTo(output)}};dst.takeIf{it.exists()&&it.length()>0L}}catch(_:Exception){null}
    }

    private fun readAudioDuration(file:File):Long {
        return try{val r=MediaMetadataRetriever();r.setDataSource(file.absolutePath);val d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L;r.release();d}catch(_:Exception){0L}
    }

    private fun showTrackDiagnostics() {
        val details=try{
            val info=if(usbReady)usbEngine.getDacInfo() else "USB DAC not initialized"
            val log=if(usbReady)usbEngine.getLastUsbDiagnostic() else UsbAudioController.getTrace()
            val mode=if(directPlayback)"Direct USB active\nSample rate: "+usbEngine.getSampleRate()+" Hz\nSource depth: "+usbEngine.getSourceBitDepth()+" bit\nOutput depth: "+usbEngine.getNegotiatedBitDepth()+" bit\nBit-perfect integer pass: "+usbEngine.isBitPerfectIntegerPassActive() else "Direct playback inactive"
            mode+"\n\nDAC: "+info+"\n\nUSB log:\n"+log
        }catch(e:Exception){"USB diagnostic unavailable: "+e.message}
        AlertDialog.Builder(this).setTitle("USB audio diagnostics").setMessage(details).setPositiveButton("OK",null).show()
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
        val p=player?:return
        try {
            if(directPlayback)try{usbEngine.stopAudio()}catch(_:Exception){}
            directPlayback=false;directDurationMs=0L;queue.clear();queue.addAll(uris);names.clear()
            val items=uris.map{uri->try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};val name=fileName(uri);names.add(name);MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build()}
            p.setMediaItems(items);p.prepare();prefs.edit().putInt("plays",prefs.getInt("plays",0)+1).apply();lastArtworkUri=null
            if(uris.isNotEmpty())playIndex(0)else{p.pause();refreshMiniPlayer();drawTab()}
        }catch(e:Exception){Toast.makeText(this,"Could not open audio: "+(e.message?:"unknown error"),Toast.LENGTH_LONG).show()}
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
        val p=player?:return
        if(index !in queue.indices)return
        if(directPlayback)try{usbEngine.stopAudio()}catch(_:Exception){}
        directPlayback=false;directDurationMs=0L
        try{p.seekTo(index,0L);p.pause()}catch(_:Exception){}
        if(usbReady) {
            val file=resolveDirectAudioFile(queue[index])
            if(file!=null) {
                val result=try{usbEngine.playAudio(file.absolutePath)}catch(_:Exception){-99}
                if(result==0) {
                    directPlayback=true;directDurationMs=readAudioDuration(file)
                    try{equalizer?.enabled=false}catch(_:Exception){}
                    p.pause();refreshTrack();refreshMiniPlayer();updatePlayButtons()
                    if(tab=="PLAYER")drawTab()
                    return
                } else Toast.makeText(this,"Direct USB rejected (code $result). Using Android output.",Toast.LENGTH_SHORT).show()
            }
        }
        try{usbEngine.stopAudio()}catch(_:Exception){}
        directPlayback=false
        try{p.seekTo(index,0L);p.play()}catch(_:Exception){}
        try{getEq()?.enabled=true}catch(_:Exception){}
        refreshTrack();loadCurrentArtwork();refreshMiniPlayer();updatePlayButtons()
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

    private fun isAudioPlaying():Boolean {
        return if(directPlayback)try{usbEngine.isPlaying()}catch(_:Exception){false}else player?.isPlaying==true
    }

    private fun updatePlayButtons() {
        val playing=isAudioPlaying()
        if(::playButton.isInitialized)playButton.text=if(playing)"Ⅱ" else "▶"
        if(::miniPlay.isInitialized)miniPlay.text=if(playing)"Ⅱ" else "▶"
    }

    private fun toggle() {
        if(directPlayback) {
            try{if(usbEngine.isPlaying())usbEngine.pauseAudio() else if(!usbEngine.resumeAudio()){val i=player?.currentMediaItemIndex?:-1;if(i>=0)playIndex(i)}}catch(_:Exception){}
        } else {val p=player?:return;if(p.isPlaying)p.pause()else p.play()}
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
        try {
            if (usbController != null) {
                usbController?.release()
            } else {
                if (directPlayback) usbEngine.stopAudio()
                usbEngine.closeUsbDac()
            }
        } catch (_:Exception) {}
        usbController=null
        try { equalizer?.release() } catch (_:Exception) {}
        equalizer=null
        player?.release();player=null
        super.onDestroy()
    }

    private class ColorDrawableCompat(color: Int) : android.graphics.drawable.ColorDrawable(color)
}
