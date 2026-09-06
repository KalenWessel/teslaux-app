package com.teslaux.wheelbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        boolean enabled = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(BridgeService.KEY_ENABLED, false);
        if (enabled) {
            context.startForegroundService(new Intent(context, BridgeService.class));
        }
    }
}
