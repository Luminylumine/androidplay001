package com.phone.mirror.mirror.scrcpy.session

import com.phone.mirror.core.DeviceInfo
import com.phone.mirror.core.Result
import com.phone.mirror.core.runResult
import com.phone.mirror.core.successOrNull
import com.phone.mirror.mirror.scrcpy.protocol.ScrcpyCodec
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
     *
     * forward tunnel 握手顺序（官方协议）：
     *  1. open video socket → device 发 1 字节 dummy byte（连接探测）
     *  2. 读 codec id (4 bytes BE)：0x68323634="h264" / 0x68323635="h265"
     *  3. 读 device name (64 bytes, \0 padding)
     *  4. 读首个 12 字节 packet header —— session packet（含初始 width/height）
     *  5. open control socket → client 写 1 字节 dummy byte（forward 模式探测）
     */
    suspend fun start(): Result<ScrcpyStreams> {
        _state.value = State.CONNECTING
        val result = runResult {
            // TODO(Phase 2): 前置条件 —— server 已通过 prepare() push 并以
            //   CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server <version> ... 启动
            val video = connection.open(ScrcpyProtocol.localAbstract(socketName))
                .successOrNull() ?: error("video stream open failed")

            // 1. forward tunnel: device 的 dummy byte
            val dummy = video.read(1)
            if (dummy.isEmpty()) error("video stream closed before dummy byte")

            // 2. codec id
            val codecBytes = video.read(ScrcpyProtocol.CODEC_ID_LENGTH)
            if (codecBytes.size < ScrcpyProtocol.CODEC_ID_LENGTH) error("truncated codec id")
            val codecId = ((codecBytes[0].toLong() and 0xFF) shl 24) or
                ((codecBytes[1].toLong() and 0xFF) shl 16) or
                ((codecBytes[2].toLong() and 0xFF) shl 8) or
                (codecBytes[3].toLong() and 0xFF)
            if (codecId != ScrcpyProtocol.CODEC_ID_H264.toLong() && codecId != ScrcpyProtocol.CODEC_ID_H265.toLong()) {
                error("unsupported codec id: 0x${codecId.toString(16)}")
            }

            // 3. device name (64 bytes, \0 padding)
            val nameBytes = video.read(ScrcpyProtocol.DEVICE_NAME_LENGTH)
            deviceName = nameBytes.toString(Charsets.UTF_8).trim('\u0000')

            // 4. 首个 session packet（初始分辨率）
            val header = video.read(ScrcpyProtocol.PACKET_HEADER_SIZE)
            if (header.size < ScrcpyProtocol.PACKET_HEADER_SIZE) error("truncated session packet header")
            if (!ScrcpyCodec.isSessionPacket(header)) error("expected session packet, got media packet")
            val session = ScrcpyCodec.decodeSessionPacket(header)

            // 5. control socket + client dummy byte
            val control = connection.open(ScrcpyProtocol.localAbstract("${socketName}_ctrl"))
                .successOrNull() ?: error("control stream open failed")
            control.write(byteArrayOf(0))

            videoStream = video
            controlStream = control

            _state.value = State.CONNECTED

            ScrcpyStreams(
                video = video,
                control = control,
                codecId = codecId,
                videoWidth = session.width,
                videoHeight = session.height,
                deviceName = deviceName,
            )
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

    /** scrcpy 视频 + 控制 stream 句柄 + 会话元数据 */
    data class ScrcpyStreams(
        val video: AdbStream,
        val control: AdbStream,
        /** video codec id（[ScrcpyProtocol.CODEC_ID_H264] / [ScrcpyProtocol.CODEC_ID_H265]） */
        val codecId: Long,
        /** 初始视频尺寸（来自首个 session packet；rotation 后会再收到 session packet） */
        val videoWidth: Int,
        val videoHeight: Int,
        /** 设备名（64 字节元数据） */
        val deviceName: String,
    )
}
