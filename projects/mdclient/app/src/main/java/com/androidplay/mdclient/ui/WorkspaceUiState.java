package com.androidplay.mdclient.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** UI projection only; persistence remains owned by the existing core services. */
public final class WorkspaceUiState {
    public final List<String> openCourseTabs = new ArrayList<>(Arrays.asList("现代控制理论", "计算机网络", "运筹学"));
    public String activeCourseId = "现代控制理论";
    public float topRatio = .5f;
    public float pdfRatio = .5f;
    public float logicRatio = .22f;
    public float agentRatio = .34f;
    public boolean drawerOpen;
    public boolean tocOpen;
    public String focusedPane = "";
    public boolean suggestionRailOpen = true;
    public boolean materialLinkMode;
}
