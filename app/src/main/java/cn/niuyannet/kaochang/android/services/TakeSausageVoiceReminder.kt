package cn.niuyannet.kaochang.android.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object TakeSausageVoiceReminder {
    private const val LOG_PREFIX = "【取肠语音】"
    private const val RAW_RESOURCE_NAME = "take_sausage_reminder"
    private const val PLAYER_VOLUME = 1.0f
    private const val PLAYBACK_TIMEOUT_MS = 10_000L

    private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playing = AtomicBoolean(false)
    private val playbackRegistry = TakeSausageVoicePlaybackRegistry()
    private var currentSession: PlaybackSession? = null
    private var timeoutJob: Job? = null

    fun play() {
        val requestedWhilePlaying = !playing.compareAndSet(false, true)
        reminderScope.launch {
            try {
                if (requestedWhilePlaying || currentSession != null || playbackRegistry.hasActive()) {
                    LogUtils.d("$LOG_PREFIX 已有取肠提醒正在播放，新请求覆盖旧播放")
                    releaseCurrent("replaced_by_new_request")
                    playing.set(true)
                }
                playOnMainThread()
            } catch (t: Throwable) {
                LogUtils.e("$LOG_PREFIX 播放协程异常：message=${t.message ?: t.javaClass.simpleName}", t)
                releaseCurrent("coroutine_exception")
            }
        }
    }

    private fun playOnMainThread() {
        val context = MyApp.instance().applicationContext
        val resId = context.resources.getIdentifier(
            RAW_RESOURCE_NAME,
            "raw",
            context.packageName
        )
        if (resId == 0) {
            LogUtils.w("$LOG_PREFIX 未找到音频资源：raw/$RAW_RESOURCE_NAME，跳过播放")
            playing.set(false)
            return
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusHandle = requestAudioFocus(context, audioAttributes)
        val player = MediaPlayer()
        val token = playbackRegistry.begin()
        currentSession = PlaybackSession(token, player, focusHandle)
        try {
            val afd = context.resources.openRawResourceFd(resId)
            if (afd == null) {
                LogUtils.w("$LOG_PREFIX 打开音频资源失败：raw/$RAW_RESOURCE_NAME")
                releasePlayer(token, player, focusHandle, "open_raw_resource_failed")
                return
            }
            afd.use {
                player.setAudioAttributes(audioAttributes)
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.setVolume(PLAYER_VOLUME, PLAYER_VOLUME)
            player.setOnPreparedListener {
                if (!playbackRegistry.isActive(token)) {
                    releaseStalePlayer(it, focusHandle, "prepared_after_replaced")
                    return@setOnPreparedListener
                }
                try {
                    it.start()
                    scheduleTimeout(token)
                    LogUtils.i("$LOG_PREFIX 已开始播放取肠提醒：resource=raw/$RAW_RESOURCE_NAME，volume=$PLAYER_VOLUME")
                } catch (t: Throwable) {
                    LogUtils.e("$LOG_PREFIX 启动播放失败：message=${t.message ?: t.javaClass.simpleName}", t)
                    releasePlayer(token, it, focusHandle, "start_failed")
                }
            }
            player.setOnCompletionListener {
                LogUtils.d("$LOG_PREFIX 取肠提醒播放完成")
                releasePlayer(token, it, focusHandle, "completed")
            }
            player.setOnErrorListener { mp, what, extra ->
                LogUtils.e("$LOG_PREFIX 播放失败：what=$what，extra=$extra")
                releasePlayer(token, mp, focusHandle, "error_what_${what}_extra_$extra")
                true
            }
            player.prepareAsync()
        } catch (t: Throwable) {
            LogUtils.e("$LOG_PREFIX 初始化播放失败：message=${t.message ?: t.javaClass.simpleName}", t)
            releasePlayer(token, player, focusHandle, "init_failed")
        }
    }

    private fun scheduleTimeout(token: Long) {
        timeoutJob?.cancel()
        timeoutJob = reminderScope.launch {
            delay(PLAYBACK_TIMEOUT_MS)
            val session = currentSession
            if (session?.token == token && playbackRegistry.isActive(token)) {
                LogUtils.w("$LOG_PREFIX 播放超时兜底释放：timeoutMs=$PLAYBACK_TIMEOUT_MS")
                releasePlayer(token, session.player, session.focusHandle, "timeout")
            }
        }
    }

    private fun requestAudioFocus(
        context: Context,
        audioAttributes: AudioAttributes
    ): AudioFocusHandle? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            LogUtils.w("$LOG_PREFIX 获取 AudioManager 失败，继续尝试播放")
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            val result = audioManager.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                AudioFocusHandle(audioManager, request)
            } else {
                LogUtils.w("$LOG_PREFIX 音频焦点申请失败，继续尝试播放：result=$result")
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                AudioFocusHandle(audioManager, null)
            } else {
                LogUtils.w("$LOG_PREFIX 音频焦点申请失败，继续尝试播放：result=$result")
                null
            }
        }
    }

    private fun releaseCurrent(reason: String) {
        val session = currentSession
        if (session == null) {
            timeoutJob?.cancel()
            timeoutJob = null
            playbackRegistry.clearActive()
            playing.set(false)
            return
        }
        releasePlayer(session.token, session.player, session.focusHandle, reason)
    }

    private fun releasePlayer(
        token: Long,
        player: MediaPlayer,
        focusHandle: AudioFocusHandle?,
        reason: String
    ) {
        val active = playbackRegistry.finishIfActive(token)
        if (active) {
            timeoutJob?.cancel()
            timeoutJob = null
            if (currentSession?.token == token) {
                currentSession = null
            }
            abandonAudioFocus(focusHandle)
            playing.set(false)
            LogUtils.d("$LOG_PREFIX 播放资源已释放：reason=$reason")
        } else {
            LogUtils.d("$LOG_PREFIX 忽略过期播放回调：reason=$reason，token=$token")
        }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun releaseStalePlayer(
        player: MediaPlayer,
        focusHandle: AudioFocusHandle?,
        reason: String
    ) {
        runCatching { player.reset() }
        runCatching { player.release() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusHandle?.request is AudioFocusRequest) {
            abandonAudioFocus(focusHandle)
        }
        LogUtils.d("$LOG_PREFIX 释放过期播放器：reason=$reason")
    }

    private fun abandonAudioFocus(focusHandle: AudioFocusHandle?) {
        if (focusHandle == null) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusHandle.request is AudioFocusRequest) {
            focusHandle.audioManager.abandonAudioFocusRequest(focusHandle.request)
        } else {
            @Suppress("DEPRECATION")
            focusHandle.audioManager.abandonAudioFocus(null)
        }
    }

    private data class AudioFocusHandle(
        val audioManager: AudioManager,
        val request: Any?
    )

    private data class PlaybackSession(
        val token: Long,
        val player: MediaPlayer,
        val focusHandle: AudioFocusHandle?
    )
}
