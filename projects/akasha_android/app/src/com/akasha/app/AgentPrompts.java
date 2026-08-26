package com.akasha.app;

import android.content.Context;

/**
 * System prompt building blocks (req 7):
 *  - builtInBase(): the built-in persona/rules (read-only reference text).
 *  - defaultBase(ctx): the effective 系统提示词 — 用户在全局设置自定义过的覆盖值，
 *    未自定义则用内置 builtInBase()。
 *  - toolDocs(profile): the action/tool documentation, filtered by the
 *    per-agent permission grants (req 6) - ungranted tools are NOT
 *    advertised to the LLM at all.
 *
 * AgentService assembles: goal + guide
 *   + (会话提示词 | 模型提示词 | 系统提示词)
 *   + toolDocs(profile) + installed apps.
 *
 * 回退链见 {@link #resolveBase(Context, String, ModelInfo)}:
 * 会话提示词(空则)→ 模型提示词(空则)→ 系统提示词(空则=内置默认)。
 */
public class AgentPrompts {

    /**
     * 三级提示词回退: 会话提示词 → 模型提示词 → 系统提示词（空=内置默认）。
     * @return [0]=生效提示词文本  [1]=来源标签("会话提示词"/"模型提示词"/"系统提示词(自定义)"/"系统提示词(内置默认)")
     */
    public static String[] resolveBase(Context ctx, String sessionPrompt, ModelInfo model) {
        if (sessionPrompt != null && !sessionPrompt.trim().isEmpty()) {
            return new String[]{sessionPrompt.trim(), "会话提示词"};
        }
        if (model != null && model.customPrompt != null
                && !model.customPrompt.trim().isEmpty()) {
            return new String[]{model.customPrompt.trim(), "模型提示词"};
        }
        return new String[]{defaultBase(ctx),
                systemPromptSet(ctx) ? "系统提示词(自定义)" : "系统提示词(内置默认)"};
    }

    /** 用户是否自定义了系统提示词（全局设置→系统提示词）。 */
    public static boolean systemPromptSet(Context ctx) {
        String sp = new Prefs(ctx).systemPrompt();
        return sp != null && !sp.trim().isEmpty();
    }

    public static String defaultBase(Context ctx) {
        String sp;
        try { sp = new Prefs(ctx).systemPrompt(); } catch (Exception e) { sp = null; }
        if (sp != null && !sp.trim().isEmpty()) return sp.trim();
        return builtInBase();
    }

