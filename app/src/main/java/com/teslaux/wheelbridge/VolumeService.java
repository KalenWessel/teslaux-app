package com.teslaux.wheelbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Holds the music volume at maximum while the TeslAux USB audio device is the output.
 *
 * Android stores a music volume per output device, and a fresh boot comes up at the default,
 * so the car plays much quieter than intended until someone turns it up. The service watches
 * for the TeslAux sound card and sets the volume whenever it appears, including when it
 * re-enumerates a few seconds after boot.
 *
 * Registering the callback also delivers the devices that are already connected, so starting
 * this service is enough to fix a device that is attached right now.
 */
public final class VolumeService extends Service {
    private static final String CHANNEL_ID = "volume";
    private static final int NOTIFICATION_ID = 1;
    private static final String USB_AUDIO_PRODUCT = "TeslAux";
    // Routing to a re-enumerated USB audio device can settle after it is reported, and the
    // volume applies to whichever output is active at that moment, so repeat the set.
    private static final int[] DELAYS_MS = {0, 1_000, 3_000, 6_000};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private final Runnable setMaxMusicVolume = () -> applyMaxMusicVolume(audioManager);
    private final AudioDeviceCallback usbAudioCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
            for (AudioDeviceInfo device : devices) {
                if (isTeslAuxSink(device)) {
                    scheduleMaxMusicVolume();
                    return;
                }
            }
        }
    };

    static boolean isTeslAuxSink(AudioDeviceInfo device) {
        return device.isSink()
                && device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                && String.valueOf(device.getProductName()).contains(USB_AUDIO_PRODUCT);
    }

    static void applyMaxMusicVolume(AudioManager audioManager) {
        if (audioManager == null) {
            return;
        }
        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = getSystemService(AudioManager.class);
        createNotificationChannel();
        audioManager.registerAudioDeviceCallback(usbAudioCallback, handler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(setMaxMusicVolume);
        if (audioManager != null) {
            audioManager.unregisterAudioDeviceCallback(usbAudioCallback);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleMaxMusicVolume() {
        handler.removeCallbacks(setMaxMusicVolume);
        for (int delay : DELAYS_MS) {
            handler.postDelayed(setMaxMusicVolume, delay);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Volume", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Keeps the TeslAux audio output at full volume");
        manager.createNotificationChannel(channel);
    }

    private Notification notification() {
        PendingIntent launch = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("TeslAux")
                .setContentText("TeslAux audio stays at full volume")
                .setContentIntent(launch)
                .setOngoing(true)
                .build();
    }
}
