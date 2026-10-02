package org.aohp.driver.bridge;

import android.content.Context;
import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * WebSocket 服务：仅接受 JSON 对象消息，由 {@link JsonCommandHandler} 处理（与 aohp CLI 一致）。
 */
public class BridgeWebSocketServer extends WebSocketServer {
    private static final String TAG = "AohpDriver";

    private JsonCommandHandler jsonCommandHandler;
    private volatile boolean listening;
    private volatile Exception lastError;

    public boolean isListening() { return listening; }
    public Exception getLastError() { return lastError; }

    public BridgeWebSocketServer(InetSocketAddress host) {
        super(host);
        Log.d(TAG, "WebSocket 监听 " + host);
    }

    public void setContext(Context context) {
        if (context != null) {
            jsonCommandHandler = new JsonCommandHandler(context);
        } else {
            jsonCommandHandler = null;
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d(TAG, "onOpen: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d(TAG, "onClose: " + code + " " + reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "onMessage: " + message);
        if (message != null && message.trim().startsWith("{")) {
            if (jsonCommandHandler != null) {
                jsonCommandHandler.dispatch(conn, message);
            } else {
                try {
                    conn.send(
                            new JSONObject()
                                    .put("status", "failed")
                                    .put("message", "JsonCommandHandler not ready")
                                    .toString());
                } catch (JSONException e) {
                    Log.e(TAG, "Error building response", e);
                }
            }
        } else {
            try {
                String err =
                        new JSONObject()
                                .put("status", "failed")
                                .put(
                                        "message",
                                        "Only JSON-RPC is supported; send a JSON object (same as aohp CLI).")
                                .toString();
                conn.send(err);
            } catch (JSONException e) {
                Log.e(TAG, "Error sending error response", e);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        Log.d(TAG, "onMessage(ByteBuffer) — echo binary");
        conn.send(message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "onError", ex);
        if (conn == null) lastError = ex;
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart: WebSocket server running");
        listening = true;
    }

    public void stopServer() {
        listening = false;
        for (WebSocket connection : connections()) {
            connection.close();
        }
        try {
            super.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String byteBufferToString(ByteBuffer buffer) {
        try {
            Charset charset = Charset.forName("UTF-8");
            CharsetDecoder decoder = charset.newDecoder();
            CharBuffer charBuffer = decoder.decode(buffer);
            buffer.flip();
            return charBuffer.toString();
        } catch (Exception ex) {
            Log.w(TAG, "byteBufferToString", ex);
            return null;
        }
    }
}
