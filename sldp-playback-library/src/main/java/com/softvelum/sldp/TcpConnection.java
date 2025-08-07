package com.softvelum.sldp;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
import static javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW;
import static javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW;
import static javax.net.ssl.SSLEngineResult.Status.CLOSED;
import static javax.net.ssl.SSLEngineResult.Status.OK;

import androidx.annotation.Nullable;

public abstract class TcpConnection extends Connection {

    public static class Config {
        public int connectionId;
        public String host;
        public int port;
        public String app;
        public String stream;
        public boolean ssl;
        public boolean trustAllCerts;
        public String user;
        public String pass;
        public String userAgent;
        public int steadyDelayMs;
    }

    private Selector selector;

    private static final String TAG = "Connection";
    private SocketChannel socketChannel;

    protected String host;
    protected int port;

    protected String userAgent;

    public static final String statusCode = "statusCode";
    public static final String statusText = "statusText";

    private final ByteBuffer outBuffer;
    private final ByteBuffer inBuffer;

    protected TcpConnection(Config config,
                            Selector selector,
                            StreamBuffer.Factory bufferFactory,
                            Connection.Listener listener) throws IOException {
        super(config.connectionId, bufferFactory, listener);

        this.selector = selector;

        this.host = config.host;
        this.port = config.port;
        this.ssl = config.ssl;
        this.trustAllCerts = config.trustAllCerts;
        this.userAgent = config.userAgent;

        inBuffer = ByteBuffer.allocate(4 * 1024 * 1024);
        outBuffer = ByteBuffer.allocate(21 * 1024);

        outBuffer.position(0);
        outBuffer.limit(0);

        socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
    }

    private final boolean ssl;
    private final boolean trustAllCerts;
    private boolean sslHandshakeFinished;
    private SSLEngine sslEngine;
    private ByteBuffer wrappedBuffer;
    private ByteBuffer unwrappedBuffer;

    @SuppressLint("CustomX509TrustManager")
    @Nullable
    private TrustManager[] getTrustManager() {
        if (trustAllCerts) {
            return new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            //Log.d(TAG, "getAcceptedIssuers");
                            return null;
                        }

                        @SuppressLint("TrustAllX509TrustManager")
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                            //Log.d(TAG, "checkClientTrusted");
                        }

