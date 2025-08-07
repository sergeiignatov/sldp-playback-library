package com.softvelum.sldp;

import android.media.MediaFormat;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.softvelum.sldp.SldpConnection.FrameType.BINARY;
import static com.softvelum.sldp.SldpConnection.FrameType.TEXT;
import static com.softvelum.sldp.SldpConnection.FrameType.UNKNOWN;
import static com.softvelum.sldp.SldpConnection.SldpConnectionState.STATUS;

public class SldpConnection extends TcpConnection {

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(\\d+)x(\\d+)");

    private static final int WEB_AAC_SEQUENCE_HEADER = 0;
    private static final int WEB_AAC_FRAME = 1;
    private static final int WEB_AVC_SEQUENCE_HEADER = 2;
    private static final int WEB_AVC_KEY_FRAME = 3;
    private static final int WEB_AVC_FRAME = 4;
    private static final int WEB_HEVC_SEQUENCE_HEADER = 5;
    private static final int WEB_HEVC_KEY_FRAME = 6;
    private static final int WEB_HEVC_FRAME = 7;
    private static final int WEB_VP6_KEY_FRAME = 8;
    private static final int WEB_VP6_FRAME = 9;
    private static final int WEB_VP8_KEY_FRAME = 10;
    private static final int WEB_VP8_FRAME = 11;
    private static final int WEB_VP9_KEY_FRAME = 12;
    private static final int WEB_VP9_FRAME = 13;
    private static final int WEB_MP3_FRAME = 14;
    private static final int WEB_OPUS_FRAME = 15;
    private static final int WEB_AV1_SEQUENCE_HEADER = 16;
    private static final int WEB_AV1_KEY_FRAME = 17;
    private static final int WEB_AV1_FRAME = 18;

    enum SldpConnectionState {
        INITIAL,
        HANDSHAKE,
        STATUS,
        PLAY,
        CLOSED
    }

    enum FrameType {
        UNKNOWN,
        TEXT,
        BINARY
    }

    private SldpConnectionState state = SldpConnectionState.INITIAL;
    private Status status = Status.CONN_FAIL;

    private final String app;
    private final String stream;

    private int id;
    private int sn;

    private final Map<Integer, StreamBuffer> streamIdMap = new HashMap<>();
    private final Map<Integer, StreamBuffer> streamSnMap = new HashMap<>();

    private final HttpParser parser = new HttpParser();

    private final ByteBuffer frameBuffer = ByteBuffer.allocate(1024 * 1024);
    private FrameType frameType = UNKNOWN;

    private long steadyTimestamp;
    private long systemTimestamp;
    private final long playbackDelay;
    private long zeroTime;

    public SldpConnection(Config config,
                          Selector selector,
                          StreamBuffer.Factory bufferFactory,
                          Connection.Listener listener) throws IOException {
        super(config, selector, bufferFactory, listener);

        app = config.app;
        stream = config.stream;
        playbackDelay = config.steadyDelayMs * 1000L;
    }

    @Override
    public void onConnect() {
        status = Status.UNKNOWN_FAIL;
        notifyOnStateChange(State.CONNECTED, Status.SUCCESS);
        sendUpgradeRequest();
        state = SldpConnectionState.HANDSHAKE;
        status = Status.CONN_FAIL;
        steadyTimestamp = C.NO_VALUE;
        systemTimestamp = C.NO_VALUE;
    }

    private void sendUpgradeRequest() {
        try {
            append(String.format("GET /%s/%s HTTP/1.1\r\n", app, stream));
            append("Upgrade: websocket\r\n");
            append("Connection: Upgrade\r\n");
            append(String.format(Locale.ENGLISH, "Host: %s:%d\r\n", host, port));
            append("Origin: http://dev.wmspanel.com\r\n");
            append("Sec-WebSocket-Protocol: sldp.softvelum.com\r\n");
            append("Pragma: no-cache\r\n");
            append("Sec-WebSocket-Key: MYnDFVtBIiNR1eIQ5NNvmA==\r\n");
            append("Sec-WebSocket-Version: 13\r\n");
            append("Sec-WebSocket-Extensions: x-webkit-deflate-frame\r\n");
            append(String.format("User-Agent: %s\r\n", TextUtils.isEmpty(userAgent) ? "SLDPLib/1.0" : userAgent));
            send("\r\n");
        } catch (Exception e) {
            close();
        }
    }

