package com.akasha.app;

/**
 * Stable, machine-readable error codes for agent tool calls (per ChatGPT spec).
 * The LLM decides on these codes; the display layer maps them to text via
 * {@link AgentErrorMessages}. Never embed dynamic values (paths, package names)
 * into a code - put them in the result's detail field instead.
 */
public final class AgentErrorCodes {

    private AgentErrorCodes() {}

    // ---- File ----
    public static final String AGENT_FILE_PERMISSION_DENIED = "AGENT_FILE_PERMISSION_DENIED";
    public static final String FILE_PATH_EMPTY = "FILE_PATH_EMPTY";
    public static final String FILE_PATH_OUT_OF_SCOPE = "FILE_PATH_OUT_OF_SCOPE";
    public static final String FILE_DIR_NOT_FOUND = "FILE_DIR_NOT_FOUND";
    public static final String FILE_DIR_READ_DENIED = "FILE_DIR_READ_DENIED";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String FILE_READ_FAILED = "FILE_READ_FAILED";
    public static final String FILE_PARENT_NOT_FOUND = "FILE_PARENT_NOT_FOUND";
    public static final String FILE_WRITE_FAILED = "FILE_WRITE_FAILED";
    public static final String FILE_SEARCH_PATTERN_EMPTY = "FILE_SEARCH_PATTERN_EMPTY";
    public static final String FILE_STORAGE_PERMISSION_DENIED = "FILE_STORAGE_PERMISSION_DENIED";
    public static final String FILE_SEARCH_NO_MATCH = "FILE_SEARCH_NO_MATCH";
    public static final String FILE_CATEGORY_PHOTO_DENIED = "FILE_CATEGORY_PHOTO_DENIED";
    public static final String FILE_CATEGORY_MEDIA_DENIED = "FILE_CATEGORY_MEDIA_DENIED";
    public static final String FILE_CATEGORY_MUSIC_DENIED = "FILE_CATEGORY_MUSIC_DENIED";

    // ---- Shell ----
    public static final String SHELL_AGENT_PERMISSION_DENIED = "SHELL_AGENT_PERMISSION_DENIED";
    public static final String SHELL_CHANNEL_UNAVAILABLE = "SHELL_CHANNEL_UNAVAILABLE";
    public static final String SHELL_CMD_EMPTY = "SHELL_CMD_EMPTY";
    public static final String SHELL_DANGEROUS_COMMAND_REJECTED = "SHELL_DANGEROUS_COMMAND_REJECTED";
    public static final String SHELL_CHANNEL_DISCONNECTED = "SHELL_CHANNEL_DISCONNECTED";
    public static final String SHELL_UID_NOT_SHELL = "SHELL_UID_NOT_SHELL";

    // ---- Accessibility ----
    public static final String AGENT_A11Y_PERMISSION_DENIED = "AGENT_A11Y_PERMISSION_DENIED";
    public static final String A11Y_NOT_ENABLED = "A11Y_NOT_ENABLED";
    public static final String A11Y_TEXT_NOT_FOUND = "A11Y_TEXT_NOT_FOUND";
    public static final String A11Y_INDEX_OUT_OF_RANGE = "A11Y_INDEX_OUT_OF_RANGE";
    public static final String A11Y_NO_FOCUSED_INPUT = "A11Y_NO_FOCUSED_INPUT";
    public static final String A11Y_KEY_FAILED = "A11Y_KEY_FAILED";
    public static final String A11Y_TAP_FAILED = "A11Y_TAP_FAILED";
    public static final String A11Y_DOUBLE_TAP_FAILED = "A11Y_DOUBLE_TAP_FAILED";
    public static final String A11Y_SWIPE_FAILED = "A11Y_SWIPE_FAILED";
    public static final String A11Y_OPEN_APP_FAILED = "A11Y_OPEN_APP_FAILED";
    public static final String A11Y_OPEN_APP_EXCEPTION = "A11Y_OPEN_APP_EXCEPTION";

    // ---- Experience pool ----
    public static final String EXP_NO_WRITE_PERM = "EXP_NO_WRITE_PERM";
    public static final String EXP_NO_READ_PERM = "EXP_NO_READ_PERM";
    public static final String EXP_RECORD_MISSING_CONTENT = "EXP_RECORD_MISSING_CONTENT";
    public static final String EXP_SEARCH_QUERY_EMPTY = "EXP_SEARCH_QUERY_EMPTY";
    public static final String EXP_SEARCH_NO_MATCH = "EXP_SEARCH_NO_MATCH";
    public static final String EXP_DELETE_TARGET_EMPTY = "EXP_DELETE_TARGET_EMPTY";
    public static final String EXP_DELETE_NOT_OWNED_OR_NOT_FOUND = "EXP_DELETE_NOT_OWNED_OR_NOT_FOUND";

    // ---- LLM / Action ----
    public static final String LLM_EMPTY_OUTPUT = "LLM_EMPTY_OUTPUT";
    public static final String LLM_NO_JSON_ACTION = "LLM_NO_JSON_ACTION";
    public static final String LLM_JSON_INVALID = "LLM_JSON_INVALID";
    public static final String ACTION_UNKNOWN = "ACTION_UNKNOWN";
    public static final String ACTION_EXECUTION_EXCEPTION = "ACTION_EXECUTION_EXCEPTION";
    public static final String DEVICE_LOCK_REQUIRED = "DEVICE_LOCK_REQUIRED";
    public static final String DEVICE_LOCK_HELD = "DEVICE_LOCK_HELD";
    public static final String TASK_CANCELLED = "TASK_CANCELLED";

    /** Codes that are "no match" rather than real failures (no ⚠ in chat). */
    public static boolean isNoMatch(String code) {
        return FILE_SEARCH_NO_MATCH.equals(code) || EXP_SEARCH_NO_MATCH.equals(code);
    }
}
