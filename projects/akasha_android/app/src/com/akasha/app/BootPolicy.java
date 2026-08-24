package com.akasha.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 开机/总门控下哪些 Agent 可以自动进入循环。
 *
 * 规则：
 *   1) 若 ModelInfo.autoStart == true -> 优先 (per-agent override)
 *   2) 否则看 prefs.autoStartEnabled() 总门控:
 *        - 总门控开 -> 所有 Agent 都可以自启 (历史行为)
 *        - 总门控关 -> 只有显式 autoStart=true 的可以自启
 *   3) 最终仍要通过 AgentConfig.isRunnable() 过滤不可运行的
 */
public final class BootPolicy {

    public static boolean canAutoStart(Context ctx, ModelInfo agent) {
        Prefs p = new Prefs(ctx);
        // 1) per-agent override
        if (agent.autoStart) return true;
        // 2) historical global gate
        if (p.autoStart()) return true;
        return false;
    }

    public static List<ModelInfo> listAutoStartAgents(Context ctx) {
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo m : new Prefs(ctx).models()) {
            if (!canAutoStart(ctx, m)) continue;
            try {
                AgentConfig.resolve(ctx, m); // just for safety checks (id / ctx etc set)
            } catch (Throwable ignore) { continue; }
            out.add(m);
        }
        return out;
    }
}