    @Override
    public void onRecv(ByteBuffer byteBuffer) {
        int bytesParsed;

        switch (state) {
            case HANDSHAKE:
                bytesParsed = parser.parse(byteBuffer.array(), byteBuffer.limit());
                if (bytesParsed < 0) {
                    close();
                    return;// byteBuffer.position();
                }
                if (!parser.getComplete()) {
                    // incomplete
                    return; // bytesParsed;
                }
                if (101 != parser.getStatusCode()) {
                    status = Status.HANDSHAKE_FAIL;
                    try {
                        putError(statusCode, parser.getStatusCode());
                        putError(statusText, parser.getStatusText());
                    } catch (JSONException ignored) {
                    }
                    close();
                    return; // byteBuffer.position();
                }
                byteBuffer.position(bytesParsed);
                state = STATUS;

            case STATUS:
            case PLAY:
                while (byteBuffer.remaining() > 0) {
                    bytesParsed = processServerMessage(byteBuffer);
                    if (bytesParsed < 0) {
                        close();
                        return; // byteBuffer.position();
                    }
                    if (bytesParsed == 0) {
                        break;
                    }
                    byteBuffer.position(byteBuffer.position() + bytesParsed);
                }
                break;

            case CLOSED:
            default:
                break;
        }
    }

    private int processServerMessage(ByteBuffer byteBuffer) {

        int offset = byteBuffer.position();

        int hdr_len = 2;

        if (byteBuffer.remaining() < hdr_len) {
            return 0;
        }

        if ((byteBuffer.get(offset + 1) & 0x80) != 0) {
            // mask
            return -1;
        }

        int payload_len = byteBuffer.get(offset + 1) & 0x7F;
        if (payload_len == 126) {
            // 16 bit Extended payload length
            hdr_len += 2;

            if (byteBuffer.remaining() < hdr_len) {
                return 0;
            }
            payload_len = ((byteBuffer.get(offset + 2) & 0xFF) << 8) | (byteBuffer.get(offset + 3) & 0xFF);

        } else if (payload_len == 127) {
            return -1;
        }

        //Log.v(TAG, "payload_len=" + payload_len);
        if (hdr_len + payload_len > byteBuffer.remaining()) {
            // incomplete frame
            //Log.v(TAG, "incomplete frame");
            return 0;
        }

        int opcode = byteBuffer.get(offset) & 0xF;
        switch (opcode) {
            case 0x0:
                // continuation frame
                try {
                    frameBuffer.put(byteBuffer.array(), offset + hdr_len, payload_len);
                } catch (Exception e) {
                    return -1;
                }
                break;

            case 0x1:
                // text frame
                frameType = TEXT;
                frameBuffer.clear();

                try {
                    frameBuffer.put(byteBuffer.array(), offset + hdr_len, payload_len);
                } catch (Exception e) {
                    return -1;
                }
                break;

            case 0x2:
                // binary frame
                frameType = BINARY;
                frameBuffer.clear();

                try {
                    frameBuffer.put(byteBuffer.array(), offset + hdr_len, payload_len);
                } catch (Exception e) {
                    return -1;
                }
                break;

            case 0x8:
                // connection close
                close();
                return hdr_len + payload_len;

            case 0x9:
                // ping
                return hdr_len + payload_len;

            case 0xA:
                // pong
                return hdr_len + payload_len;

            default:
                break;
        }

        if ((byteBuffer.get(offset) & 0x80) != 0) {
            // fin
            frameBuffer.flip();

            switch (frameType) {
                case TEXT:
                    String text;
                    try {
                        text = new String(frameBuffer.array(), StandardCharsets.US_ASCII);
                        processTextMessage(text);
                    } catch (Exception e) {
                        close();
                        return -1;
                    }
                    break;

                case BINARY:
                    processBinaryMessage(frameBuffer);
                    break;

                default:
                    break;
            }

            frameBuffer.clear();
            frameType = UNKNOWN;
        }

        return hdr_len + payload_len;
    }

