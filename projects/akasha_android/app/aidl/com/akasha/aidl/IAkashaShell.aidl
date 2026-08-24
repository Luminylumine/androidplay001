package com.akasha.aidl;

/**
 * Executed inside the privileged process (Shizuku shell / Dhizuku), so
 * exec() runs with shell (uid 2000) rights.
 */
interface IAkashaShell {
    String exec(String cmd);
}
