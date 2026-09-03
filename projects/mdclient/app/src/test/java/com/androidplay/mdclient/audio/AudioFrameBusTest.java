package com.androidplay.mdclient.audio;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class AudioFrameBusTest {
    @Test public void publishesWithoutBlockingAndReportsSlowConsumer() throws Exception {
        AudioFrameBus bus = new AudioFrameBus(1);
        AudioFrameBus.Subscription first = bus.subscribe();
        AudioFrameBus.Subscription second = bus.subscribe();
        AudioFrameBus.Frame frame = new AudioFrameBus.Frame(new short[]{1}, 0, 1, 2);
        assertEquals(0, bus.publish(frame));
        assertEquals(2, bus.publish(frame));
        assertNotNull(first.poll(10));
        first.close(); second.close();
    }
}
