package com.thelightphone.sample

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val id: String, val name: String)

val CHANNELS = listOf(
    Channel("p1", "NRK P1"),
    Channel("p2", "NRK P2"),
    Channel("p3", "NRK P3"),
)

class HomeScreenViewModel : LightViewModel<Unit>() {

    val playingId = MutableStateFlow<String?>(null)
    val paused = MutableStateFlow(false)
    val status = MutableStateFlow("Velg kanal")
    val timeText = MutableStateFlow("")
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    fun select(channel: Channel) {
        if (playingId.value == channel.id) return
        stop()
        playingId.value = channel.id
        status.value = "Kobler til ${channel.name}..."
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { findStreamUrl(channel) }
            if (playingId.value != channel.id) return@launch
            if (url == null) {
                status.value = "Fant ikke strømmen"
                playingId.value = null
            } else {
                startPlayer(url, channel)
            }
        }
    }

    private fun findStreamUrl(channel: Channel): String? {
        return try {
            val conn = URL("https://psapi.nrk.no/playback/manifest/channel/${channel.id}")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty(
                "Accept",
                "application/vnd.nrk.psapi+json; version=9; player=tv-player; device=player-core"
            )
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(text).getJSONObject("playable").getJSONArray("assets")
            var found: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("url")
                if (url.isNotEmpty()) {
                    if (found == null) found = url
                    if (asset.optString("format") == "HLS") {
                        found = url
                        break
                    }
                }
            }
            found
        } catch (e: Exception) {
            null
        }
    }

    private fun startPlayer(url: String, channel: Channel) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                it.start()
                paused.value = false
                status.value = "Spiller ${channel.name}"
                startTicker()
            }
            mp.setOnErrorListener { _, _, _ ->
                status.value = "Feil ved avspilling"
                playingId.value = null
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            status.value = "Feil ved avspilling"
            playingId.value = null
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                try {
                    val p = player ?: break
                    timeText.value = formatTime(p.currentPosition)
                } catch (e: Exception) {
                    break
                }
                delay(1000)
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val total = ms / 1000
        val minutes = total / 60
        val seconds = total % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    fun togglePause() {
        val p = player ?: return
        try {
            if (paused.value) {
                p.start()
                paused.value = false
                status.value = "Spiller"
            } else {
                p.pause()
                paused.value = true
                status.value = "Pauset"
            }
        } catch (e: Exception) {
            status.value = "Feil ved pause"
        }
    }

    fun seekBy(seconds: Int) {
        val p = player ?: return
        try {
            var target = p.currentPosition + seconds * 1000
            if (target < 0) target = 0
            val dur = p.duration
            if (dur > 0 && target > dur) target = dur
            p.seekTo(target)
        } catch (e: Exception) {
            status.value = "Kan ikke spole"
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.release()
        player = null
        playingId.value = null
        paused.value = false
        timeText.value = ""
        status.value = "Velg kanal"
    }

    override fun onCleared() {
        ticker?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel = HomeScreenViewModel()

    @Composable
    override fun Content() {
        val playingId by viewModel.playingId.collectAsState()
        val paused by viewModel.paused.collectAsState()
        val status by viewModel.status.collectAsState()
        val timeText by viewModel.timeText.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "NRK Radio",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 24.dp),
                )

                CHANNELS.forEach { channel ->
                    LightText(
                        text = channel.name,
                        variant = LightTextVariant.Heading,
                        lighten = playingId != null && playingId != channel.id,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.select(channel) }
                            .padding(vertical = 20.dp),
                    )
                }

                if (playingId != null) {
                    LightText(
                        text = if (timeText.isEmpty()) "Direkte" else "Direkte  $timeText",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        LightText(
                            text = "-15",
                            variant = LightTextVariant.Heading,
                            modifier = Modifier
                                .lightClickable { viewModel.seekBy(-15) }
                                .padding(vertical = 16.dp),
                        )
                        LightText(
                            text = if (paused) "Spill" else "Pause",
                            variant = LightTextVariant.Heading,
                            modifier = Modifier
                                .lightClickable { viewModel.togglePause() }
                                .padding(vertical = 16.dp),
                        )
                        LightText(
                            text = "+15",
                            variant = LightTextVariant.Heading,
                            modifier = Modifier
                                .lightClickable { viewModel.seekBy(15) }
                                .padding(vertical = 16.dp),
                        )
                        LightText(
                            text = "Stopp",
                            variant = LightTextVariant.Heading,
                            modifier = Modifier
                                .lightClickable { viewModel.stop() }
                                .padding(vertical = 16.dp),
                        )
                    }
                }

                LightText(
                    text = status,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
