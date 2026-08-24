package com.akasha.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Prefs p = new Prefs(context);
            if (p.autoStart()) {
                context.startService(
                        new Intent(context, AgentService.class).setAction(AgentService.ACTION_RUN));
            }
        }
    }
}