    private void processBinaryMessage(ByteBuffer buffer) {

        int offset = 0;

        int sn = buffer.get(0);
        int type = buffer.get(1);
        offset += 2;

        long timestamp = C.NO_VALUE;
        long steady = C.NO_VALUE;

        if (type != WEB_AAC_SEQUENCE_HEADER &&
                type != WEB_AVC_SEQUENCE_HEADER &&
                type != WEB_HEVC_SEQUENCE_HEADER &&
                type != WEB_AV1_SEQUENCE_HEADER) {

            if (buffer.limit() < offset + 8) {
                return;
            }

            timestamp = (((long) buffer.get(offset) & 0xFF) << 56) |
                    (((long) buffer.get(offset + 1) & 0xFF) << 48) |
                    (((long) buffer.get(offset + 2) & 0xFF) << 40) |
                    (((long) buffer.get(offset + 3) & 0xFF) << 32) |
                    (((long) buffer.get(offset + 4) & 0xFF) << 24) |
                    (((long) buffer.get(offset + 5) & 0xFF) << 16) |
                    (((long) buffer.get(offset + 6) & 0xFF) << 8) |
                    ((long) buffer.get(offset + 7) & 0xFF);

            offset += 8;

            if (playbackDelay > 0 && systemTimestamp != C.NO_VALUE && this.steadyTimestamp != C.NO_VALUE) {
                steady = (((long) buffer.get(offset) & 0xFF) << 56) |
                        (((long) buffer.get(offset + 1) & 0xFF) << 48) |
                        (((long) buffer.get(offset + 2) & 0xFF) << 40) |
                        (((long) buffer.get(offset + 3) & 0xFF) << 32) |
                        (((long) buffer.get(offset + 4) & 0xFF) << 24) |
                        (((long) buffer.get(offset + 5) & 0xFF) << 16) |
                        (((long) buffer.get(offset + 6) & 0xFF) << 8) |
                        ((long) buffer.get(offset + 7) & 0xFF);
                offset += 8;
            }
        }

        int compositionTimeOffset = -1;
        if (type == WEB_AVC_KEY_FRAME
                || type == WEB_AVC_FRAME
                || type == WEB_HEVC_FRAME
                || type == WEB_HEVC_KEY_FRAME) {

            if (buffer.limit() < offset + 4) {
                return;
            }

            compositionTimeOffset = (((int) buffer.get(offset) & 0xFF) << 24) |
                    (((int) buffer.get(offset + 1) & 0xFF) << 16) |
                    (((int) buffer.get(offset + 2) & 0xFF) << 8) |
                    ((int) buffer.get(offset + 3) & 0xFF);

            offset += 4;
        }

        StreamBuffer stream = getStreamBySn(sn);
        if (stream != null) {
            if (stream.isVideo()) {
                // video
                buffer.position(offset);
                processVideoFrame(stream, type, timestamp, compositionTimeOffset, buffer.slice());
                if (steady != C.NO_VALUE &&
                        (type == WEB_AVC_KEY_FRAME
                                || type == WEB_HEVC_KEY_FRAME
                                || type == WEB_VP8_KEY_FRAME
                                || type == WEB_VP9_KEY_FRAME
                                || type == WEB_AV1_KEY_FRAME)) {
                    stream.notifySteadyTimestamp(zeroTime, steady);
                }
            } else if (stream.isAudio()) {
                // audio
                buffer.position(offset);
                processAudioFrame(stream, type, timestamp, buffer.slice());
                if (steady != C.NO_VALUE &&
                        (type == WEB_AAC_FRAME
                                || type == WEB_MP3_FRAME
                                || type == WEB_OPUS_FRAME)) {
                    stream.notifySteadyTimestamp(zeroTime, steady);
                }
            }
        }
    }

    private void processAudioFrame(StreamBuffer streamBuffer, int type, long timestamp, ByteBuffer buffer) {

        if (streamBuffer.getState() != StreamBuffer.State.PLAY) {
            return;
        }

        switch (type) {
            case WEB_AAC_SEQUENCE_HEADER:
                byte[] seqHeader = new byte[buffer.remaining()];
                buffer.get(seqHeader);
                streamBuffer.setExtradata(seqHeader);
                break;

            case WEB_AAC_FRAME:
            case WEB_MP3_FRAME:
            case WEB_OPUS_FRAME:
                byte[] audioFrame = new byte[buffer.remaining()];
                buffer.get(audioFrame);
                streamBuffer.writeAudioFrame(timestamp, audioFrame);
                break;

            default:
                break;
        }
    }

