package com.sysmon.aidl;

interface ISysShell {
    String exec(String cmd);
    String readFiles(in String[] paths);
}
