package ru.localplayer.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class MediaFile(val name: String, val path: String)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var videoPlayer: ExoPlayer
    private lateinit var musicPlayer: ExoPlayer
    private lateinit var status: TextView
    private var baseUrl = ""
    private var videos = listOf<MediaFile>()
    private var music = listOf<MediaFile>()
    private var musicEnabled = false
    private var currentMusicIndex = -1
    private var isShowingPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        baseUrl = prefs.getString("server", "http://192.168.1.100:8080") ?: ""
        buildUi()
        videoPlayer = ExoPlayer.Builder(this).build()
        musicPlayer = ExoPlayer.Builder(this).build().apply {
            // Фоновая музыка не запрашивает аудиофокус и не должна глушиться
            // при старте/переключении видеоролика.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && musicEnabled) playNextMusic()
                }
            })
        }
        playerView.player = videoPlayer
        loadLibrary()
    }

    private fun buildUi() {
        playerView = PlayerView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(0xFF101318.toInt())
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("LOCAL PLAYER", 22f), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(action("Настройки сервера") { showServerSettings() })
        header.addView(action("Обновить") { loadLibrary() })
        header.addView(action("Музыка: выкл.") { toggleMusic(it as Button) }.also { it.tag = "music" })
        root.addView(header)
        status = label("Подключение к серверу…", 15f)
        root.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showLibrary() {
        isShowingPlayer = false
        content.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("ВИДЕО", 18f))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        videos.forEachIndexed { index, item ->
            list.addView(action(item.name) { playVideo(index) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 16f
                maxLines = 2
            }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(4) })
        }
        scroll.addView(list)
        left.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1.2f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(label("ФОНОВАЯ МУЗЫКА", 18f))
        val musicScroll = ScrollView(this)
        val musicList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        music.forEachIndexed { index, item ->
            musicList.addView(action(item.name) {
                currentMusicIndex = index
                startMusic()
                musicEnabled = true
                updateMusicButton()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 15f
                maxLines = 2
            }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(4) })
        }
        musicScroll.addView(musicList)
        right.addView(musicScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, -1, 0.8f).apply { marginStart = dp(18) })
        content.addView(row, LinearLayout.LayoutParams(-1, -1))
        list.getChildAt(0)?.requestFocus() ?: header.getChildAt(1)?.requestFocus()
    }

    private fun showPlayer() {
        isShowingPlayer = true
        content.removeAllViews()
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        val back = action("← К списку") { videoPlayer.pause(); showLibrary() }
        content.addView(back, LinearLayout.LayoutParams(-2, dp(48)))
        content.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))
        playerView.requestFocus()
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
                    status.text = "Видео: ${videos.size}   Музыка: ${music.size}"
                    showLibrary()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Не удалось подключиться. Проверьте адрес и доступность сервера."
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
        showPlayer()
    }

    private fun startMusic() {
        if (music.isEmpty() || currentMusicIndex !in music.indices) return
        val item = music[currentMusicIndex]
        musicPlayer.setMediaItem(MediaItem.fromUri(mediaUrl("music", item.path)))
        musicPlayer.prepare()
        musicPlayer.playWhenReady = true
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

    private fun toggleMusic(button: Button) {
        if (musicEnabled) {
            musicEnabled = false
            musicPlayer.pause()
        } else {
            if (music.isEmpty()) {
                Toast.makeText(this, "В папке music нет треков", Toast.LENGTH_SHORT).show()
                return
            }
            musicEnabled = true
            if (currentMusicIndex < 0) currentMusicIndex = (music.indices).random()
            if (musicPlayer.mediaItemCount == 0) startMusic() else musicPlayer.play()
        }
        updateMusicButton()
    }

    private fun updateMusicButton() {
        val button = header.findViewWithTag<Button>("music")
        button?.text = if (musicEnabled) "Музыка: вкл." else "Музыка: выкл."
    }

    private fun mediaUrl(folder: String, path: String): String {
        val encoded = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return baseUrl.trimEnd('/') + "/$folder/$encoded"
    }

    private fun showServerSettings() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(baseUrl)
            hint = "http://192.168.1.100:8080"
            textSize = 18f
        }
        AlertDialog.Builder(this)
            .setTitle("Адрес медиасервера")
            .setMessage("Введите IP-адрес и порт компьютера, например http://192.168.1.100:8080")
            .setView(input)
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

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFF1F3F5.toInt())
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun action(text: String, callback: (View) -> Unit) = Button(this).apply {
        this.text = text
        isFocusable = true
        setOnClickListener(callback)
        setTextColor(0xFFFFFFFF.toInt())
        val states = arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        )
        val colors = intArrayOf(
            0xFF3B82F6.toInt(),
            0xFF2563EB.toInt(),
            0xFF293342.toInt()
        )
        backgroundTintList = ColorStateList(states, colors)
        textSize = 14f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isShowingPlayer) {
            videoPlayer.pause()
            showLibrary()
            return true
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