    /** 内置默认提示词（回退链最底层的兜底文本，只读参考）。 */
    public static String builtInBase() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是这台安卓手机(720x1600 竖屏)的智能体。\n")
          .append("观察方式: 每轮自动给你【屏幕文本(无障碍)】。仅当你明确需要看画面(图片/画布/排版/验证码)时输出 look，下一轮会附带截图。截图很贵，能用文本解决就不要 look。\n")
          .append("只输出一行 JSON，不要任何解释。\n")
          .append("规则:\n")
          .append("1. 每轮一个动作。能用 ①/② 解决就绝不 ③，绝不为找图标去翻桌面(用 open_app)。\n")
          .append("2. 文件/网页/应用类任务(找文件、读改文件、搜索)必须用 shell 工具直接做，禁止用文件管理器/浏览器界面一步步点。\n")
          .append("3. 敏感操作(支付/转账/删除/对外发消息/改系统设置)必须先 ask_user 征得同意，未经同意不得执行。file_write 覆盖已有文件前先 file_read 确认。\n")
          .append("4. 同一画面连续 3 次无效或无法判断时，ask_user。\n")
          .append("5. 任务收尾(必须遵守): 当任务目标已达成，或确认无法继续(权限不足/条件缺失/用户要求停止)时，必须在当轮立即输出 done(message=结果说明) 或 terminate(message=终止原因) 来结束任务；结束前严禁再输出 wait/look/观察类动作拖延，也严禁在任务实际已结束后继续循环。\n")
          .append("6. 遇到有价值的新错误(权限/参数/路径/系统行为)，用 exp_record 简要记录(标题+错误+解决办法，可附截图)；执行陌生任务前先 exp_search 查前人经验。常规成功操作不要记录。\n")
          .append("7. 需要用自然语言向用户解释思考/进展/结论时，用 say 动作(每轮一句，会自动分段显示在对话里)，不要只闷头操作。\n")
          .append("8. 信息检索(对话记忆搜索): \n")
          .append("   - 想了解 用户长期偏好 / 历史设定 / 习惯 / 跨对话背景 → chat_search scope=same_agent_all，在本 Agent 所有对话中搜。\n")
          .append("   - 想了解 当前对话用户给的任务目标 / 临时规则 / 即时上下文 → chat_search scope=this_session，仅在当前对话搜。\n")
          .append("   - 结果一定带【时间戳 / 所属对话 / 发送者(精确到Agent名称)】，必要时直接引用来源对话名。命中过多时缩小关键词或加上时间范围。\n")
          .append("示例(\"打开Aloha搜索原神\"): open_app→web_open https://www.bing.com/search?q=原神→wait→a11y_text 读结果。\n")
          .append("示例(\"找Download里原神相关文件\"): file_search 原神。\n")
          .append("示例(\"更新 genshin.txt\"): file_read→(web_open 查新闻→a11y_text 读取)→file_write。");
        return sb.toString();
    }

    /** Action docs for this agent, honoring its permission grants (req 6). */
    public static String toolDocs(ModelInfo m) {
        ModelInfo p = (m == null) ? new ModelInfo() : m; // null = all defaults (on)
        StringBuilder sb = new StringBuilder();
        sb.append("可用动作(按优先级排序):\n");

        sb.append("① 零成本工具(不动屏幕，永远优先):\n");
        sb.append(" {\"action\":\"say\",\"message\":\"...\"} 在对话里向用户说话(思考/进展/结论；可每轮说一句，长句会自动分段显示)\n");
        sb.append(" {\"action\":\"chat_search\",\"query\":\"关键词\",\"scope\":\"this_session\",\"from_ts\":0,\"to_ts\":9999999999999,\"role\":\"any\",\"sender_agent_ids\":[\"agentId\"]}\n");
        sb.append("    → 对话记忆搜索 (每轮可调用一次，免费直接用，无需权限)：\n");
        sb.append("      scope: this_session=仅当前对话; same_agent_all=同Agent全部对话\n");
        sb.append("      role: user=只搜用户说的话; agent=只搜Agent说的话; any=二者皆搜（默认）\n");
        sb.append("      sender_agent_ids: role=agent 时只命中这些Agent id发送的话（跨Agent同型排查时特别有用，不填=全部）\n");
        sb.append("      返回格式(每行必带时间/所属对话/发送者): [2026-08-24 10:27:39] [对话:第二个] [qwen(id)] user: ...\n");
        if (p.permFile) {
            sb.append(" {\"action\":\"file_ls\",\"path\":\"Download\"} 列目录(/sdcard 下，相对或 /sdcard/ 前缀均可)\n")
              .append(" {\"action\":\"file_read\",\"path\":\"Documents/genshin.txt\"} 读文件内容\n")
              .append(" {\"action\":\"file_write\",\"path\":\"...\",\"content\":\"...\"} 写/覆盖文件\n")
              .append(" {\"action\":\"file_search\",\"pattern\":\"原神\"} 按文件名模糊搜索 /sdcard 全部\n");
        }
        sb.append(" {\"action\":\"web_open\",\"url\":\"https://...\"} 用默认浏览器直接打开网址(搜索就用搜索引擎URL，别手动敲搜索框)\n")
          .append(" {\"action\":\"app_list\",\"query\":\"aoha\"} 查已装应用包名\n")
          .append(" {\"action\":\"clipboard_set\",\"text\":\"...\"} / {\"action\":\"clipboard_get\"}\n");
        if (p.permShell) {
            sb.append(" {\"action\":\"shell\",\"cmd\":\"ls /sdcard/Download\"} 执行 shell 命令(需 Shizuku 通道；仅当以上工具不够用；禁止重启/卸载/擦除数据类命令)\n");
        }
        if (p.permExpWrite || p.permExpRead) {
            if (p.permExpWrite) {
                sb.append(" {\"action\":\"exp_record\",\"title\":\"简短标题\",\"content\":\"错误/经验描述+解决办法\",\"shots\":2} 向全局经验池记录一条经验(shots=0~9 最近截图张数，缺省自动带2张)\n")
                  .append(" {\"action\":\"exp_delete\",\"id\":\"经验id\"} 或 {\"action\":\"exp_delete\",\"title\":\"标题\"} 删除本 Agent 自己记录的经验(只能删自己的)\n");
            }
            if (p.permExpRead) {
                sb.append(" {\"action\":\"exp_search\",\"query\":\"关键词\"} 搜索全局经验池(查前人记录的经验)\n");
            }
        }

        if (p.permA11y) {
            sb.append("② 文本 UI 操作(便宜，优先于坐标):\n")
              .append(" {\"action\":\"tap_text\",\"query\":\"设置\"} 找含该文字的控件并点击\n")
              .append(" {\"action\":\"tap_idx\",\"idx\":3} 点当前屏幕文本里第3个节点\n")
              .append(" {\"action\":\"a11y_text\"} 强制刷新屏幕文本树\n")
              .append(" {\"action\":\"open_app\",\"package\":\"...\"} 直接打开应用\n")
              .append(" {\"action\":\"type\",\"text\":\"...\"} 向聚焦输入框写文字\n")
              .append(" {\"action\":\"key\",\"key\":\"back|home|recents\"}\n");
        }

        sb.append("③ 视觉动作(贵，仅在文本无效/需要看画面时用):\n")
          .append(" {\"action\":\"look\"} 请求下一轮带截图\n");
        if (p.permA11y) {
            sb.append(" {\"action\":\"tap\",\"x\":0~1,\"y\":0~1} 点击(相对坐标)\n")
              .append(" {\"action\":\"double_tap\",\"x\":..,\"y\":..}\n")
              .append(" {\"action\":\"swipe\",\"x1\":..,\"y1\":..,\"x2\":..,\"y2\":..,\"ms\":300}\n");
        }
        sb.append(" {\"action\":\"wait\",\"ms\":2000}\n")
          .append("结束(任务实际完成时必须当轮输出): {\"action\":\"done\",\"message\":\"结果说明\"}；终止(无法继续/用户叫停): {\"action\":\"terminate\",\"message\":\"原因\"}；提问: {\"action\":\"ask_user\",\"question\":\"...\"}");
        return sb.toString();
    }
}
