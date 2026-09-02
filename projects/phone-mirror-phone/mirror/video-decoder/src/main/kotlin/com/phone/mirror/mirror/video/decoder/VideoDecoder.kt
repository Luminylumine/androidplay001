package com.phone.mirror.mirror.video.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.phone.mirror.mirror.scrcpy.protocol.ScrcpyCodec
import com.phone.mirror.mirror.scrcpy.protocol.ScrcpyProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * H.264 视频解码器，输出到 [Surface]。
 *
 * 输入：scrcpy video stream 的 media packet（12 字节 BE header + 裸 H.264 NALU，不带 container）。
 *
 * 关键点：
 *  - scrcpy server 可能在运行中动态切换分辨率（session packet / SPS 变化）→ 检测并 [reconfigure]
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
     * @param scrcpyPacket scrcpy media packet（12 字节 BE header + H.264 裸码流），
     *   header 由 [ScrcpyCodec.decodeMediaPacketHeader] 定义（PTS u61 + config/keyFrame 标志 + size）。
     */
    suspend fun decodeFrame(scrcpyPacket: ByteArray) = withContext(Dispatchers.Default) {
        if (!running) return@withContext

        val decoder = codec ?: return@withContext
        if (scrcpyPacket.size < ScrcpyProtocol.PACKET_HEADER_SIZE) return@withContext

        // 12 字节 media packet header（BE）
        val header = ScrcpyCodec.decodeMediaPacketHeader(
            scrcpyPacket.sliceArray(0 until ScrcpyProtocol.PACKET_HEADER_SIZE),
        )
        val pts = header.pts

        val nalu = scrcpyPacket.sliceArray(
            ScrcpyProtocol.PACKET_HEADER_SIZE until scrcpyPacket.size,
        )

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
        // H.264 SPS: NAL header type = 7 (0x67) 紧跟 start code 00 00 00 01
        val sc = ScrcpyProtocol.H264_NALU_STARTCODE
        val idx = nalu.indexOfSequence(sc)
        if (idx < 0 || idx + 4 >= nalu.size) return false
        val naluType = nalu[idx + 4].toInt() and 0x1F
        return naluType == 7
    }

    private fun ByteArray.indexOfSequence(other: ByteArray): Int {
        for (i in 0..this.size - other.size) {
            var match = true
            for (j in other.indices) {
                if (this[i + j] != other[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
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
