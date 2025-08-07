package com.softvelum.sldp;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.List;

public abstract class Connection {

    public enum State {
        INITIALIZED,
        CONNECTED,
        SETUP,
        PLAY,
        DISCONNECTED,
        STEADY_SUPPORT_CHECK
    }

    public enum Status {
        SUCCESS,
        CONN_FAIL,
        AUTH_FAIL,
        HANDSHAKE_FAIL,
        UNKNOWN_FAIL,
        STEADY_UNSUPPORTED
    }

    public interface Listener {
        Handler getHandler();

        void onStreamInfoReceived(int connectionId);

        void onStateChanged(int connectionId, State state, Status status, JSONObject info);
    }

    private final int id;
    private final StreamBuffer.Factory bufferFactory;
    private final Listener listener;

    private JSONObject info = new JSONObject();

    protected Connection(int id,
                         @NonNull StreamBuffer.Factory bufferFactory,
                         Listener listener) {
        this.id = id;
        this.bufferFactory = bufferFactory;
        this.listener = listener;
    }

    @NonNull
    public StreamBuffer.Factory getBufferFactory() {
        return bufferFactory;
    }

    public int getConnectionId() {
        return id;
    }

    public void putError(@NonNull String name, int value) throws JSONException {
        info.put(name, value);
    }

    public void putError(@NonNull String name, @Nullable Object value) throws JSONException {
        info.put(name, value);
    }

    public void resetErrors() {
        info = new JSONObject();
    }

    public abstract void release();

    public void playStreams(List<PlayRequest> requests) {

    }

    public void cancelStreams(List<Integer> streams) {

    }

    abstract public StreamBuffer getStreamByStreamId(int stream_id);

    abstract public Collection<StreamBuffer> getStreamInfo();

    protected void notifyOnStateChange(final State state,
                             final Status status) {
        notifyOnStateChange(state, status, info);
    }

    protected void notifyOnStateChange(final State state,
                                     final Status status,
                                     final JSONObject info) {
        if (listener == null) {
            return;
        }
        final Handler handler = listener.getHandler();
        if (handler == null) {
            return;
        }
        handler.post(() -> listener.onStateChanged(id, state, status, info));
    }

    public void onStreamInfoReceived() {
        if (listener == null) {
            return;
        }
        final Handler handler = listener.getHandler();
        if (handler == null) {
            return;
        }
        handler.post(() -> listener.onStreamInfoReceived(id));
    }

}

