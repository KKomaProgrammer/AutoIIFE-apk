package com.kkomaprogrammer.iiferunner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("runner_prefs", Context.MODE_PRIVATE);
        if (!p.getBoolean("autostart", true) || p.getString("code", "").trim().isEmpty()) return;
        Intent service = new Intent(context, RunnerService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
    }
}
