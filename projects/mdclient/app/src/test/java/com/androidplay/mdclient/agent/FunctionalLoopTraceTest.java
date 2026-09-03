package com.androidplay.mdclient.agent;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FunctionalLoopTraceTest {
    @Test public void emitsRequiredFunctionalSequenceAsJsonl() {
        FunctionalLoopTrace trace = new FunctionalLoopTrace()
                .add("PAGE_CHANGED", 1, "page=0")
                .add("TRANSCRIPT_FINAL", 2, "傅里叶变换 Fourier Transform")
                .add("HUMAN_EDIT", 3, "block=b1 revision=2")
                .add("FAST_AGENT", 4, "suggestion")
                .add("SLOW_AGENT", 5, "historical update")
                .add("DOCUMENT_UPDATE", 6, "block=b1 revision=3");
        assertEquals(6, trace.entries().size());
        assertTrue(trace.toJsonl().contains("TRANSCRIPT_FINAL"));
        assertTrue(trace.toJsonl().contains("傅里叶变换 Fourier Transform"));
    }
}