                        @SuppressLint("TrustAllX509TrustManager")
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            //Log.d(TAG, "checkServerTrusted");
                        }
                    }
            };
        }
        return null;
    }

    private boolean initSsl() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, getTrustManager(), new SecureRandom());

            sslEngine = sslContext.createSSLEngine(host, port);
            sslEngine.setUseClientMode(true);

            int wrappedBufferSize = sslEngine.getSession().getPacketBufferSize();
            wrappedBuffer = ByteBuffer.allocate(wrappedBufferSize);

            int unwrappedBufferSize = sslEngine.getSession().getApplicationBufferSize();
            unwrappedBuffer = ByteBuffer.allocate(unwrappedBufferSize);
            return true;

        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return false;
        }
    }

    public void connect() {
        try {
            if (ssl && !initSsl()) {
                close();
                return;
            }

            notifyOnStateChange(State.INITIALIZED, Status.SUCCESS);

            socketChannel.register(selector, SelectionKey.OP_CONNECT, this);

            InetSocketAddress socketAddress = new InetSocketAddress(host, port);
            socketChannel.connect(socketAddress);
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            close();
        }
    }

    public void send(String request) throws IOException {
        byte[] buffer = request.getBytes(StandardCharsets.US_ASCII);
        send(buffer, 0, buffer.length);
    }

    public void send(byte[] buffer) throws IOException {
        send(buffer, 0, buffer.length);
    }

    SSLEngineResult wrap(ByteBuffer src, ByteBuffer dst) {
        try {
            dst.clear();

            SSLEngineResult result = sslEngine.wrap(src, dst);
            if (result.getStatus() != OK) {
                Log.e(TAG, "failed to wrap output data");
                close();
                return result;
            }

            dst.flip();
            return result;

        } catch (Exception e) {
            close();
            return new SSLEngineResult(CLOSED, NOT_HANDSHAKING, 0, 0);
        }
    }

    public void send(byte[] buffer, int offset, int count) throws IOException {

        if (null == outBuffer) {
            close();
            return;
        }

        outBuffer.compact();
        outBuffer.put(buffer, offset, count);
        outBuffer.flip();

        if (ssl) {
            if (wrappedBuffer.hasRemaining()) {
                close();
                return;
            }

            SSLEngineResult result = wrap(outBuffer, wrappedBuffer);
            if (result.getStatus() != OK) {
                close();
                return;
            }

            sendInternal(wrappedBuffer);

        } else {

            sendInternal(outBuffer);
        }
    }

    private void sendInternal(ByteBuffer buffer) throws IOException {

        int bytesSent = socketChannel.write(buffer);
        if (bytesSent > 0) {
            inactivityCount = 0;
        }

        if (buffer.hasRemaining()) {
            setOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    public int getSendBufferRemaining() {
        if (null == outBuffer) {
            return 0;
        }
        return outBuffer.remaining();
    }

    public void append(String request) throws IOException {
        byte[] buffer = request.getBytes(StandardCharsets.US_ASCII);
        append(buffer, buffer.length);
    }

    public void append(int b) throws IOException {
        if (null == outBuffer) {
            close();
            return;
        }

        outBuffer.compact();
        outBuffer.put((byte) (b & 0xFF));
        outBuffer.flip();
    }

    public void append(byte[] buffer) throws IOException {
        append(buffer, buffer.length);
    }

    void append(byte[] buffer, int count) throws IOException {

        if (null == outBuffer) {
            close();
            return;
        }

        outBuffer.compact();
        outBuffer.put(buffer, 0, count);
        outBuffer.flip();
    }

    abstract public void onConnect();

    abstract public void onRecv(ByteBuffer byteBuffer);

    public void onSend() {

    }

    protected int inactivityCount = 0;

    public void verifyInactivity() {
        inactivityCount++;
        if (inactivityCount > 5) {
            Log.w(TAG, "inactivity timeout expired");
            close();
        }
    }

    public void processEvent(SelectionKey selectionKey) {
        if (null == selectionKey) {
            return;
        }

        try {

            if (selectionKey.isConnectable()) {
                if (socketChannel.finishConnect()) {
                    inactivityCount = 0;
                    setOps(SelectionKey.OP_READ);

                    if (ssl) {
                        sslEngine.beginHandshake();
                        SSLEngineResult.HandshakeStatus hs = sslEngine.getHandshakeStatus();
                        if (hs == NEED_WRAP) {
                            wrap(outBuffer, wrappedBuffer);
                            sendInternal(wrappedBuffer);
                        } else {
                            Log.e(TAG, "unexpected handshake status on connect, hs=" + hs);
                            close();
                        }

                    } else {
                        onConnect();
                    }
                }
            }

            if (selectionKey.isReadable()) {
                //Log.v(TAG, "read: pos=" + inBuffer.position() + "; limit=" + inBuffer.limit());

                int bytesRead = socketChannel.read(inBuffer);
                //Log.d(TAG, "bytesRead=" + bytesRead);
                if (bytesRead <= 0) {
                    close();
                    return;
                }

                inactivityCount = 0;

                if (ssl) {

                    if (sslHandshakeFinished) {

                        for (int pass = 1; ; pass++) {

                            inBuffer.flip();
                            SSLEngineResult result = sslEngine.unwrap(inBuffer, unwrappedBuffer);
                            inBuffer.compact();

                            //Log.d(TAG, "status=" + result.getStatus() + "; pass=" + pass);

                            if (result.getStatus() == BUFFER_UNDERFLOW) {
                                unwrappedBuffer.flip();
                                onRecv(unwrappedBuffer);
                                unwrappedBuffer.compact();
                                return;
                            } else if (result.getStatus() == BUFFER_OVERFLOW) {
                                unwrappedBuffer = expandBuffer(unwrappedBuffer, unwrappedBuffer.capacity() * 2);
                                //Log.d(TAG, "expand unwrapped buffer");
                            } else if (result.getStatus() != OK) {
                                Log.e(TAG, "failed to unwrap input buffer=" + result.getStatus());
                                close();
                                return;
                            }

                            unwrappedBuffer.flip();
                            onRecv(unwrappedBuffer);
                            unwrappedBuffer.compact();

                        }

                    } else {

                        SSLEngineResult.HandshakeStatus hs = sslEngine.getHandshakeStatus();
                        //Log.d(TAG, "hs=" + hs);

                        SSLEngineResult result;
                        SSLEngineResult.Status status = OK;

                        while (hs != FINISHED && hs != NOT_HANDSHAKING && status == OK) {

                            if (hs == NEED_UNWRAP) {

                                inBuffer.flip();
                                result = sslEngine.unwrap(inBuffer, unwrappedBuffer);
                                inBuffer.compact();

                                hs = result.getHandshakeStatus();
                                status = result.getStatus();

                            } else if (hs == NEED_WRAP) {

                                result = wrap(outBuffer, wrappedBuffer);

                                hs = result.getHandshakeStatus();
                                status = result.getStatus();

                                if (status == OK) {
                                    sendInternal(wrappedBuffer);
                                }

                            } else if (hs == NEED_TASK) {

                                Runnable runnable;
                                while ((runnable = sslEngine.getDelegatedTask()) != null) {
                                    //Log.d(TAG, "running delegated task...");
                                    runnable.run();
                                }
                                hs = sslEngine.getHandshakeStatus();
                                if (hs == NEED_TASK) {
                                    throw new Exception("handshake shouldn't need additional tasks");
                                }

                            } else {

                                Log.e(TAG, "unexpected hs=" + hs);
                                close();
                                return;
                            }

                            //Log.d(TAG, "new hs=" + hs);
                            //Log.d(TAG, "new status=" + status);
                        }

                        // In some cases, on some platforms, the engine can go directly
                        // from a handshaking state to NOT_HANDSHAKING.
                        // We handle this situation as if it had returned FINISHED.

                        // hs == NEED_UNWRAP && status == BUFFER_UNDERFLOW
                        // do nothing and wait for next data portion

                        if ((hs == FINISHED || hs == NOT_HANDSHAKING) && status == OK) {
                            //Log.d(TAG, "ssl handshake finished");
                            sslHandshakeFinished = true;
                            onConnect();
                        }
                    }

                } else {
                    inBuffer.flip();
                    onRecv(inBuffer);
                    inBuffer.compact();
                }
            }

            if (selectionKey.isWritable()) {
                //Log.d(TAG, "write event");

                if (ssl) {
                    sendBuffer(wrappedBuffer);

                } else {
                    sendBuffer(outBuffer);
                }
            }

        } catch (Exception e) {
            Log.d(TAG, Log.getStackTraceString(e));
            close();
        }
    }

    private void setOps(int operations) {
        if (null == socketChannel) {
            return;
        }

        SelectionKey selectionKey = socketChannel.keyFor(selector);
        if (null == selectionKey) {
            close();
            return;
        }
        selectionKey.interestOps(operations);
    }

    private void sendBuffer(ByteBuffer buffer) {
        //Log.d(TAG, "sendBuffer");

        try {

            int bytesSent = socketChannel.write(buffer);
            if (bytesSent > 0) {
                inactivityCount = 0;
            }

            if (!buffer.hasRemaining()) {
                setOps(SelectionKey.OP_READ);

                onSend();
            }

        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            close();
        }
    }

    synchronized public void close() {
        if (null == socketChannel) {
            return;
        }
        Log.d(TAG, "close id=" + getConnectionId());
        //Log.e(TAG, Log.getStackTraceString(new Exception()));

        try {
            socketChannel.close();

            SelectionKey selectionKey = socketChannel.keyFor(selector);
            if (null != selectionKey) {
                selectionKey.cancel();
            }

            socketChannel = null;
            selector = null;
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    @Override
    public void release() {
        close();
    }

    /**
     * Expands the given byte buffer to the requested capacity.
     *
     * @param original Original byte buffer.
     * @param cap      Requested capacity.
     * @return Expanded byte buffer.
     */
    private ByteBuffer expandBuffer(ByteBuffer original, int cap) {
        ByteBuffer res = original.isDirect() ? ByteBuffer.allocateDirect(cap) : ByteBuffer.allocate(cap);

        res.order(original.order());

        original.flip();

        res.put(original);

        return res;
    }

}

