package com.androidplay.mdclient.material;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PdfMaterialInstrumentationTest {
    @Test public void extractsTextLayerFromThreePageFixture() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pdfFile = new File(context.getCacheDir(), "phase35_fourier_text_fixture.pdf");
        try (InputStream input = context.getAssets().open("phase35_fourier_text_fixture.pdf");
             OutputStream output = Files.newOutputStream(pdfFile.toPath())) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) { if (count > 0) output.write(buffer, 0, count); }
        }
        PdfMaterialController controller = new PdfMaterialController(context);
        try {
            controller.open(Uri.fromFile(pdfFile));
            assertEquals(3, controller.pageCount());
            assertTrue(controller.extractPageText(0).contains("Fourier Transform"));
            assertTrue(controller.extractPageText(2).contains("Convolution Theorem"));
            assertFalse(controller.needsOcr(0));
        } finally { controller.close(); Files.deleteIfExists(pdfFile.toPath()); }
    }
}
