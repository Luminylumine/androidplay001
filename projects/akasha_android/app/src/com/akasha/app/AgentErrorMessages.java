package com.akasha.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Central error_code → human-readable Chinese hints (照抄 ChatGPT spec v2).
 * Two layers: for the LLM (next-step guidance) and for the user (chat ⚠ bubble).
 *
 * Usage:
 *   AgentErrorMessages.getForLLM(code, detail)  // passed back in observation
 *   AgentErrorMessages.getForUser(code, detail) // shown in chat as ⚠ warning
 */
public final class AgentErrorMessages {

    private AgentErrorMessages() {}

    private static final Map<String, String> LLM_MSG;
    private static final Map<String, String> USER_MSG;
    static {
        Map<String, String> l = new HashMap<>();
        Map<String, String> u = new HashMap<>();

        // ---- File ----
        p(l, u, AgentErrorCodes.AGENT_FILE_PERMISSION_DENIED,
                "本 Agent 无文件访问权限；到通讯录→Agent 设置→权限管理开启文件/媒体",
                "该 Agent 无文件权限");
        p(l, u, AgentErrorCodes.FILE_PATH_EMPTY,
                "file_ls/read/write 需要 path 参数，请补全后重试",
                "参数缺失：需要路径");
        p(l, u, AgentErrorCodes.FILE_PATH_OUT_OF_SCOPE,
                "路径越界，仅允许 /sdcard 内操作，如需更多目录请用 shell",
                "路径不在允许范围内");
        p(l, u, AgentErrorCodes.FILE_DIR_NOT_FOUND,
                "目录不存在；请先 file_ls 上级目录确认准确路径再操作",
                "目录不存在：{0}");
        p(l, u, AgentErrorCodes.FILE_DIR_READ_DENIED,
                "该目录无读取权限；请检查存储权限授予状态或换其他目录",
                "目录无权限：{0}");
        p(l, u, AgentErrorCodes.FILE_NOT_FOUND,
                "文件不存在；请先 file_ls 上级目录确认准确路径再操作",
                "文件不存在：{0}");
        p(l, u, AgentErrorCodes.FILE_READ_FAILED,
                "读取失败，可能文件被占用/编码异常/权限不足；换 file_search 或 shell 读原路径",
                "读取失败：{0}");
        p(l, u, AgentErrorCodes.FILE_PARENT_NOT_FOUND,
                "父目录不存在；请先 file_ls 确认，必要时 file_mkdir 创建目录",
                "父目录不存在：{0}");
        p(l, u, AgentErrorCodes.FILE_WRITE_FAILED,
                "写入失败（只读分区/空间不足/无写权限）；换可写目录或 shell 覆盖原文件",
                "写入失败：{0}");
        p(l, u, AgentErrorCodes.FILE_SEARCH_PATTERN_EMPTY,
                "file_search 需要 pattern 参数，请补全关键字后重试",
                "参数缺失：需要搜索关键字");
        p(l, u, AgentErrorCodes.FILE_STORAGE_PERMISSION_DENIED,
                "存储权限未授予；可在设置页授权存储后重试",
                "无存储权限");
        p(l, u, AgentErrorCodes.FILE_SEARCH_NO_MATCH,
                "未匹配到文件；换更短/更泛的关键字，或先 file_ls 定位目录",
                "未找到：{0}");
        p(l, u, AgentErrorCodes.FILE_CATEGORY_PHOTO_DENIED,
                "本 Agent 无相册访问权限；到通讯录→Agent 设置→权限管理开启",
                "相册权限未授予");
        p(l, u, AgentErrorCodes.FILE_CATEGORY_MEDIA_DENIED,
                "本 Agent 无媒体访问权限；到通讯录→Agent 设置→权限管理开启",
                "媒体权限未授予");
        p(l, u, AgentErrorCodes.FILE_CATEGORY_MUSIC_DENIED,
                "本 Agent 无音乐访问权限；到通讯录→Agent 设置→权限管理开启",
                "音乐权限未授予");

        // ---- Shell ----
        p(l, u, AgentErrorCodes.SHELL_AGENT_PERMISSION_DENIED,
                "本 Agent 未授予 Shizuku Shell 权限；到通讯录→Agent 设置→权限管理开启",
                "该 Agent 无 Shell 权限");
        p(l, u, AgentErrorCodes.SHELL_CHANNEL_UNAVAILABLE,
                "Shizuku 通道不可用（进程未运行/未授权）；到设置页处理后重试",
                "Shell 通道不可用");
        p(l, u, AgentErrorCodes.SHELL_CMD_EMPTY,
                "shell 需要 cmd 参数，请补全命令后重试",
                "参数缺失：需要命令");
        p(l, u, AgentErrorCodes.SHELL_DANGEROUS_COMMAND_REJECTED,
                "命中高危命令黑名单（重启/卸载/擦除类）；请改为安全命令",
                "命令已拦截：高危");
        p(l, u, AgentErrorCodes.SHELL_CHANNEL_DISCONNECTED,
                "通道已断开，Shizuku 进程可能被系统回收；去设置页重新授权/启动服务后重试",
                "Shell 通道已断开");
        p(l, u, AgentErrorCodes.SHELL_UID_NOT_SHELL,
                "当前 shell 通道为 app 级 uid，系统命令可能受限；建议优先用文件/无障碍接口",
                "Shell 通道权限较低（uid {0}）");

        // ---- A11Y ----
        p(l, u, AgentErrorCodes.AGENT_A11Y_PERMISSION_DENIED,
                "本 Agent 无无障碍权限；到通讯录→Agent 设置→权限管理开启",
                "该 Agent 无无障碍权限");
        p(l, u, AgentErrorCodes.A11Y_NOT_ENABLED,
                "无障碍服务未启用；到设置页开启无障碍后重试",
                "无障碍服务未启用");
        p(l, u, AgentErrorCodes.A11Y_TEXT_NOT_FOUND,
                "未找到匹配文本的控件；放宽关键字、切到 a11y_text 看页面结构或改用 tap/tap_idx",
                "未找到文本：{0}");
        p(l, u, AgentErrorCodes.A11Y_INDEX_OUT_OF_RANGE,
                "索引越界；先 a11y_text 看页面结构拿到合法索引范围再 tap_idx",
                "索引越界");
        p(l, u, AgentErrorCodes.A11Y_NO_FOCUSED_INPUT,
                "没有聚焦的输入框；先 tap 到输入控件（确保激活光标）再 type",
                "无聚焦输入框");
        p(l, u, AgentErrorCodes.A11Y_KEY_FAILED,
                "key 失败；确认控件状态或改用手势/无障碍点击方式",
                "按键失败");
        p(l, u, AgentErrorCodes.A11Y_TAP_FAILED,
                "tap 失败；确认坐标在屏幕范围内或改用 tap_text/tap_idx",
                "点击失败");
        p(l, u, AgentErrorCodes.A11Y_DOUBLE_TAP_FAILED,
                "double_tap 失败；确认坐标在屏幕范围内",
                "双击失败");
        p(l, u, AgentErrorCodes.A11Y_SWIPE_FAILED,
                "swipe 失败；确认起止坐标在屏幕范围内",
                "滑动失败");
        p(l, u, AgentErrorCodes.A11Y_OPEN_APP_FAILED,
                "open_app 失败；到设置页确认包名列表，或先安装目标应用",
                "无法打开应用：{0}");
        p(l, u, AgentErrorCodes.A11Y_OPEN_APP_EXCEPTION,
                "打开应用异常；换包名或改用 tap_text 启动目标应用",
                "打开应用异常：{0}");

        // ---- Experience pool ----
        p(l, u, AgentErrorCodes.EXP_NO_WRITE_PERM,
                "无写入经验池权限；到通讯录→Agent 设置→权限管理开启写入全局经验池",
                "无经验池写入权限");
        p(l, u, AgentErrorCodes.EXP_NO_READ_PERM,
                "无读取经验池权限；到通讯录→Agent 设置→权限管理开启读取全局经验池",
                "无经验池读取权限");
        p(l, u, AgentErrorCodes.EXP_RECORD_MISSING_CONTENT,
                "exp_record 需要 title 或 content，至少填一项再重试",
                "参数缺失：title/content");
        p(l, u, AgentErrorCodes.EXP_SEARCH_QUERY_EMPTY,
                "exp_search 需要 query 关键字，请补全后重试",
                "参数缺失：query");
        p(l, u, AgentErrorCodes.EXP_SEARCH_NO_MATCH,
                "未匹配到经验；换更泛的关键字，或先 file_search 定位后再记录",
                "未找到：{0}");
        p(l, u, AgentErrorCodes.EXP_DELETE_TARGET_EMPTY,
                "exp_delete 需要 id 或 title（只能删自己的），请补全参数再重试",
                "参数缺失：id/title");
        p(l, u, AgentErrorCodes.EXP_DELETE_NOT_OWNED_OR_NOT_FOUND,
                "未找到本 Agent 可删除的经验（id/title 不匹配或不属于本 Agent）",
                "无可删除的经验");

        // ---- LLM / Action ----
        p(l, u, AgentErrorCodes.LLM_EMPTY_OUTPUT,
                "模型输出为空；请重试调用或减少上下文长度后再试",
                "模型无输出");
        p(l, u, AgentErrorCodes.LLM_NO_JSON_ACTION,
                "输出中未找到 JSON 动作；请严格按 JSON 指令集输出 1 行动作",
                "输出未含 JSON 动作");
        p(l, u, AgentErrorCodes.LLM_JSON_INVALID,
                "JSON 损坏或字段缺失；请严格按指令集中 schema 输出",
                "JSON 解析失败");
        p(l, u, AgentErrorCodes.DEVICE_LOCK_REQUIRED,
                "执行该设备操作前必须先输出 acquire_device；获取后再读取或操作手机",
                "需先获取手机操作锁");
        p(l, u, AgentErrorCodes.DEVICE_LOCK_HELD,
                "手机操作锁正由 {0} 持有；不要执行设备动作，可 wait 后再次 acquire_device，或处理不依赖手机的任务",
                "手机正由其他会话操作：{0}");
        p(l, u, AgentErrorCodes.TASK_CANCELLED,
                "当前任务已被取消；不要继续执行动作",
                "任务已取消");
        p(l, u, AgentErrorCodes.ACTION_UNKNOWN,
                "未知动作 {0}；请从指令集列表中选合法动作",
                "未知动作：{0}");
        p(l, u, AgentErrorCodes.ACTION_EXECUTION_EXCEPTION,
                "动作执行异常 {0}；重试或换等价的其它动作完成任务",
                "执行异常：{0}");

        LLM_MSG = Collections.unmodifiableMap(l);
        USER_MSG = Collections.unmodifiableMap(u);
    }

    private static void p(Map<String, String> l, Map<String, String> u,
                          String code, String llm, String user) {
        l.put(code, llm);
        u.put(code, user);
    }

    public static String getForLLM(String code, String detail) {
        String tpl = LLM_MSG.get(code);
        if (tpl == null) return "错误 " + code + (detail == null ? "" : ": " + detail);
        return format(tpl, detail == null ? "" : detail);
    }

    public static String getForUser(String code, String detail) {
        String tpl = USER_MSG.get(code);
        if (tpl == null) return "操作失败" + (detail == null ? "" : ": " + detail);
        return format(tpl, detail == null ? "" : detail);
    }

    private static String format(String tpl, String d) {
        if (!tpl.contains("{0}")) return d.isEmpty() ? tpl : (tpl + "：" + d);
        return String.format(Locale.ROOT, tpl, d);
    }
}
