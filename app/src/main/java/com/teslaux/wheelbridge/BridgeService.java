package com.teslaux.wheelbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BridgeService extends Service {
    public static final String PREFS = "bridge";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_STATUS = "status";
    public static final String KEY_CONNECTED_AT = "connected_at";
    public static final String KEY_LAST_WHEEL_EVENT_AT = "last_wheel_event_at";
    public static final String KEY_LAST_WHEEL_EVENT = "last_wheel_event";

    private static final String CHANNEL_ID = "wheel_bridge";
    private static final int NOTIFICATION_ID = 1;
    private static final int RECONNECT_DELAY_MS = 2_000;
    private static final int PRIORITY_RECLAIM_DELAY_MS = 250;
    private static final String TAG = "TeslAuxWheelBridge";

    private final AtomicBoolean running = new AtomicBoolean();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String lastStatus;
    private MediaSession mediaSession;
    private AudioTrack silentAudioTrack;
    private volatile Socket socket;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getPreferences().edit().putBoolean(KEY_ENABLED, true).apply();
        startForeground(NOTIFICATION_ID, notification("Starting"));
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::connectionLoop, "teslaux-wheel-bridge");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        closeSocket();
        stopSilentPlayback();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        setStatus("Stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connectionLoop() {
        setStatus("Connecting to Screenmate");
        while (running.get()) {
            try {
                connectAndDrain();
                if (running.get()) {
                    setStatus("Disconnected; retrying");
                }
            } catch (IOException e) {
                if (running.get()) {
                    setStatus("Waiting for Screenmate Wi-Fi Audio");
                }
            } finally {
                closeSocket();
            }

            if (running.get()) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void connectAndDrain() throws IOException {
        Socket connectedSocket = new Socket();
        connectedSocket.setTcpNoDelay(true);
        connectedSocket.connect(new InetSocketAddress("127.0.0.1", 8888), 2_000);
        socket = connectedSocket;

        InputStream input = connectedSocket.getInputStream();
        OutputStream output = connectedSocket.getOutputStream();
        performHandshake(input, output);
        getPreferences().edit()
                .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
                .apply();
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
        startSilentPlayback();
        reclaimMediaButtonPriority();
        setStatus("Connected — wheel controls active");

        try {
            while (running.get()) {
                drainFrame(input, output);
            }
        } finally {
            stopSilentPlayback();
            if (mediaSession != null) {
                mediaSession.setActive(false);
            }
        }
    }

    private void startSilentPlayback() {
        stopSilentPlayback();
        int sampleRate = 48_000;
        int frameCount = sampleRate;
        byte[] silence = new byte[frameCount * 2];
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(silence.length)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        if (track.write(silence, 0, silence.length) != silence.length) {
            track.release();
            Log.w(TAG, "Could not initialize silent playback");
            return;
        }
        track.setLoopPoints(0, frameCount, -1);
        // The samples are already all zero. Setting volume to zero additionally marks the
        // AudioTrack as client-muted, causing Android to ignore it when choosing which media
        // session receives hardware buttons.
        track.setVolume(1f);
        track.play();
        silentAudioTrack = track;
    }

    private void stopSilentPlayback() {
        AudioTrack track = silentAudioTrack;
        silentAudioTrack = null;
        if (track == null) {
            return;
        }
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
            // The track may not have reached PLAYING before the connection closed.
        }
        track.release();
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "TeslAuxWheelBridge");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(
                        PlaybackState.ACTION_PLAY
                                | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SKIP_TO_NEXT
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1)
                .build());
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null) {
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    getPreferences().edit()
                            .putLong(KEY_LAST_WHEEL_EVENT_AT, System.currentTimeMillis())
                            .putString(
                                    KEY_LAST_WHEEL_EVENT,
                                    KeyEvent.keyCodeToString(event.getKeyCode()))
                            .apply();
                    // Consume DOWN without forwarding. Forwarding here promotes the target player
                    // before Screenmate dispatches UP, allowing that UP to leak directly to the
                    // player and immediately reverse a play/pause command.
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    return forwardToActivePlayer(event.getKeyCode());
                }
                return true;
            }
        });
    }

    private boolean forwardToActivePlayer(int keyCode) {
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        if (manager == null) {
            Log.w(TAG, "MediaSessionManager unavailable");
            return true;
        }

        ComponentName listener =
                new ComponentName(this, BridgeNotificationListener.class);
        try {
            MediaController fallback = null;
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if (getPackageName().equals(controller.getPackageName())) {
                    continue;
                }
                if (fallback == null) {
                    fallback = controller;
                }
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return dispatchToController(controller, keyCode);
                }
            }
            if (fallback != null) {
                return dispatchToController(fallback, keyCode);
            }
            Log.w(TAG, "No active media session found");
            setStatus("Connected — no active media player");
        } catch (SecurityException e) {
            Log.w(TAG, "Notification Access is not enabled", e);
            setStatus("Connected — enable Notification Access");
        }
        return true;
    }

    private boolean dispatchToController(MediaController controller, int keyCode) {
        MediaController.TransportControls controls = controller.getTransportControls();
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    controls.pause();
                } else {
                    controls.play();
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                controls.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                controls.pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                controls.skipToNext();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                controls.skipToPrevious();
                break;
            default:
                return false;
            }
        String player = controller.getPackageName();
        Log.i(TAG, "Forwarded media key " + keyCode + " to " + player);
        setStatus("Connected — sent " + KeyEvent.keyCodeToString(keyCode) + " to " + player);
        reclaimMediaButtonPriority();
        return true;
    }

    private void reclaimMediaButtonPriority() {
        mainHandler.postDelayed(() -> {
            if (!running.get() || mediaSession == null) {
                return;
            }

            AudioTrack track = silentAudioTrack;
            if (track != null) {
                try {
                    // Android assigns media buttons to the most recently active playback UID.
                    // Forwarding a command activates the target player, so transition our silent
                    // track after it has handled the command to make TeslAux the recipient again.
                    track.pause();
                    track.play();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Could not reclaim audio playback priority", e);
                }
            }
            Log.i(TAG, "Reclaimed media-button priority");
        }, PRIORITY_RECLAIM_DELAY_MS);
    }

    private void performHandshake(InputStream input, OutputStream output) throws IOException {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String request =
                "GET /audio HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8888\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        String response = readHeaders(input);
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            int lineEnd = response.indexOf("\r\n");
            String statusLine = lineEnd >= 0 ? response.substring(0, lineEnd) : response;
            throw new IOException("WebSocket upgrade failed: " + statusLine);
        }
    }

    private String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16_384) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("Connection closed during handshake");
            }
            bytes.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && value == '\n') {
                matched++;
                if (matched == 4) {
                    return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("WebSocket response headers too large");
    }

    private void drainFrame(InputStream input, OutputStream output) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0) {
            throw new EOFException("WebSocket closed");
        }

        int opcode = first & 0x0f;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) readByte(input) << 8) | readByte(input);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(input);
            }
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IOException("Unsupported WebSocket frame length: " + length);
        }

        byte[] mask = null;
        if (masked) {
            mask = readExactly(input, 4);
        }

        if (opcode == 0x8) {
            throw new EOFException("WebSocket close frame");
        }

        if (opcode == 0x9 && length <= 125) {
            byte[] payload = readExactly(input, (int) length);
            if (mask != null) {
                unmask(payload, mask);
            }
            sendMaskedControlFrame(output, 0xA, payload);
            return;
        }

        discardExactly(input, (int) length);
    }

    private int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of WebSocket frame");
        }
        return value;
    }

    private byte[] readExactly(InputStream input, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = input.read(bytes, offset, count - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of WebSocket frame");
            }
            offset += read;
        }
        return bytes;
    }

    private void discardExactly(InputStream input, int count) throws IOException {
        byte[] buffer = new byte[Math.min(16_384, Math.max(1, count))];
        int remaining = count;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of WebSocket frame");
            }
            remaining -= read;
        }
    }

    private void unmask(byte[] payload, byte[] mask) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
    }

    private void sendMaskedControlFrame(OutputStream output, int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        output.write(0x80 | opcode);
        output.write(0x80 | payload.length);
        output.write(mask);
        for (int i = 0; i < payload.length; i++) {
            output.write(payload[i] ^ mask[i % 4]);
        }
        output.flush();
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void setStatus(String status) {
        if (status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;
        getPreferences().edit().putString(KEY_STATUS, status).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(status));
        }
    }

    private Notification notification(String status) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("TeslAux")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Wheel bridge status", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
