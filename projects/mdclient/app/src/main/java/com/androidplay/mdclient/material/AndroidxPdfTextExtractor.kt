package com.androidplay.mdclient.material

import android.content.Context
import android.net.Uri
import androidx.pdf.SandboxedPdfLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Uses AndroidX PDF's sandboxed document service for text-layer extraction. */
object AndroidxPdfTextExtractor {
    @JvmStatic
    fun extract(context: Context, uri: Uri?, pageIndex: Int): String = runBlocking(Dispatchers.IO) {
        if (uri == null) return@runBlocking ""
        val loader = SandboxedPdfLoader(context.applicationContext, Dispatchers.IO)
        val document = loader.openDocument(uri)
        try {
            val content = document.getPageContent(pageIndex) ?: return@runBlocking ""
            content.textContents.joinToString("\n") { it.text }
        } finally {
            document.close()
        }
    }
}
