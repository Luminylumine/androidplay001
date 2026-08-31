package com.phone.mirror.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.phone.mirror.phone.ui.nav.AppNavHost
import com.phone.mirror.phone.ui.theme.PhoneMirrorTheme

/**
 * 应用主 Activity。整个 App 只有这一个 Activity，所有界面通过 Compose NavHost 切换。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as AppApplication).container

        setContent {
            PhoneMirrorTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(container = container)
                }
            }
        }
    }
}

/** 顶层空组件占位 —— 给 Theme preview 用 */
@Composable
fun EmptyPreview() {
    PhoneMirrorTheme { Surface {} }
}
