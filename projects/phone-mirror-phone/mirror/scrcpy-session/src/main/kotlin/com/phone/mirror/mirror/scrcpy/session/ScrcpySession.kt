package com.phone.mirror.mirror.scrcpy.session

import com.phone.mirror.core.DeviceInfo
import com.phone.mirror.core.Result
import com.phone.mirror.mirror.scrcpy.protocol.ScrcpyProtocol
import com.phone.mirror.transport.adb.core.AdbConnection
import com.phone.mirror.transport.adb.core.AdbStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * scrcpy 会话状态机。
 *
 * 生命周期：
 * ```
 * IDLE
 *  → prepare()        → ADB 连接 + 推送 scrcpy-server.jar 到设备
 *  → start()          → am startservice + open localabstract:scrcpy_xxx
 *  → connected        → videoStream / controlStream 就绪
 *  → stop()           → 关闭 stream + kill service
 *  → IDLE
 * ```
 *
 * 本类只负责会话编排；实际的视频解码在 `:mirror:video-decoder` 模块处理。
 */
class ScrcpySession(
    private val connection: AdbConnection,
    private val device: DeviceInfo,
    private val socketName: String = ScrcpyProtocol.DEFAULT_SOCKET_NAME,
) {

    enum class State { IDLE, PREPARING, CONNECTING, CONNECTED, STOPPING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    private var deviceName: String = ""

    /** 推送 scrcpy-server.jar 到设备并执行权限设置 */
    suspend fun prepare(): Result<Unit> {
        _state.value = State.PREPARING
        return runResult {
            // TODO: 把 scrcpy-server.jar push 到 /data/local/tmp/scrcpy-server.jar
            // TODO: chmod +x
        }
    }

    /**
     * 启动 scrcpy server 并连接 localabstract socket。
     * 成功返回后，video/control stream 已就绪，可以被 [VideoDecoder] 消费。
     */
    suspend fun start(): Result<ScrcpyStreams> {
        _state.value = State.CONNECTING
        val result = runResult {
            // 1. am startservice -n com.genymobile.scrcpy/.ScrcpyService
            //    --port=0   (让 server 自己挑端口写回 socket)
            // 2. open localabstract:scrcpy_${socketName} 两次（video + control）

            val video = connection.open(ScrcpyProtocol.localAbstract(socketName))
                .successOrNull() ?: error("video stream open failed")
            val control = connection.open(ScrcpyProtocol.localAbstract("${socketName}_ctrl"))
                .successOrNull() ?: error("control stream open failed")

            // 3. 读取 device name (64 bytes)
            val nameBytes = video.read(ScrcpyProtocol.DEVICE_NAME_LENGTH)
            deviceName = nameBytes.toString(Charsets.UTF_8).trim('\u0000')

            videoStream = video
            controlStream = control

            _state.value = State.CONNECTED

            ScrcpyStreams(video, control)
        }
        if (result is Result.Failure) _state.value = State.ERROR
        return result
    }

    /** 停止 scrcpy 会话 */
    suspend fun stop() {
        _state.value = State.STOPPING
        try {
            controlStream?.close()
            videoStream?.close()
            // TODO: 执行 am force-stop com.genymobile.scrcpy 杀掉 server
        } finally {
            controlStream = null
            videoStream = null
            _state.value = State.IDLE
        }
    }

    /** 发送控制包到 scrcpy control channel */
    suspend fun sendControl(payload: ByteArray) {
        val stream = controlStream ?: return
        stream.write(payload)
    }

    /** scrcpy 视频 + 控制 stream 句柄 */
    data class ScrcpyStreams(
        val video: AdbStream,
        val control: AdbStream,
    )
}