    private void processVideoFrame(StreamBuffer streamBuffer, int type, long timestamp, int offset, ByteBuffer buffer) {

        if (streamBuffer.getState() != StreamBuffer.State.PLAY) {
            return;
        }

        switch (type) {
            case WEB_AVC_SEQUENCE_HEADER:
            case WEB_HEVC_SEQUENCE_HEADER:
                byte[] seqHeader = new byte[buffer.remaining()];
                buffer.get(seqHeader);
                streamBuffer.setExtradata(seqHeader);
                break;

            case WEB_AVC_KEY_FRAME:
            case WEB_HEVC_KEY_FRAME:
            case WEB_AVC_FRAME:
            case WEB_HEVC_FRAME:
                byte[] h26xFrame = new byte[buffer.remaining()];
                buffer.get(h26xFrame);
                boolean h26xKeyKrame = type == WEB_AVC_KEY_FRAME
                        || type == WEB_HEVC_KEY_FRAME;
                streamBuffer.writeH26xFrame(timestamp, offset, h26xFrame, h26xKeyKrame);
                break;

            case WEB_VP8_KEY_FRAME:
            case WEB_VP9_KEY_FRAME:
            case WEB_AV1_KEY_FRAME:
            case WEB_VP8_FRAME:
            case WEB_VP9_FRAME:
            case WEB_AV1_FRAME:
                byte[] vpxFrame = new byte[buffer.remaining()];
                buffer.get(vpxFrame);
                boolean vpxKeyFrame = type == WEB_VP8_KEY_FRAME
                        || type == WEB_VP9_KEY_FRAME
                        || type == WEB_AV1_KEY_FRAME;
                streamBuffer.writeVpxFrame(timestamp, vpxFrame, vpxKeyFrame);
                break;

            default:
                break;
        }
    }

