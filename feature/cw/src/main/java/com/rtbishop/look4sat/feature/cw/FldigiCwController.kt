package com.rtbishop.look4sat.feature.cw

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * fldigi CW 解码器控制器。
 * 替代 com.ve3nea.morse_expert.MainActivity，提供相同生命周期接口:
 *   onCreate / onResume / onPause / onDestroy / onPermissionGranted
 * 内部使用 AudioRecord 采集麦克风，送入 fldigi 原生解码器。
 */
class FldigiCwController {

    /** 兼容 CwSettingsDialog 对 controller.mActivity 的引用 */
    var mActivity: Activity? = null
        private set

    private var nativeHandle: Long = 0L
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var audioThread: Thread? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _decodedText = MutableStateFlow("")
    val decodedText: StateFlow<String> = _decodedText.asStateFlow()

    // 轮询解码文本
    private var pollJob: Job? = null

    /** 创建解码器, 保存 Activity 引用。rootView 暂不处理(UI 由 AndroidView 管理)。 */
    fun onCreate(activity: Activity, rootView: View?) {
        mActivity = activity
        if (nativeHandle == 0L) {
            nativeHandle = FldigiNative.create()
        }
    }

    /** 授予权限后启动录音和解码线程 */
    fun onPermissionGranted() {
        startAudioCapture()
    }

    /** 恢复录音和解码 */
    fun onResume() {
        if (hasPermission()) {
            startAudioCapture()
        }
        // 启动轮询
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val text = FldigiNative.getDecodedText(nativeHandle)
                if (text.isNotEmpty()) {
                    _decodedText.value += text
                }
                delay(100L)
            }
        }
    }

    /** 暂停录音 */
    fun onPause() {
        stopAudioCapture()
        pollJob?.cancel()
        pollJob = null
    }

    /** 销毁解码器 */
    fun onDestroy() {
        stopAudioCapture()
        pollJob?.cancel()
        if (nativeHandle != 0L) {
            FldigiNative.destroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    /** 获取当前解码文本（供 Compose 读取） */
    fun getCurrentText(): String = _decodedText.value

    /** 清除解码文本 */
    fun clearText() {
        _decodedText.value = ""
    }

    // ── 兼容 MainActivity 接口（CwDecodeScreen 顶部按钮用）──

    /** 暂停/恢复解码 */
    fun togglePause() {
        if (isRunning) {
            stopAudioCapture()
        } else {
            startAudioCapture()
        }
    }

    /** 清除解码文本（= clearText） */
    fun clearDecoded() = clearText()

    /** 保存解码文本到文件。当前简单实现: 不落盘（可选扩展） */
    fun saveText() {
        // 可选: 把 _decodedText.value 写入外部存储
    }

    /** 录音信号保存。占位实现 */
    fun recordSignals() {
        // 可选扩展
    }

    /** 返回是否处理了返回键（占位，直接返回 false 让上层导航） */
    fun handleBackPress(): Boolean = false

    // ── 内部 ──

    private fun hasPermission(): Boolean {
        val ctx = mActivity ?: return false
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startAudioCapture() {
        if (isRunning) return
        val ctx = mActivity ?: return
        if (!hasPermission()) return

        val sampleRate = 8000 // fldigi CW 解码器需要的采样率
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            return
        }

        val record = audioRecord ?: return
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            audioRecord = null
            return
        }

        record.startRecording()
        isRunning = true

        // 16-bit PCM → float 转换送入解码器
        val shortBuf = ShortArray(4096)
        val floatBuf = FloatArray(4096)
        audioThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRunning && nativeHandle != 0L) {
                val read = record.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    // 转 float [-1.0, 1.0]
                    for (i in 0 until read) {
                        floatBuf[i] = shortBuf[i].toFloat() / 32768f
                    }
                    FldigiNative.process(nativeHandle, floatBuf.copyOf(read))
                }
            }
        }.apply {
            name = "fldigi-cw-audio"
            start()
        }
    }

    private fun stopAudioCapture() {
        isRunning = false
        audioThread?.join(500)
        audioThread = null
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (_: Exception) { }
        audioRecord = null
    }
}