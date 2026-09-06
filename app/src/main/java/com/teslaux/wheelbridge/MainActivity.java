package com.teslaux.wheelbridge;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private static final long WHEEL_TEST_TIMEOUT_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView overallStatus;
    private TextView bridgeStatus;
    private TextView audioStatus;
    private TextView wheelStatus;
    private TextView notificationStatus;
    private TextView playerStatus;
    private Button notificationAccess;
    private Button verifyWheel;
    private long verificationStartedAt;
    private long verificationConnectionAt;
    private boolean verificationFailed;
    private String verificationPlayerFingerprint;

    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            handler.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.rgb(16, 21, 25));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));
        scrollView.addView(layout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("TeslAux");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        layout.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("Control media via the steering wheel input buttons.");
        explanation.setTextColor(Color.rgb(185, 193, 199));
        explanation.setTextSize(16);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explanationParams.setMargins(0, dp(12), 0, dp(20));
        layout.addView(explanation, explanationParams);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.rgb(28, 35, 41));
        cardBackground.setCornerRadius(dp(14));
        cardBackground.setStroke(dp(1), Color.rgb(64, 74, 82));
        statusCard.setBackground(cardBackground);

        TextView statusTitle = new TextView(this);
        statusTitle.setText("Status");
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTextSize(17);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(statusTitle);

        overallStatus = statusRow(true);
        overallStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(overallStatus);
        bridgeStatus = statusRow(false);
        statusCard.addView(bridgeStatus);
        audioStatus = statusRow(false);
        statusCard.addView(audioStatus);
        wheelStatus = statusRow(false);
        statusCard.addView(wheelStatus);
        notificationStatus = statusRow(false);
        statusCard.addView(notificationStatus);
        playerStatus = statusRow(false);
        statusCard.addView(playerStatus);
        layout.addView(statusCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.setMargins(0, dp(16), 0, 0);
        layout.addView(actions, actionsParams);

        Button start = new Button(this);
        start.setText("Start bridge");
        start.setOnClickListener(view -> startBridge());
        actions.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button stop = new Button(this);
        stop.setText("Stop bridge");
        stop.setOnClickListener(view -> stopBridge());
        actions.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        notificationAccess = new Button(this);
        notificationAccess.setText("Enable notification access");
        notificationAccess.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        layout.addView(notificationAccess, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        verifyWheel = new Button(this);
        verifyWheel.setText("Verify wheel controls");
        verifyWheel.setOnClickListener(view -> startWheelVerification());
        layout.addView(verifyWheel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView instructions = new TextView(this);
        instructions.setText(
                "\nIf switching between native Spotify and Android Auto, stop the previous "
                        + "player so Android routes controls to the new active session.");
        instructions.setTextColor(Color.rgb(155, 165, 173));
        instructions.setTextSize(14);
        layout.addView(instructions);

        setContentView(scrollView);
        requestNotificationPermission();

        SharedPreferences preferences =
                getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE);
        if (!preferences.contains(BridgeService.KEY_ENABLED)
                || preferences.getBoolean(BridgeService.KEY_ENABLED, false)) {
            startBridge();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(refreshStatus);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshStatus);
        super.onStop();
    }

    private void renderStatus() {
        SharedPreferences preferences =
                getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE);
        String rawStatus = preferences.getString(BridgeService.KEY_STATUS, "Stopped");
        boolean connected = rawStatus.startsWith("Connected");
        boolean notificationEnabled = hasNotificationAccess();
        long connectedAt = preferences.getLong(BridgeService.KEY_CONNECTED_AT, 0);
        long lastWheelEventAt =
                preferences.getLong(BridgeService.KEY_LAST_WHEEL_EVENT_AT, 0);
        String lastWheelEvent =
                preferences.getString(BridgeService.KEY_LAST_WHEEL_EVENT, "");
        boolean wheelVerified =
                connected && connectedAt > 0 && lastWheelEventAt >= connectedAt;

        if (verificationFailed && connectedAt != verificationConnectionAt) {
            verificationFailed = false;
        }
        if (verificationStartedAt > 0) {
            if (!connected || connectedAt != verificationConnectionAt) {
                verificationStartedAt = 0;
                verificationFailed = false;
            } else {
                String currentFingerprint = mediaStateFingerprint(notificationEnabled);
                boolean playerChanged =
                        !currentFingerprint.equals(verificationPlayerFingerprint);
                if (lastWheelEventAt >= verificationStartedAt || playerChanged) {
                    if (playerChanged && lastWheelEventAt < verificationStartedAt) {
                        lastWheelEventAt = System.currentTimeMillis();
                        lastWheelEvent = "PLAYER_STATE_CHANGED";
                        preferences.edit()
                                .putLong(BridgeService.KEY_LAST_WHEEL_EVENT_AT, lastWheelEventAt)
                                .putString(BridgeService.KEY_LAST_WHEEL_EVENT, lastWheelEvent)
                                .apply();
                    }
                    wheelVerified = true;
                    verificationStartedAt = 0;
                    verificationFailed = false;
                } else if (System.currentTimeMillis() - verificationStartedAt
                        >= WHEEL_TEST_TIMEOUT_MS) {
                    verificationStartedAt = 0;
                    verificationFailed = true;
                }
            }
        }

        boolean everythingWorking = connected && notificationEnabled && wheelVerified;
        if (everythingWorking) {
            setStatus(overallStatus, "● Everything is working", true);
        } else if (connected && verificationFailed) {
            setStatus(overallStatus,
                    "● Restart Screenmate",
                    Color.rgb(255, 105, 97));
        } else if (connected && notificationEnabled) {
            setStatus(overallStatus, "● Verification needed", false);
        } else {
            setStatus(overallStatus, "● Action needed", false);
        }
        setStatus(bridgeStatus,
                "● Bridge service: " + ("Stopped".equals(rawStatus) ? "STOPPED" : "RUNNING"),
                !"Stopped".equals(rawStatus));
        setStatus(audioStatus,
                "● Screenmate audio: " + (connected ? "connected" : rawStatus),
                connected);
        if (verificationStartedAt > 0) {
            long secondsLeft = Math.max(
                    1,
                    (WHEEL_TEST_TIMEOUT_MS
                            - (System.currentTimeMillis() - verificationStartedAt)
                            + 999) / 1_000);
            setStatus(wheelStatus,
                    "● Wheel controls: press play/pause now (" + secondsLeft + "s)",
                    false);
        } else if (verificationFailed) {
            setStatus(wheelStatus,
                    "● Wheel controls: no event detected — restart Screenmate",
                    Color.rgb(255, 105, 97));
        } else if (wheelVerified) {
            setStatus(wheelStatus,
                    "● Wheel controls: verified"
                            + (lastWheelEvent.isEmpty() ? "" : " (" + lastWheelEvent + ")"),
                    true);
        } else {
            setStatus(wheelStatus,
                    "● Wheel controls: " + (connected ? "not verified" : "unavailable"),
                    false);
        }
        setStatus(notificationStatus,
                "● Notification access: " + (notificationEnabled ? "granted" : "required"),
                notificationEnabled);

        String activePlayer = findActivePlayer(notificationEnabled);
        setStatus(playerStatus,
                "● Active player: " + activePlayer,
                !"none".equals(activePlayer));
        notificationAccess.setVisibility(notificationEnabled ? View.GONE : View.VISIBLE);
        verifyWheel.setVisibility(
                connected && !wheelVerified ? View.VISIBLE : View.GONE);
        verifyWheel.setEnabled(verificationStartedAt == 0);
        verifyWheel.setText(
                verificationStartedAt > 0
                        ? "Waiting for wheel input…"
                        : verificationFailed
                                ? "Test wheel controls again"
                                : "Verify wheel controls");
    }

    private void startWheelVerification() {
        SharedPreferences preferences =
                getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE);
        String rawStatus = preferences.getString(BridgeService.KEY_STATUS, "Stopped");
        if (!rawStatus.startsWith("Connected")) {
            return;
        }
        verificationStartedAt = System.currentTimeMillis();
        verificationConnectionAt =
                preferences.getLong(BridgeService.KEY_CONNECTED_AT, 0);
        verificationPlayerFingerprint = mediaStateFingerprint(hasNotificationAccess());
        verificationFailed = false;
        renderStatus();
    }

    private String mediaStateFingerprint(boolean notificationEnabled) {
        if (!notificationEnabled) {
            return "";
        }
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        if (manager == null) {
            return "";
        }
        try {
            List<String> states = new ArrayList<>();
            for (MediaController controller : manager.getActiveSessions(
                    new ComponentName(this, BridgeNotificationListener.class))) {
                if (getPackageName().equals(controller.getPackageName())) {
                    continue;
                }
                PlaybackState state = controller.getPlaybackState();
                MediaMetadata metadata = controller.getMetadata();
                String metadataFingerprint = metadata == null
                        ? "none"
                        : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                                + ":"
                                + metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                                + ":"
                                + metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                states.add(
                        controller.getPackageName()
                                + ":"
                                + (state == null ? "none" : state.getState())
                                + ":"
                                + metadataFingerprint);
            }
            Collections.sort(states);
            return states.toString();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        String listener = new ComponentName(this, BridgeNotificationListener.class).flattenToString();
        for (String component : enabled.split(":")) {
            if (listener.equals(component)) {
                return true;
            }
        }
        return false;
    }

    private String findActivePlayer(boolean notificationEnabled) {
        if (!notificationEnabled) {
            return "unknown";
        }
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        if (manager == null) {
            return "none";
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                String mediaKeyPackage = manager.getMediaKeyEventSessionPackageName();
                if (mediaKeyPackage != null
                        && !mediaKeyPackage.isEmpty()
                        && !getPackageName().equals(mediaKeyPackage)) {
                    return playerName(mediaKeyPackage);
                }
            }
            List<MediaController> sessions = manager.getActiveSessions(
                    new ComponentName(this, BridgeNotificationListener.class));
            MediaController paused = null;
            for (MediaController controller : sessions) {
                if (getPackageName().equals(controller.getPackageName())) {
                    continue;
                }
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return playerName(controller.getPackageName());
                }
                if (paused == null
                        && state != null
                        && state.getState() == PlaybackState.STATE_PAUSED) {
                    paused = controller;
                }
            }
            if (paused != null) {
                return playerName(paused.getPackageName()) + " (paused)";
            }
        } catch (SecurityException ignored) {
            return "unknown";
        }
        return "none";
    }

    private String playerName(String packageName) {
        if ("com.spotify.music".equals(packageName)) {
            return "Spotify";
        }
        if ("com.andrerinas.headunitrevived".equals(packageName)) {
            return "Android Auto";
        }
        if ("org.videolan.vlc".equals(packageName)) {
            return "VLC";
        }
        return packageName;
    }

    private TextView statusRow(boolean prominent) {
        TextView row = new TextView(this);
        row.setTextSize(prominent ? 16 : 15);
        row.setPadding(0, dp(prominent ? 12 : 7), 0, 0);
        return row;
    }

    private void setStatus(TextView view, String text, boolean healthy) {
        setStatus(
                view,
                text,
                healthy ? Color.rgb(94, 218, 137) : Color.rgb(255, 184, 77));
    }

    private void setStatus(TextView view, String text, int color) {
        view.setText(text);
        view.setTextColor(color);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startBridge() {
        startForegroundService(new Intent(this, BridgeService.class));
    }

    private void stopBridge() {
        getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(BridgeService.KEY_ENABLED, false)
                .apply();
        stopService(new Intent(this, BridgeService.class));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }
}
