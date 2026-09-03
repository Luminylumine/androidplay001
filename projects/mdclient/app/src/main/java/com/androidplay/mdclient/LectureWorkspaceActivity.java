package com.androidplay.mdclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.androidplay.mdclient.material.PdfMaterialController;
import com.androidplay.mdclient.ui.ScratchpadState;
import com.androidplay.mdclient.ui.WorkspaceRatioPolicy;
import com.androidplay.mdclient.ui.WorkspaceUiState;

import java.util.ArrayList;
import java.util.List;

/** Sketch-driven tablet workspace. This Activity is a projection over the frozen core. */
public final class LectureWorkspaceActivity extends Activity {
    private static final int OPEN_PDF = 41;
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int LINE = Color.rgb(224, 229, 236);
    private static final int TEXT = Color.rgb(31, 36, 45);
    private static final String PREFS = "lecture_workspace_ui";
    private final WorkspaceUiState ui = new WorkspaceUiState();
    private final ScratchpadState scratchpad = new ScratchpadState();
    private final Handler handler = new Handler();
    private LinearLayout top;
    private LinearLayout bottom;
    private LinearLayout pdfPane;
    private LinearLayout notesPane;
    private LinearLayout logicPane;
    private LinearLayout agentPane;
    private LinearLayout scratchPane;
    private PdfMaterialController pdf;
    private ImageView pdfImage;
    private TextView pdfPage;
    private TextView agentTarget;
    private EditText scratchEdit;
    private FrameLayout root;
    private View drawer;
    private android.content.SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        pdf = new PdfMaterialController(this);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadRatios();
        scratchpad.text = preferences.getString("scratchpad.demo-session", scratchpad.text);
        buildWorkspace();
    }

    private void buildWorkspace() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.WHITE);
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(54)));
        top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setPadding(dp(10), dp(8), dp(10), dp(4));
        bottom = new LinearLayout(this); bottom.setOrientation(LinearLayout.HORIZONTAL); bottom.setPadding(dp(10), dp(4), dp(10), dp(10));
        buildTopPanes(); buildBottomPanes();
        shell.addView(top, new LinearLayout.LayoutParams(-1, 0, ui.topRatio));
        shell.addView(new SplitHandle(true, delta -> resizeTop(delta)), new LinearLayout.LayoutParams(-1, dp(12)));
        shell.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1f - ui.topRatio));
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12), 0, dp(10), 0); bar.setBackgroundColor(Color.WHITE);
        Button menu = compact("☰", v -> toggleDrawer()); bar.addView(menu, new LinearLayout.LayoutParams(dp(44), -1));
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this); tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this); tabs.setGravity(Gravity.CENTER_VERTICAL);
        for (String course : ui.openCourseTabs) addTab(tabs, course);
        tabsScroll.addView(tabs); bar.addView(tabsScroll, new LinearLayout.LayoutParams(0, -1, 1));
        TextView state = label("● 录音中   ASR --   Agent Offline", 12, Color.rgb(65, 75, 88)); state.setGravity(Gravity.CENTER_VERTICAL); bar.addView(state, new LinearLayout.LayoutParams(dp(230), -1));
        return bar;
    }

    private void addTab(LinearLayout tabs, String title) {
        LinearLayout tab = new LinearLayout(this); tab.setGravity(Gravity.CENTER_VERTICAL); tab.setPadding(dp(10), 0, dp(2), 0);
        TextView name = label(title, 13, TEXT); name.setGravity(Gravity.CENTER_VERTICAL); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setSingleLine(true);
        tab.addView(name, new LinearLayout.LayoutParams(dp(135), -1));
        tab.addView(compact("×", v -> { tabs.removeView(tab); if (title.equals(ui.activeCourseId) && tabs.getChildCount() > 0) tabs.getChildAt(0).performClick(); }), new LinearLayout.LayoutParams(dp(34), -1));
        View.OnClickListener select = v -> { saveScratchpad(); ui.activeCourseId = title; scratchpad.sessionId = title; loadRatios(); scratchpad.text = preferences.getString("scratchpad." + scratchpad.sessionId, ""); scratchEdit.setText(scratchpad.text); updateOuterWeights(); updateWeight(top, 0, ui.pdfRatio); updateWeight(top, 2, 1 - ui.pdfRatio); updateWeight(bottom, 0, ui.logicRatio); updateWeight(bottom, 2, ui.agentRatio); updateWeight(bottom, 4, 1 - ui.logicRatio - ui.agentRatio); for (int i=0;i<tabs.getChildCount();i++) tabs.getChildAt(i).setBackgroundColor(Color.WHITE); tab.setBackgroundColor(Color.rgb(239,245,255)); showStatus("课程已切换：" + title); };
        name.setOnClickListener(select); tab.setOnClickListener(select); tabs.addView(tab, new LinearLayout.LayoutParams(-2, -1));
    }

    private void buildTopPanes() {
        pdfPane = pane("课件", "独立  ·  Human p--");
        LinearLayout pdfHeader = (LinearLayout) pdfPane.getChildAt(0); pdfPage = (TextView) pdfHeader.getChildAt(1);
        pdfHeader.addView(compact("⛶", v -> focus("pdf")), new LinearLayout.LayoutParams(dp(42), dp(38)));
        pdfHeader.addView(compact("导入", v -> choosePdf()), new LinearLayout.LayoutParams(dp(58), dp(38)));
        pdfImage = new ImageView(this); pdfImage.setAdjustViewBounds(true); pdfImage.setBackgroundColor(Color.rgb(248, 249, 251));
        pdfPane.addView(pdfImage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this); controls.addView(compact("‹", v -> page(-1)), new LinearLayout.LayoutParams(0, dp(38), 1)); controls.addView(compact("›", v -> page(1)), new LinearLayout.LayoutParams(0, dp(38), 1));
        pdfPane.addView(controls); pdfPane.addView(label("未选择课件  ·  Agent p--", 11, Color.GRAY));
        notesPane = pane("已整理复习资料", "目录");
        LinearLayout notesHeader = (LinearLayout) notesPane.getChildAt(0); notesHeader.addView(compact("目录", v -> showToc()), new LinearLayout.LayoutParams(dp(62), dp(38))); notesHeader.addView(compact("⛶", v -> focus("notes")), new LinearLayout.LayoutParams(dp(42), dp(38)));
        ScrollView notes = new ScrollView(this); LinearLayout noteBody = new LinearLayout(this); noteBody.setOrientation(LinearLayout.VERTICAL);
        addNote(noteBody, "状态空间模型", 20, true); addNote(noteBody, "状态方程", 16, true); addNote(noteBody, "xdot = Ax + Bu", 16, false); addNote(noteBody, "能控性判据", 16, true); addNote(noteBody, "rank [ B  AB  ... ] = n", 16, false); addNote(noteBody, "Agent 更新：傅里叶级数  查看", 13, false);
        notes.addView(noteBody); notesPane.addView(notes, new LinearLayout.LayoutParams(-1, 0, 1));
        top.addView(pdfPane, new LinearLayout.LayoutParams(0, -1, ui.pdfRatio)); top.addView(new SplitHandle(false, delta -> resizePdf(delta)), new LinearLayout.LayoutParams(dp(12), -1)); top.addView(notesPane, new LinearLayout.LayoutParams(0, -1, 1f - ui.pdfRatio));
    }

    private void buildBottomPanes() {
        logicPane = pane("授课逻辑", "当前"); addLogic(logicPane);
        agentPane = pane("Agent Observable State", "最近"); addAgent(agentPane);
        scratchPane = pane("Human Scratchpad", "Human Attention"); addScratchpad(scratchPane);
        bottom.addView(logicPane, new LinearLayout.LayoutParams(0, -1, ui.logicRatio)); bottom.addView(new SplitHandle(false, delta -> resizeLogic(delta)), new LinearLayout.LayoutParams(dp(12), -1));
        bottom.addView(agentPane, new LinearLayout.LayoutParams(0, -1, ui.agentRatio)); bottom.addView(new SplitHandle(false, delta -> resizeAgent(delta)), new LinearLayout.LayoutParams(dp(12), -1));
        bottom.addView(scratchPane, new LinearLayout.LayoutParams(0, -1, 1f - ui.logicRatio - ui.agentRatio));
    }

    private void addLogic(LinearLayout pane) { LinearLayout body = body(); addNote(body, "正在讲解：能控性判据", 15, true); addNote(body, "分析题目条件", 13, false); addNote(body, "列出状态方程", 13, false); addNote(body, "推导 rank 条件", 13, true); addNote(body, "老师正在举以前学生的例子", 12, false); pane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private void addAgent(LinearLayout pane) { agentTarget = label("Agent attention: PDF p-- / Notes: 状态变量定义", 12, Color.rgb(55, 86, 150)); pane.addView(agentTarget); LinearLayout body = body(); addNote(body, "正在回看“状态变量定义”", 13, false); addNote(body, "检测到老师引用旧知识", 13, false); addNote(body, "等待公式更多证据", 13, false); addNote(body, "正在更新 block b-04", 13, false); addNote(body, "当前片段疑似闲聊", 13, false); pane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private void addScratchpad(LinearLayout pane) { LinearLayout header = (LinearLayout) pane.getChildAt(0); header.addView(compact("⛶", v -> focus("scratchpad")), new LinearLayout.LayoutParams(dp(42), dp(38))); LinearLayout area = new LinearLayout(this); area.setOrientation(LinearLayout.HORIZONTAL); SuggestionRail rail = new SuggestionRail(); area.addView(rail, new LinearLayout.LayoutParams(dp(118), -1)); scratchEdit = new EditText(this); scratchEdit.setText(scratchpad.text); scratchEdit.setTextColor(Color.BLACK); scratchEdit.setTextSize(16); scratchEdit.setGravity(Gravity.TOP); scratchEdit.setPadding(dp(14), dp(10), dp(10), dp(10)); scratchEdit.setBackgroundColor(Color.WHITE); scratchEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); scratchEdit.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){ scratchpad.text=s.toString(); scratchpad.lastModified=System.currentTimeMillis(); handler.removeCallbacks(saveScratchpadTask); handler.postDelayed(saveScratchpadTask, 500); } public void afterTextChanged(android.text.Editable e){} }); area.addView(scratchEdit, new LinearLayout.LayoutParams(0, -1, 1)); pane.addView(area, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private final Runnable saveScratchpadTask = this::saveScratchpad;
    private void saveScratchpad() { if (preferences != null) preferences.edit().putString("scratchpad." + scratchpad.sessionId, scratchpad.text).apply(); }

    private final class SuggestionRail extends LinearLayout { SuggestionRail() { super(LectureWorkspaceActivity.this); setOrientation(LinearLayout.VERTICAL); setPadding(dp(6), dp(4), dp(6), dp(4)); addView(label("建议  •", 12, BLUE)); TextView card = label("公式修正\nF(omega)=...\n\n[接受]  [忽略]", 12, TEXT); card.setPadding(dp(8), dp(12), dp(4), dp(8)); card.setOnClickListener(v -> acceptSuggestion("F(omega) = integral f(t) exp(-j omega t) dt")); card.setOnLongClickListener(v -> { v.startDragAndDrop(null, new View.DragShadowBuilder(v), "F(omega) = integral f(t) exp(-j omega t) dt", 0); return true; }); setOnDragListener((v,e) -> { if (e.getAction()==android.view.DragEvent.ACTION_DROP) { acceptSuggestion(String.valueOf(e.getLocalState())); return true; } return true; }); addView(card); } }
    private void acceptSuggestion(String text) { if (scratchEdit != null) { int at=scratchEdit.getSelectionStart(); if(at<0)at=scratchEdit.length(); scratchEdit.getText().insert(at, "\n" + text + "\n"); showStatus("建议已插入 Scratchpad"); } }

    private LinearLayout pane(String title, String trailing) { LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setBackgroundColor(Color.WHITE); LinearLayout h = new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL); h.setPadding(dp(8), 0, dp(4), 0); TextView t=label(title,13,TEXT); h.addView(t,new LinearLayout.LayoutParams(0,dp(38),1)); h.addView(label(trailing,11,Color.GRAY),new LinearLayout.LayoutParams(-2,dp(38))); p.addView(h); View line=new View(this); line.setBackgroundColor(LINE); p.addView(line,new LinearLayout.LayoutParams(-1,dp(1))); return p; }
    private LinearLayout body() { LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(8),dp(8),dp(8),dp(8)); return b; }
    private void addNote(LinearLayout body,String text,int size,boolean strong){ TextView v=label(text,size,strong?TEXT:Color.rgb(80,88,100)); v.setPadding(dp(8),dp(7),dp(4),dp(7)); if(strong)v.setBackgroundColor(Color.rgb(247,249,252)); body.addView(v); }
    private TextView label(String text,int size,int color){ TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v; }
    private Button compact(String text,View.OnClickListener click){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setTextColor(TEXT);b.setAllCaps(false);b.setMinHeight(0);b.setMinWidth(0);b.setPadding(dp(5),0,dp(5),0);b.setOnClickListener(click);return b;}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}

    private void choosePdf(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),OPEN_PDF);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==OPEN_PDF&&result==RESULT_OK&&data!=null)try{pdf.open(data.getData());renderPdf();}catch(Exception e){showStatus("课件打开失败");}}
    private void renderPdf(){if(!pdf.isOpen())return;try{pdfPage.setText("p"+(pdf.currentPage()+1)+"/"+pdf.pageCount());pdfImage.setImageBitmap(pdf.renderPageBitmap(900,1200));}catch(Exception e){showStatus("课件渲染失败");}}
    private void page(int delta){try{if(delta<0)pdf.previous();else pdf.next();renderPdf();}catch(Exception ignored){showStatus("请先导入课件");}}
    private void showToc(){ui.tocOpen=!ui.tocOpen;showStatus(ui.tocOpen?"目录打开：点击目录项只滚动 Notes":"目录关闭");}
    private void focus(String pane){boolean exit=ui.focusedPane.equals(pane);ui.focusedPane=exit?"":pane;if(exit){pdfPane.setVisibility(View.VISIBLE);notesPane.setVisibility(View.VISIBLE);logicPane.setVisibility(View.VISIBLE);agentPane.setVisibility(View.VISIBLE);scratchPane.setVisibility(View.VISIBLE);top.setVisibility(View.VISIBLE);bottom.setVisibility(View.VISIBLE);updateOuterWeights();}else{pdfPane.setVisibility(pane.equals("pdf")?View.VISIBLE:View.GONE);notesPane.setVisibility(pane.equals("notes")?View.VISIBLE:View.GONE);logicPane.setVisibility(pane.equals("logic")?View.VISIBLE:View.GONE);agentPane.setVisibility(pane.equals("agent")?View.VISIBLE:View.GONE);scratchPane.setVisibility(pane.equals("scratchpad")?View.VISIBLE:View.GONE);top.setVisibility(pane.equals("pdf")||pane.equals("notes")?View.VISIBLE:View.GONE);bottom.setVisibility(pane.equals("logic")||pane.equals("agent")||pane.equals("scratchpad")?View.VISIBLE:View.GONE);}showStatus(exit?"FocusPane 退出":"FocusPane: "+pane);}
    private void showStatus(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void toggleDrawer(){if(drawer!=null){root.removeView(drawer);drawer=null;return;} drawer=new LinearLayout(this);((LinearLayout)drawer).setOrientation(LinearLayout.VERTICAL);drawer.setBackgroundColor(Color.WHITE);drawer.setElevation(dp(8));drawer.setPadding(dp(18),dp(18),dp(12),dp(12)); String[] items={"新建课程","课程列表","最近课堂 Session","当前课程设置","导入 PDF","导出 Markdown","Diagnostics","打开旧 Debug Activity","Load UI Demo"};for(String item:items){TextView v=label(item,15,TEXT);v.setPadding(dp(4),dp(15),dp(20),dp(15));v.setOnClickListener(x->{if(item.startsWith("打开")){startActivity(new Intent(this,MdClientActivity.class));}else if(item.equals("导入 PDF")){choosePdf();}else if(item.equals("Load UI Demo")){showStatus("Demo Mode 已加载");}else showStatus(item);});((LinearLayout)drawer).addView(v);}FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(300),-1,Gravity.START);lp.topMargin=dp(54);root.addView(drawer,lp);}
    private void resizeTop(float delta){ui.topRatio=WorkspaceRatioPolicy.top(ui.topRatio+delta);updateOuterWeights();persistRatios();}
    private void resizePdf(float delta){ui.pdfRatio=WorkspaceRatioPolicy.pdf(ui.pdfRatio+delta);updateWeight(top,0,ui.pdfRatio);updateWeight(top,2,1-ui.pdfRatio);persistRatios();}
    private void resizeLogic(float delta){ui.logicRatio=WorkspaceRatioPolicy.clamp(ui.logicRatio+delta,.12f,1f-ui.agentRatio-.35f);updateWeight(bottom,0,ui.logicRatio);updateWeight(bottom,4,1-ui.logicRatio-ui.agentRatio);persistRatios();}
    private void resizeAgent(float delta){ui.agentRatio=WorkspaceRatioPolicy.clamp(ui.agentRatio+delta,.14f,1f-ui.logicRatio-.35f);updateWeight(bottom,2,ui.agentRatio);updateWeight(bottom,4,1-ui.logicRatio-ui.agentRatio);persistRatios();}
    private String ratioKey(String name){return "ratio." + ui.activeCourseId + "." + name;}
    private void loadRatios(){if(preferences==null)return;ui.topRatio=WorkspaceRatioPolicy.top(preferences.getFloat(ratioKey("top"),.5f));ui.pdfRatio=WorkspaceRatioPolicy.pdf(preferences.getFloat(ratioKey("pdf"),.5f));ui.logicRatio=WorkspaceRatioPolicy.clamp(preferences.getFloat(ratioKey("logic"),.22f),.12f,.51f);ui.agentRatio=WorkspaceRatioPolicy.clamp(preferences.getFloat(ratioKey("agent"),.34f),.14f,1f-ui.logicRatio-.35f);}
    private void persistRatios(){if(preferences!=null)preferences.edit().putFloat(ratioKey("top"),ui.topRatio).putFloat(ratioKey("pdf"),ui.pdfRatio).putFloat(ratioKey("logic"),ui.logicRatio).putFloat(ratioKey("agent"),ui.agentRatio).apply();}
    private void updateOuterWeights(){View shell=root.getChildAt(0);updateWeight((LinearLayout)shell,1,ui.topRatio);updateWeight((LinearLayout)shell,3,1-ui.topRatio);}
    private void updateWeight(LinearLayout parent,int index,float weight){if(index>=parent.getChildCount())return;View v=parent.getChildAt(index);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)v.getLayoutParams();lp.weight=Math.max(.01f,weight);v.setLayoutParams(lp);}

    private final class SplitHandle extends View { private final boolean horizontal; private final java.util.function.Consumer<Float> callback; private long down; private boolean armed; private float start; private final Runnable arm=()->{armed=true;performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);setBackgroundColor(BLUE);}; SplitHandle(boolean horizontal,java.util.function.Consumer<Float> callback){super(LectureWorkspaceActivity.this);this.horizontal=horizontal;this.callback=callback;setBackgroundColor(LINE);setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){down=System.currentTimeMillis();start=horizontal?e.getY():e.getX();armed=false;handler.postDelayed(arm,1500);return true;}if(e.getAction()==MotionEvent.ACTION_MOVE&&armed){float now=horizontal?e.getY():e.getX();ViewGroup parent=(ViewGroup)getParent();float size=horizontal?Math.max(1,parent.getHeight()):Math.max(1,parent.getWidth());callback.accept((now-start)/size);start=now;return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){handler.removeCallbacks(arm);armed=false;setBackgroundColor(LINE);return true;}return true;});} }
}
