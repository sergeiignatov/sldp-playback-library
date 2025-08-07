package com.softvelum.sldp;

public class BufferItem {
    private final byte[] data;
    private final Timestamp timestamp;
    private final boolean isKeyFrame;
    private long messageIndex;

    public BufferItem(byte[] data, long timestamp, int offset, int timescale, boolean keyFrame) {
        this.data = data;
        this.timestamp = new Timestamp(timestamp, offset, timescale);
        this.isKeyFrame = keyFrame;
        this.messageIndex = -1;
    }

    public byte[] getData() {
        return data;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public boolean isKeyFrame() {
        return isKeyFrame;
    }

    public void setMessageIndex(long index) {
        messageIndex = index;
    }

    public long getMessageIndex() {
        return messageIndex;
    }
}
