package com.phone.mirror.phone.ui.mirror

import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.phone.mirror.mirror.scrcpy.session.ScrcpySession
import com.phone.mirror.mirror.video.decoder.VideoDecoder
import com.phone.mirror.phone.di.AppContainer

/**
 * Mirror 投屏界面。
 *
 * 结构：
 *  - AndroidView(SurfaceView) 承载 MediaCodec H.264 解码输出
 *  - LaunchedEffect 里串联 ScrcpySession.prepare() → start() → VideoDecoder 消费视频流
 *  - 触控事件 → ScrcpyCodec.encodeTouch() → ScrcpySession.sendControl()
 */
@Composable
fun MirrorScreen(
    container: AppContainer,
    deviceId: String,
) {
    var sessionState by remember { mutableStateOf(ScrcpySession.State.IDLE) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("投屏 · $deviceId") },
        )

        // 承载 MediaCodec Surface 的 FrameLayout
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    val surfaceView = SurfaceView(context)
                    addView(surfaceView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ))
                    // TODO: 持有 surfaceView.getHolder().surface 传给 VideoDecoder
                }
            },
        )

        // 状态覆盖层
        when {
            sessionState == ScrcpySession.State.CONNECTING -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage!!,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // TODO: LaunchedEffect 里串联 ScrcpySession → VideoDecoder
    // 当 Surface 就绪后：
    //   val session = container.scrcpySessionFactory(connection, device)
    //   session.prepare()
    //   val streams = session.start()
    //   val decoder = VideoDecoder(surface)
    //   decoder.start()
    //   while (streams.video.isActive) decoder.decodeFrame(videoStream.readChunk())
}
