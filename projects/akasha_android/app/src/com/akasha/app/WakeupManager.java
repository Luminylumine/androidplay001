package com.akasha.app;

import android.content.Context;
import android.content.Intent;

/**
 * Wake-up entry point for the future timer / event phases: any scheduled or
 * event-driven trigger calls {@link #wakeupAgent} to start the agent on a
 * session with a reason. The timer/event schedulers (next phases) will call
 * this; it is functional today so the interface is ready.
 */
public class WakeupManager {

    /** Start the agent on the given session with a wake-up reason. */
    public static void wakeupAgent(Context ctx, String sessionId, String reason) {
        try {
            Intent i = new Intent(ctx, AgentService.class)
                    .setAction(AgentService.ACTION_RUN_GOAL)
                    .putExtra("text", "（定时/事件唤醒）" + (reason == null ? "" : reason));
            if (sessionId != null) i.putExtra("sessionId", sessionId);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startService(i);
        } catch (Exception e) {
            CpLog.w("Akasha", "wakeupAgent failed: " + e);
        }
    }
}
