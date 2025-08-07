package com.softvelum.sldp;

import android.media.MediaFormat;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.SortedMap;
import java.util.TreeMap;

import androidx.annotation.NonNull;

public class StreamBuffer {
    protected static final String TAG = "StreamBuffer";

    public interface Factory {
        @NonNull
        StreamBuffer createVideoBuffer();

        @NonNull
        StreamBuffer createAudioBuffer();
    }

    public enum Type {
        AUDIO,
        VIDEO
    }

    public enum State {
        STOP,
        PLAY
    }

    private final Type type;
    private int connectionId = C.NO_VALUE;
    private int streamId = C.NO_VALUE;
    private int timescale;
    private String stream;
    private State state = State.STOP;
    private Size size = new Size(C.NO_VALUE, C.NO_VALUE);
    private int bandwidth;
    private String mimeType;
    private byte[] extradata;

    private boolean isInitialized;

    private BufferItem[] ringBuffer;
    private final int maxItems;
    private long messageIndex;

    private Timestamp startTimestamp;
    private Timestamp endTimestamp;

    private int offset;
    private int duration;
    private int sn;

    private final TreeMap<Long, Long> steadyMap = new TreeMap<>();
    private long steadyOffset = C.NO_VALUE;

    public StreamBuffer(Type type, int capacity) {
        this.maxItems = Math.max(capacity, offset);
        this.type = type;
    }

    protected void onBufferReady() {
    }

    protected void onBufferRelease() {
    }

    protected void onProduced(BufferItem item) {
    }

    synchronized private void putItem(BufferItem item) {
        if (item == null) {
            throw new IllegalArgumentException();
        }

        if (startTimestamp == null) {
            startTimestamp = item.getTimestamp();
        }
        endTimestamp = item.getTimestamp();

        item.setMessageIndex(messageIndex);

        if (ringBuffer == null) {
            ringBuffer = new BufferItem[maxItems];
        }

        onProduced(item);

        ringBuffer[((int) (messageIndex % maxItems))] = item;
        messageIndex++;
    }

    synchronized public BufferItem getItem(long readIndex) {
        if (readIndex >= messageIndex) {
            return null;
        }
        return ringBuffer[((int) (readIndex % maxItems))];
    }

    synchronized public void writeAudioFrame(long timestamp, byte[] buffer) {
        putItem(new BufferItem(buffer, timestamp, 0, timescale, true));

        if (!isInitialized) {
            if (MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mimeType)) {
                setExtradata(Arrays.copyOfRange(buffer, 0, 4));
            }
            isInitialized = true;
            onBufferReady();
        }
    }

    synchronized public void writeH26xFrame(long timestamp, int offset, byte[] buffer, boolean keyFrame) {
        if (isInitialized || keyFrame) {
            int pos = 0;
            while (buffer.length - pos > 4) {
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    bytes[i] = buffer[pos + i];
                }
                int naluLength = ByteBuffer.wrap(bytes).getInt();
                buffer[pos] = 0;
                buffer[pos + 1] = 0;
                buffer[pos + 2] = 0;
                buffer[pos + 3] = 1;
                pos += naluLength + 4;
            }
            putItem(new BufferItem(buffer, timestamp, offset, timescale, keyFrame));
        }

        if (!isInitialized && keyFrame) {
            isInitialized = true;
            onBufferReady();
        }
    }

    public synchronized void writeVpxFrame(long timestamp, byte[] buffer, boolean keyFrame) {
        if (isInitialized || keyFrame) {
            putItem(new BufferItem(buffer, timestamp, 0, timescale, keyFrame));
        }

        if (!isInitialized && keyFrame) {
            isInitialized = true;
            onBufferReady();
        }
    }

    public void release() {
        setState(State.STOP);
        offset = 0;
        duration = 0;
        sn = C.NO_VALUE;
        isInitialized = false;
        startTimestamp = null;
        messageIndex = 0;
        ringBuffer = null;

        onBufferRelease();
    }

    public synchronized void notifySteadyTimestamp(long zeroTime, long steadyTimestamp) {
        long pts = getEndTimestamp().getPtsUs();
        if (steadyOffset < 0) {
            steadyOffset = zeroTime;
        }
        if (!steadyMap.isEmpty() && pts - steadyMap.lastKey() < 1_000_000) {
            return;
        }
        //Log.v(TAG, "Steady "+ steady_ts + " for pts "+ pts);
        steadyMap.put(pts, steadyTimestamp);
    }

    public synchronized double getDeviationForPlayTime(long playtimeUs) {
        //Log.v(TAG, "MediaTime:"+playtime_us);
        long now = System.nanoTime() / 1000;
        Long pts = steadyMap.lowerKey(playtimeUs + 1); //lowerKey returns strictly less, so increment for less or equal
        if (pts == null) {
            return 0.0;
        }
        Long steady = steadyMap.get(pts);
        SortedMap<Long, Long> toRemove = steadyMap.headMap(pts);
        toRemove.clear();

        long sincePts = playtimeUs - pts;
        long expected = steady + sincePts + steadyOffset;
        //Log.v(TAG, "Steady:"+steady +  "+"+ since_pts + " expected:" + expected);
        return (expected - now) / 1000000.0;
    }

    public Timestamp getEndTimestamp() {
        return endTimestamp;
    }

    public Timestamp getStartTimestamp() {
        return startTimestamp;
    }

    public int getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(int id) {
        connectionId = id;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String type) {
        mimeType = type;
    }

    public Size getSize() {
        return size;
    }

    public void setSize(Size size) {
        this.size = size;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public int getStreamId() {
        return streamId;
    }

    public void setStreamId(int id) {
        streamId = id;
    }

    public void setTimescale(int scale) {
        timescale = scale;
    }

    public void setExtradata(byte[] buffer) {
        extradata = buffer;
    }

    public byte[] getExtradata() {
        return extradata;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public int getBandwidth() {
        return bandwidth;
    }

    public void setBandwidth(int bandwidth) {
        this.bandwidth = bandwidth;
    }

    public boolean isVideo() {
        return type == Type.VIDEO;
    }

    public boolean isAudio() {
        return type == Type.AUDIO;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getSn() {
        return sn;
    }

    public void setSn(int sn) {
        this.sn = sn;
    }
}