    private void processTextMessage(String text) {
        try {
            JSONObject response = new JSONObject(text);
            String command = response.getString("command");
            if (command.equalsIgnoreCase("status")) {
                String steady = response.optString("steady", "");
                String system = response.optString("system", "");
                if (steady.isEmpty() || system.isEmpty()) {
                    notifyOnStateChange(State.STEADY_SUPPORT_CHECK, Status.STEADY_UNSUPPORTED);
                } else {
                    notifyOnStateChange(State.STEADY_SUPPORT_CHECK, Status.SUCCESS);
                    if (playbackDelay > 0) {
                        steadyTimestamp = Long.parseLong(steady);
                        systemTimestamp = Long.parseLong(system);

                        long localNanoTime = System.nanoTime() / 1000;
                        long remoteClockOffset = 0;
                        remoteClockOffset += playbackDelay;
                        zeroTime = localNanoTime - steadyTimestamp + remoteClockOffset;
                    }
                }

                JSONArray infoArray = response.getJSONArray("info");
                if (infoArray.length() < 1) {
                    return;
                }

                for (int i = 0; i < infoArray.length(); i++) {

                    JSONObject info = infoArray.getJSONObject(i);
                    JSONObject streamInfo = info.getJSONObject("stream_info");

                    if (streamInfo.has("vcodec") && streamInfo.has("vtimescale")) {

                        StreamBuffer videoBuffer = getBufferFactory().createVideoBuffer();

                        videoBuffer.setConnectionId(getConnectionId());
                        videoBuffer.setStreamId(++id);

                        int timescale = streamInfo.getInt("vtimescale");
                        videoBuffer.setTimescale(timescale);

                        videoBuffer.setStream(info.getString("stream"));
                        videoBuffer.setBandwidth(streamInfo.getInt("bandwidth"));

                        String resolution = streamInfo.getString("resolution");
                        Matcher m = RESOLUTION_PATTERN.matcher(resolution);
                        if (m.find()) {
                            int width = Integer.parseInt(m.group(1));
                            int height = Integer.parseInt(m.group(2));
                            videoBuffer.setSize(new Size(width, height));
                        }

                        String vcodec = streamInfo.getString("vcodec");
                        if (vcodec.startsWith("avc1")) {
                            videoBuffer.setMimeType(MediaFormat.MIMETYPE_VIDEO_AVC);
                        } else if (vcodec.startsWith("hvc1")) {
                            videoBuffer.setMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC);
                        } else if (vcodec.startsWith("vp8")) {
                            videoBuffer.setMimeType(MediaFormat.MIMETYPE_VIDEO_VP8);
                        } else if (vcodec.startsWith("vp9")) {
                            videoBuffer.setMimeType(MediaFormat.MIMETYPE_VIDEO_VP9);
                        } else if (vcodec.startsWith("av01")) {
                            videoBuffer.setMimeType(MediaFormat.MIMETYPE_VIDEO_AV1);
                        }
                        streamIdMap.put(videoBuffer.getStreamId(), videoBuffer);
                    }

                    if (streamInfo.has("acodec") && streamInfo.has("atimescale")) {

                        StreamBuffer audioBuffer = getBufferFactory().createAudioBuffer();

                        audioBuffer.setConnectionId(getConnectionId());
                        audioBuffer.setStreamId(++id);

                        int timescale = streamInfo.getInt("atimescale");
                        audioBuffer.setTimescale(timescale);

                        audioBuffer.setStream(info.getString("stream"));
                        audioBuffer.setBandwidth(streamInfo.getInt("bandwidth"));

                        String acodec = streamInfo.getString("acodec");
                        if (acodec.equals("opus")) {
                            audioBuffer.setMimeType(MediaFormat.MIMETYPE_AUDIO_OPUS);
                        } else if (acodec.equals("mp4a.40.34")) {
                            audioBuffer.setMimeType(MediaFormat.MIMETYPE_AUDIO_MPEG);
                        } else {
                            audioBuffer.setMimeType(MediaFormat.MIMETYPE_AUDIO_AAC);
                        }

                        streamIdMap.put(audioBuffer.getStreamId(), audioBuffer);
                    }
                }

                onStreamInfoReceived();

            }

        } catch (JSONException ignored) {
        }
    }

    private void sendCommand(String command) {
        try {
            append(0x81);

            if (command.length() < 125) {
                append(0x80 | command.length());
            } else if (command.length() <= 0xFFFF) {
                append(0x80 | 126);
                append(command.length() >> 8);
                append(command.length() & 0xFF);
            } else {
                close();
                return;
            }

            byte[] mask = new byte[4];
            new Random().nextBytes(mask);
            append(mask[0]);
            append(mask[1]);
            append(mask[2]);
            append(mask[3]);

            byte[] bytes = command.getBytes();
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] ^= mask[i % 4];
            }
            send(bytes);

        } catch (Exception e) {
            close();
        }
    }

    private void sendPlay(List<PlayRequest> requests) {
        StringBuilder play = new StringBuilder();
        play.append("{\"command\":\"Play\", \"streams\":[");
        String steady = "";
        if (playbackDelay > 0 && systemTimestamp != C.NO_VALUE && steadyTimestamp != C.NO_VALUE) {
            steady = ",\"steady\":true";
        }

        for (int i = 0; i < requests.size(); i++) {
            StreamBuffer s = getStreamByStreamId(requests.get(i).getStreamId());
            if (null != s) {
                s.setSn(++sn);
                s.setState(StreamBuffer.State.PLAY);
                s.setOffset(requests.get(i).getOffset());
                s.setDuration(requests.get(i).getDuration());

                if (i > 0) {
                    play.append(",");
                }

                play.append(String.format(Locale.ENGLISH,
                        "{\"stream\":\"%s\",\"type\":\"%s\",\"sn\":\"%d\",\"offsetMs\":\"%d\",\"duration\":\"%d\"%s}",
                        s.getStream(),
                        s.isVideo() ? "video" : "audio",
                        s.getSn(), s.getOffset(), s.getDuration(),
                        steady
                ));

                streamSnMap.put(s.getSn(), s);
            }
        }

        play.append("]}");
        sendCommand(play.toString());
    }

    private void sendCancel(List<Integer> streams) {

        StringBuilder cancel = new StringBuilder();
        cancel.append("{\"command\":\"Cancel\", \"streams\":[");

        for (int i = 0; i < streams.size(); i++) {

            if (i > 0) {
                cancel.append(",");
            }

            StreamBuffer s = getStreamByStreamId(streams.get(i));
            cancel.append(String.format(Locale.ENGLISH, "\"%d\"", s.getSn()));
            s.setState(StreamBuffer.State.STOP);
            streamSnMap.remove(s.getSn());
            s.release();
        }

        cancel.append("]}");
        sendCommand(cancel.toString());
    }

    @Override
    public Collection<StreamBuffer> getStreamInfo() {
        return streamIdMap.values();
    }

    @Override
    public StreamBuffer getStreamByStreamId(int stream_id) {
        return streamIdMap.get(stream_id);
    }

    private StreamBuffer getStreamBySn(int sn) {
        return streamSnMap.get(sn);
    }

    @Override
    public void playStreams(List<PlayRequest> streams) {
        sendPlay(streams);
    }

    @Override
    public void cancelStreams(List<Integer> streams) {
        sendCancel(streams);
    }

    @Override
    synchronized public void close() {
        if (state != SldpConnectionState.CLOSED) {
            state = SldpConnectionState.CLOSED;
            super.close();
            notifyOnStateChange(State.DISCONNECTED, status);
        }
    }

}