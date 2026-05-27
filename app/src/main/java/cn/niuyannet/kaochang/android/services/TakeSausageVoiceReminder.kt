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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object TakeSausageVoiceReminder {
    private const val LOG_PREFIX = "【取肠语音】"
    private const val RAW_RESOURCE_NAME = "take_sausage_reminder"
    private const val PLAYER_VOLUME = 1.0f

    private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playing = AtomicBoolean(false)

    fun play() {
        if (!playing.compareAndSet(false, true)) {
            LogUtils.d("$LOG_PREFIX 已有取肠提醒正在播放，跳过本次重复请求")
            return
        }
        reminderScope.launch {
            try {
                playOnMainThread()
            } catch (t: Throwable) {
                LogUtils.e("$LOG_PREFIX 播放协程异常：message=${t.message ?: t.javaClass.simpleName}", t)
                playing.set(false)
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
        try {
            val afd = context.resources.openRawResourceFd(resId)
            if (afd == null) {
                LogUtils.w("$LOG_PREFIX 打开音频资源失败：raw/$RAW_RESOURCE_NAME")
                releasePlayer(player, focusHandle)
                return
            }
            afd.use {
                player.setAudioAttributes(audioAttributes)
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.setVolume(PLAYER_VOLUME, PLAYER_VOLUME)
            player.setOnPreparedListener {
                try {
                    it.start()
                    LogUtils.i("$LOG_PREFIX 已开始播放取肠提醒：resource=raw/$RAW_RESOURCE_NAME，volume=$PLAYER_VOLUME")
                } catch (t: Throwable) {
                    LogUtils.e("$LOG_PREFIX 启动播放失败：message=${t.message ?: t.javaClass.simpleName}", t)
                    releasePlayer(it, focusHandle)
                }
            }
            player.setOnCompletionListener {
                LogUtils.d("$LOG_PREFIX 取肠提醒播放完成")
                releasePlayer(it, focusHandle)
            }
            player.setOnErrorListener { mp, what, extra ->
                LogUtils.e("$LOG_PREFIX 播放失败：what=$what，extra=$extra")
                releasePlayer(mp, focusHandle)
                true
            }
            player.prepareAsync()
        } catch (t: Throwable) {
            LogUtils.e("$LOG_PREFIX 初始化播放失败：message=${t.message ?: t.javaClass.simpleName}", t)
            releasePlayer(player, focusHandle)
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

    private fun releasePlayer(player: MediaPlayer, focusHandle: AudioFocusHandle?) {
        runCatching { player.reset() }
        runCatching { player.release() }
        abandonAudioFocus(focusHandle)
        playing.set(false)
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
}
