package com.androidplay.mdclient.transcription;

public interface TranscriptionProvider {
    interface Listener {
        void onSegment(TranscriptSegment segment);
        void onError(String code, String detail);
    }
    void start(String sessionId, Listener listener);
    void acceptAudio(short[] samples, long frameStart, long frameEnd, long audioTimeNs);
    void stop();
}
