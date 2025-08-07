package com.softvelum.sldp;

public class PlayRequest {
    private final int streamId;
    private final int offset;
    private final int duration;

    public PlayRequest(int streamId, int offset, int duration) {
        this.streamId = streamId;
        this.offset = offset;
        this.duration = duration;
    }

    public int getStreamId() {
        return streamId;
    }

    public int getOffset() {
        return offset;
    }

    public int getDuration() {
        return duration;
    }
}
