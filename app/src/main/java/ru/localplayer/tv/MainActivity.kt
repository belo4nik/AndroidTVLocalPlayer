package ru.localplayer.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class MediaFile(val name: String, val path: String)

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var libraryContainer: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var nowPlayingLabel: TextView

    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var playerOverlayBar: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var resizeButton: Button
    private lateinit var playerMusicButton: Button

    private lateinit var videoPlayer: ExoPlayer
    private lateinit var musicPlayer: ExoPlayer

    private var baseUrl = ""
    private var videos = listOf<MediaFile>()
    private var music = listOf<MediaFile>()
    private var musicEnabled = false
    private var currentMusicIndex = -1
    private var isShowingPlayer = false

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Вписать",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Заполнить",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Растянуть"
    )
    private var resizeModeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        baseUrl = prefs.getString("server", "http://192.168.1.100:8080") ?: ""
        resizeModeIndex = prefs.getInt("resize_mode", 0).coerceIn(resizeModes.indices)

        buildUi()

        videoPlayer = ExoPlayer.Builder(this).build()
        musicPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && musicEnabled) playNextMusic()
                }
            })
        }

        playerView.player = videoPlayer
        applyResizeMode()
        loadLibrary()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B0F17.toInt(), 0xFF131B29.toInt())
            )
            clipChildren = false
            clipToPadding = false
        }

        // --- ЭКРАН БИБЛИОТЕКИ ---
        libraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            clipChildren = false
            clipToPadding = false
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = roundedBox(0xFF151D2B.toInt(), 0xFF233044.toInt(), 16)
            clipChildren = false
            clipToPadding = false
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appTitle = TextView(this).apply {
            text = "▶  LOCAL PLAYER TV"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            letterSpacing = 0.04f
        }
        status = TextView(this).apply {
            text = "Подключение к серверу…"
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
        }
        nowPlayingLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF38BDF8.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        titleBlock.addView(appTitle)
        titleBlock.addView(status)
        titleBlock.addView(nowPlayingLabel)

        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pillButton("⚙ Сервер") { showServerSettings() }, marginRight(10))
        header.addView(pillButton("↻ Обновить") { loadLibrary() }, marginRight(10))
        header.addView(pillButton("♫ Музыка: Выкл") { toggleMusic() }.also { it.tag = "music" })

        libraryContainer.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(18)
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        libraryContainer.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- ПОЛНОЭКРАННЫЙ ПЛЕЕР ---
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 3500
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                playerOverlayBar.visibility = visibility
            })
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        playerOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD9000000.toInt(), 0x00000000)
            )
            clipChildren = false
            clipToPadding = false
        }

        val backBtn = pillButton("← К списку") {
            videoPlayer.pause()
            showLibrary()
        }
        playerTitle = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), 0, dp(16), 0)
        }
        resizeButton = pillButton("⛶ Экран: Вписать") {
            cycleResizeMode()
            playerView.showController()
        }
        playerMusicButton = pillButton("♫ Музыка: Выкл") {
            toggleMusic()
            playerView.showController()
        }

        listOf(backBtn, resizeButton, playerMusicButton).forEach { btn ->
            val oldListener = btn.onFocusChangeListener
            btn.setOnFocusChangeListener { v, hasFocus ->
                oldListener?.onFocusChange(v, hasFocus)
                if (hasFocus) playerView.showController()
            }
        }

        playerOverlayBar.addView(backBtn)
        playerOverlayBar.addView(playerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        playerOverlayBar.addView(resizeButton, marginRight(10))
        playerOverlayBar.addView(playerMusicButton)

        playerContainer.addView(
            playerOverlayBar,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        )

        root.addView(libraryContainer, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerContainer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showLibrary() {
        isShowingPlayer = false
        setImmersiveMode(false)
        playerContainer.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE

        content.removeAllViews()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        // Левая колонка: ВИДЕО
        val left = createColumnPanel("ВИДЕО", videos.size)
        val videoScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        val videoList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
            clipChildren = false
            clipToPadding = false
        }

        if (videos.isEmpty()) {
            videoList.addView(emptyHint("Нет видеофайлов или сервер недоступен"))
        } else {
            videos.forEachIndexed { index, item ->
                val card = mediaCard("▶", item.name, isVideo = true) { playVideo(index) }
                videoList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        videoScroll.addView(videoList)
        left.addView(videoScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1.3f))

        // Правая колонка: ФОНОВАЯ МУЗЫКА
        val right = createColumnPanel("ФОНОВАЯ МУЗЫКА", music.size)
        val musicScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        val musicList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
            clipChildren = false
            clipToPadding = false
        }

        if (music.isEmpty()) {
            musicList.addView(emptyHint("В папке music нет треков"))
        } else {
            music.forEachIndexed { index, item ->
                val card = mediaCard("♪", item.name, isVideo = false) {
                    currentMusicIndex = index
                    musicEnabled = true
                    startMusic()
                    updateMusicUi()
                }
                musicList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        musicScroll.addView(musicList)
        right.addView(musicScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, -1, 0.9f).apply {
            marginStart = dp(18)
        })

        content.addView(row, LinearLayout.LayoutParams(-1, -1))
        if (videos.isNotEmpty()) {
            videoList.getChildAt(0)?.requestFocus()
        } else {
            header.getChildAt(1)?.requestFocus()
        }
    }

    private fun showPlayer(title: String) {
        isShowingPlayer = true
        setImmersiveMode(true)
        playerTitle.text = title
        libraryContainer.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        playerOverlayBar.visibility = View.VISIBLE
        playerView.showController()
        playerView.requestFocus()
    }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
        prefs.edit { putInt("resize_mode", resizeModeIndex) }
        applyResizeMode()
        val (_, label) = resizeModes[resizeModeIndex]
        Toast.makeText(this, "Режим экрана: $label", Toast.LENGTH_SHORT).show()
    }

    private fun applyResizeMode() {
        val (mode, label) = resizeModes[resizeModeIndex]
        playerView.resizeMode = mode
        resizeButton.text = "⛶ Экран: $label"
    }

    @Suppress("DEPRECATION")
    private fun setImmersiveMode(enabled: Boolean) {
        if (enabled) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun loadLibrary() {
        status.text = "Подключение: $baseUrl"
        io.execute {
            try {
                val newVideos = fetchList("/api/videos")
                val newMusic = fetchList("/api/music")
                runOnUiThread {
                    videos = newVideos
                    music = newMusic
                    status.text = "Сервер: $baseUrl  •  Видео: ${videos.size}  •  Музыка: ${music.size}"
                    showLibrary()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Нет связи с $baseUrl — проверьте настройки сервера"
                    Toast.makeText(this, e.message ?: "Ошибка подключения", Toast.LENGTH_LONG).show()
                    showLibrary()
                }
            }
        }
    }

    private fun fetchList(path: String): List<MediaFile> {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 10000
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(text)
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                MediaFile(obj.optString("name"), obj.optString("path"))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun playVideo(index: Int) {
        val item = videos.getOrNull(index) ?: return
        val url = mediaUrl("videos", item.path)
        videoPlayer.setMediaItem(MediaItem.fromUri(url))
        videoPlayer.prepare()
        videoPlayer.playWhenReady = true
        showPlayer(item.name)
    }

    private fun startMusic() {
        if (music.isEmpty() || currentMusicIndex !in music.indices) return
        val item = music[currentMusicIndex]
        musicPlayer.setMediaItem(MediaItem.fromUri(mediaUrl("music", item.path)))
        musicPlayer.prepare()
        musicPlayer.playWhenReady = true
        updateMusicUi()
    }

    private fun playNextMusic() {
        if (music.isEmpty()) return
        val next = if (music.size == 1) 0 else {
            var n = (0 until music.size).random()
            while (n == currentMusicIndex) n = (0 until music.size).random()
            n
        }
        currentMusicIndex = next
        startMusic()
    }

    private fun toggleMusic() {
        if (musicEnabled) {
            musicEnabled = false
            musicPlayer.pause()
        } else {
            if (music.isEmpty()) {
                Toast.makeText(this, "В папке music нет треков", Toast.LENGTH_SHORT).show()
                return
            }
            musicEnabled = true
            if (currentMusicIndex < 0) currentMusicIndex = music.indices.random()
            if (musicPlayer.mediaItemCount == 0) startMusic() else musicPlayer.play()
        }
        updateMusicUi()
    }

    private fun updateMusicUi() {
        val label = if (musicEnabled) "♫ Музыка: Вкл" else "♫ Музыка: Выкл"
        header.findViewWithTag<Button>("music")?.text = label
        playerMusicButton.text = label
        val currentTrack = music.getOrNull(currentMusicIndex)?.name
        if (musicEnabled && currentTrack != null) {
            nowPlayingLabel.text = "♪ Сейчас играет: $currentTrack"
            nowPlayingLabel.visibility = View.VISIBLE
        } else {
            nowPlayingLabel.visibility = View.GONE
        }
    }

    private fun mediaUrl(folder: String, path: String): String {
        val encoded = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return baseUrl.trimEnd('/') + "/$folder/$encoded"
    }

    private fun showServerSettings() {
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(baseUrl)
            hint = "http://192.168.1.100:8080"
            textSize = 18f
            setTextColor(0xFFF8FAFC.toInt())
            setHintTextColor(0xFF64748B.toInt())
        }
        container.addView(input)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Адрес медиасервера")
            .setMessage("Введите IP-адрес и порт компьютера, например:\nhttp://192.168.1.100:8080")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                var value = input.text.toString().trim().trimEnd('/')
                if (value.isNotBlank() && !value.startsWith("http://") && !value.startsWith("https://")) {
                    value = "http://$value"
                }
                if (value.isNotBlank()) {
                    baseUrl = value
                    prefs.edit { putString("server", baseUrl) }
                    loadLibrary()
                }
            }.show()
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ UI-КОМПОНЕНТЫ ---

    private fun createColumnPanel(title: String, count: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBox(0xFF121926.toInt(), 0xFF1E293B.toInt(), 16)
            clipChildren = false
            clipToPadding = false

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), dp(12))
            }
            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFE2E8F0.toInt())
                letterSpacing = 0.06f
            }
            val badge = TextView(this@MainActivity).apply {
                text = count.toString()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF38BDF8.toInt())
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = roundedBox(0xFF17253B.toInt(), 0xFF1E3A5F.toInt(), 20)
            }
            headerRow.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            headerRow.addView(badge)
            addView(headerRow)
        }
    }

    private fun mediaCard(icon: String, title: String, isVideo: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = selectableCardBg(radiusDp = 12)
            setOnClickListener { onClick() }
            attachFocusScale(this)

            val iconBadge = TextView(this@MainActivity).apply {
                text = icon
                textSize = if (isVideo) 14f else 15f
                gravity = Gravity.CENTER
                setTextColor(if (isVideo) 0xFF38BDF8.toInt() else 0xFFA78BFA.toInt())
                background = roundedBox(
                    if (isVideo) 0xFF17253B.toInt() else 0xFF241D3A.toInt(),
                    Color.TRANSPARENT,
                    10
                )
            }
            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = if (isVideo) 16f else 15f
                setTextColor(0xFFF1F5F9.toInt())
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(iconBadge, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(12)
            })
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun pillButton(text: String, callback: (View) -> Unit) = Button(this).apply {
        this.text = text
        isFocusable = true
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFF8FAFC.toInt())
        setPadding(dp(16), dp(8), dp(16), dp(8))
        minHeight = dp(42)
        minimumHeight = dp(42)
        background = selectableButtonBg(radiusDp = 10)
        setOnClickListener(callback)
        attachFocusScale(this)
    }

    private fun emptyHint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF64748B.toInt())
        setPadding(dp(8), dp(24), dp(8), dp(24))
    }

    private fun roundedBox(fillColor: Int, strokeColor: Int, radiusDp: Int, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(strokeDp), strokeColor)
            }
        }
    }

    private fun selectableCardBg(radiusDp: Int): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                roundedBox(0xFF1E2D45.toInt(), 0xFF38BDF8.toInt(), radiusDp, strokeDp = 2)
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBox(0xFF2563EB.toInt(), 0xFF60A5FA.toInt(), radiusDp, strokeDp = 2)
            )
            addState(
                intArrayOf(),
                roundedBox(0xFF1A2233.toInt(), 0xFF243044.toInt(), radiusDp, strokeDp = 1)
            )
        }
    }

    private fun selectableButtonBg(radiusDp: Int): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                roundedBox(0xFF2563EB.toInt(), 0xFF93C5FD.toInt(), radiusDp, strokeDp = 2)
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBox(0xFF1D4ED8.toInt(), 0xFF93C5FD.toInt(), radiusDp, strokeDp = 2)
            )
            addState(
                intArrayOf(),
                roundedBox(0xFF1E293B.toInt(), 0xFF334155.toInt(), radiusDp, strokeDp = 1)
            )
        }
    }

    private fun attachFocusScale(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(110)
                .start()
        }
    }

    private fun marginRight(dpVal: Int) = LinearLayout.LayoutParams(-2, -2).apply {
        marginEnd = dp(dpVal)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isShowingPlayer) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                videoPlayer.pause()
                showLibrary()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_MENU) {
                playerView.showController()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        videoPlayer.release()
        musicPlayer.release()
        io.shutdownNow()
        super.onDestroy()
    }
}
