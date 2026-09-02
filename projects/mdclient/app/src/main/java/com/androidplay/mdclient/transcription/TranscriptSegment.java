package com.androidplay.mdclient.transcription;

/** A provider-neutral transcript record; partials remain evidence until replaced by a final. */
public final class TranscriptSegment {
    public final String id;
    public final String provider;
    public final long speechStartNs;
    public final long speechEndNs;
    public final long arrivalTimeNs;
    public final String text;
    public final boolean isFinal;
    public final String replacesSegmentId;

    public TranscriptSegment(String id, String provider, long speechStartNs, long speechEndNs,
                             long arrivalTimeNs, String text, boolean isFinal, String replacesSegmentId) {
        this.id = id; this.provider = provider; this.speechStartNs = speechStartNs;
        this.speechEndNs = speechEndNs; this.arrivalTimeNs = arrivalTimeNs;
        this.text = text == null ? "" : text; this.isFinal = isFinal; this.replacesSegmentId = replacesSegmentId;
    }
}
