package com.teslaux.wheelbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Status screen. The app has no settings: it runs a background service that keeps the music
 * volume at maximum for the TeslAux USB audio device.
 */
public final class MainActivity extends Activity {
    private static final int TEXT = Color.parseColor("#E8EDF2");
    private static final int MUTED = Color.parseColor("#8A97A3");
    private static final int OK = Color.parseColor("#5ED08A");
    private static final int WARN = Color.parseColor("#E0A64A");

    private TextView deviceRow;
    private TextView volumeRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));
        root.setBackgroundColor(Color.parseColor("#101519"));

        TextView title = new TextView(this);
        title.setText("TeslAux");
        title.setTextColor(TEXT);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Keeps the music volume at maximum while the TeslAux USB audio device"
                + " is the output.\n\nSteering-wheel media buttons are handled by Screenmate"
                + " itself, so this app only manages volume.");
        body.setTextColor(MUTED);
        body.setTextSize(16);
        body.setPadding(0, dp(12), 0, dp(28));
        root.addView(body);

        deviceRow = row(root, dp(6));
        volumeRow = row(root, dp(6));

        Button apply = new Button(this);
        apply.setText("Set volume now");
        apply.setOnClickListener(v -> {
            VolumeService.applyMaxMusicVolume(getSystemService(AudioManager.class));
            render();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(28);
        root.addView(apply, params);

        setContentView(root);

        // Start the service so the callback registers and the current device is handled.
        startForegroundService(new Intent(this, VolumeService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        AudioManager audioManager = getSystemService(AudioManager.class);
        if (audioManager == null) {
            setStatus(deviceRow, "Audio service unavailable", WARN);
            setStatus(volumeRow, "", MUTED);
            return;
        }

        boolean attached = false;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (VolumeService.isTeslAuxSink(device)) {
                attached = true;
                break;
            }
        }
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        setStatus(deviceRow,
                attached ? "TeslAux audio device connected" : "TeslAux audio device not connected",
                attached ? OK : MUTED);
        setStatus(volumeRow,
                "Music volume " + volume + " of " + max,
                volume >= max ? OK : WARN);
    }

    private TextView row(LinearLayout parent, int topMargin) {
        TextView view = new TextView(this);
        view.setTextSize(17);
        view.setTextColor(TEXT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        parent.addView(view, params);
        return view;
    }

    private void setStatus(TextView view, String text, int color) {
        view.setText(text);
        view.setTextColor(color);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
