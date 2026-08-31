package com.phone.mirror.mirror.video.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.phone.mirror.mirror.scrcpy.protocol.ScrcpyProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * H.264 视频解码器，输出到 [Surface]。
 *
 * 输入：scrcpy video stream 的裸 H.264 NALU（不带 container），每帧前有 8 字节 PTS header。
 *
 * 关键点：
 *  - scrcpy server 可能在运行中动态切换分辨率 → 检测 SPS/PPS 变化并 [reconfigure]
 *  - 优先选择硬编码器（MediaCodecInfo.CodecCapabilities）
 */
class VideoDecoder(
    private val surface: Surface,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
) {

    private var codec: MediaCodec? = null
    private var running = false

    /** 初始化 MediaCodec —— 可选传入初始 resolution */
    suspend fun start(initialWidth: Int = 1920, initialHeight: Int = 1080) = withContext(Dispatchers.Default) {
        val format = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight).apply {
            // 让平台自动选择合适比特率 / profile
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val decoder = MediaCodec.createDecoderByType(mimeType)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        running = true
    }

    /**
     * 处理一帧 scrcpy 视频数据。
     * @param scrcpyPacket scrcpy 视频包（8 字节 PTS header + H.264 裸码流）
     */
    suspend fun decodeFrame(scrcpyPacket: ByteArray) = withContext(Dispatchers.Default) {
        if (!running) return@withContext

        val decoder = codec ?: return@withContext

        // PTS header 解析
        val pts = scrcpyPacket
            .sliceArray(0 until ScrcpyProtocol.VIDEO_HEADER_SIZE)
            .let { ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).long }

        val nalu = scrcpyPacket.sliceArray(ScrcpyProtocol.VIDEO_HEADER_SIZE until scrcpyPacket.size)

        // 检测是否分辨率变化（SPS/PPS 带新的 width/height）→ 需要 reconfigure
        if (containsSps(nalu) && needsReconfigure(nalu)) {
            reconfigure(nalu)
        }

        // 塞入 decoder
        val inputIdx = decoder.dequeueInputBuffer(1000)
        if (inputIdx >= 0) {
            val inputBuf = decoder.getInputBuffer(inputIdx) ?: return@withContext
            inputBuf.clear()
            inputBuf.put(nalu)
            decoder.queueInputBuffer(inputIdx, 0, nalu.size, pts, 0)
        }

        // 排空输出 buffer（如果有 surface，输出端不需要 draw）
        while (isActive) {
            val info = MediaCodec.BufferInfo()
            val outputIdx = decoder.dequeueOutputBuffer(info, 0)
            if (outputIdx < 0) break
            decoder.releaseOutputBuffer(outputIdx, /*render=*/ true)
        }
    }

    /**
     * 从 SPS 中重新配置 decoder（scrcpy 可能在运行中切换分辨率）。
     */
    private suspend fun reconfigure(spsNalu: ByteArray) {
        // TODO: 解析 SPS 获取 width/height，reconfigure MediaCodec
    }

    private fun containsSps(nalu: ByteArray): Boolean {
        // H.264 SPS: NAL header type = 7 (0x67) 紧跟 start code
        val idx = nalu.indexOf(ScrcpyProtocol.H264_NALU_STARTCODE)
        if (idx < 0 || idx + 4 >= nalu.size) return false
        val naluType = nalu[idx + 4].toInt() and 0x1F
        return naluType == 7
    }

    private fun needsReconfigure(spsNalu: ByteArray): Boolean {
        // TODO: 与上一次缓存的 SPS 字节对比，判断分辨率是否变化
        return false
    }

    /** 释放 decoder 资源 */
    fun stop() {
        running = false
        try {
            codec?.stop()
        } catch (_: Throwable) { /* 已停止 */ }
        codec?.release()
        codec = null
    }
}
