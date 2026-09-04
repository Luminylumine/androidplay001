package com.androidplay.mdclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import com.androidplay.mdclient.material.PdfContinuousView;
import com.androidplay.mdclient.markdown.MarkdownRenderEngine;
import com.androidplay.mdclient.ui.ScratchpadState;
import com.androidplay.mdclient.ui.WorkspaceRatioPolicy;
import com.androidplay.mdclient.ui.WorkspaceUiState;
import com.androidplay.mdclient.whiteboard.WhiteboardSceneView;

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
    private PdfContinuousView pdfCanvas;
    private TextView pdfPage;
    private TextView agentTarget;
    private EditText notesEditor;
    private TextView notesPreview;
    private ScrollView notesScroll;
    private ScrollView notesPreviewScroll;
    private boolean notesPreviewMode;
    private WhiteboardSceneView whiteboard;
    private TextView whiteboardToolLabel;
    private TextView suggestionSticker;
    private FrameLayout agentLayer;
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
        shell.addView(new SplitHandle(true, delta -> resizeTop(delta)), new LinearLayout.LayoutParams(-1, dp(6)));
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
        View.OnClickListener select = v -> { saveScratchpad(); ui.activeCourseId = title; scratchpad.sessionId = title; loadRatios(); scratchpad.text = preferences.getString("scratchpad." + scratchpad.sessionId, ""); updateOuterWeights(); updateWeight(top, 0, ui.pdfRatio); updateWeight(top, 2, 1 - ui.pdfRatio); updateWeight(bottom, 0, ui.logicRatio); updateWeight(bottom, 2, ui.agentRatio); updateWeight(bottom, 4, 1 - ui.logicRatio - ui.agentRatio); for (int i=0;i<tabs.getChildCount();i++) tabs.getChildAt(i).setBackgroundColor(Color.WHITE); tab.setBackgroundColor(Color.rgb(239,245,255)); showStatus("课程已切换：" + title); };
        name.setOnClickListener(select); tab.setOnClickListener(select); tabs.addView(tab, new LinearLayout.LayoutParams(-2, -1));
    }

    private void buildTopPanes() {
        pdfPane = pane("课件", "独立  ·  Human p--");
        LinearLayout pdfHeader = (LinearLayout) pdfPane.getChildAt(0); pdfPage = (TextView) pdfHeader.getChildAt(1);
        pdfHeader.addView(compact("⛶", v -> focus("pdf")), new LinearLayout.LayoutParams(dp(42), dp(38)));
        pdfHeader.addView(compact("导入", v -> choosePdf()), new LinearLayout.LayoutParams(dp(58), dp(38)));
        pdfCanvas = new PdfContinuousView(this, pdf); pdfPane.addView(pdfCanvas, new LinearLayout.LayoutParams(-1, 0, 1));
        pdfPane.addView(label("上下滑动翻页  ·  Agent p--", 10, Color.GRAY));
        notesPane = pane("已整理复习资料", "人类优先");
        LinearLayout notesHeader = (LinearLayout) notesPane.getChildAt(0); notesHeader.addView(compact("目录", v -> showToc()), new LinearLayout.LayoutParams(dp(62), dp(28))); notesHeader.addView(compact("预览", v -> toggleNotesPreview()), new LinearLayout.LayoutParams(dp(62), dp(28))); notesHeader.addView(compact("⛶", v -> focus("notes")), new LinearLayout.LayoutParams(dp(42), dp(28)));
        notesEditor = new EditText(this); notesEditor.setText("# 状态空间模型\n\n## 状态方程\n\n$\\dot{x} = Ax + Bu$\n\n## 能控性判据\n\n$\\operatorname{rank}[B\\ AB\\ ...] = n$\n\nAgent 更新：傅里叶级数"); notesEditor.setTextSize(16); notesEditor.setTextColor(TEXT); notesEditor.setGravity(Gravity.TOP | Gravity.START); notesEditor.setPadding(dp(14), dp(10), dp(14), dp(10)); notesEditor.setBackgroundColor(Color.WHITE); notesEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); notesEditor.setHorizontallyScrolling(false); notesEditor.setMinHeight(dp(900)); notesEditor.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) showStatus("Human 正在编辑资料"); }); notesScroll = new ScrollView(this); notesScroll.setFillViewport(true); notesScroll.addView(notesEditor, new ScrollView.LayoutParams(-1, -2)); notesEditor.setOnDragListener(this::dropIntoNotes); notesScroll.setOnDragListener(this::dropIntoNotes); notesPane.addView(notesScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        top.addView(pdfPane, new LinearLayout.LayoutParams(0, -1, ui.pdfRatio)); top.addView(new SplitHandle(false, delta -> resizePdf(delta)), new LinearLayout.LayoutParams(dp(6), -1)); top.addView(notesPane, new LinearLayout.LayoutParams(0, -1, 1f - ui.pdfRatio));
    }

    private void buildBottomPanes() {
        logicPane = pane("授课逻辑", "当前"); addLogic(logicPane);
        agentPane = pane("Agent Observable State", "最近"); addAgent(agentPane);
        scratchPane = pane("白板", "手写"); addScratchpad(scratchPane);
        bottom.addView(logicPane, new LinearLayout.LayoutParams(0, -1, ui.logicRatio)); bottom.addView(new SplitHandle(false, delta -> resizeLogic(delta)), new LinearLayout.LayoutParams(dp(6), -1));
        bottom.addView(agentPane, new LinearLayout.LayoutParams(0, -1, ui.agentRatio)); bottom.addView(new SplitHandle(false, delta -> resizeAgent(delta)), new LinearLayout.LayoutParams(dp(6), -1));
        bottom.addView(scratchPane, new LinearLayout.LayoutParams(0, -1, 1f - ui.logicRatio - ui.agentRatio));
    }

    private void addLogic(LinearLayout pane) { LinearLayout body = body(); addNote(body, "正在讲解：能控性判据", 15, true); addNote(body, "分析题目条件", 13, false); addNote(body, "列出状态方程", 13, false); addNote(body, "推导 rank 条件", 13, true); addNote(body, "老师正在举以前学生的例子", 12, false); pane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private void addAgent(LinearLayout pane) { agentTarget = label("Agent attention: PDF p-- / Notes: 状态变量定义", 10, Color.rgb(55, 86, 150)); pane.addView(agentTarget); LinearLayout header = (LinearLayout) pane.getChildAt(0); header.addView(compact("生成贴纸", v -> showSticker()), new LinearLayout.LayoutParams(dp(78), dp(28))); agentLayer = new FrameLayout(this); LinearLayout log = body(); addNote(log, "正在回看“状态变量定义”", 12, false); addNote(log, "检测到老师引用旧知识", 12, false); addNote(log, "等待公式更多证据", 12, false); addNote(log, "正在更新 block b-04", 12, false); addNote(log, "当前片段疑似闲聊", 12, false); agentLayer.addView(log, new FrameLayout.LayoutParams(-1, -1)); pane.addView(agentLayer, new LinearLayout.LayoutParams(-1, 0, 1)); showSticker(); }
    private void addScratchpad(LinearLayout pane) { LinearLayout header = (LinearLayout) pane.getChildAt(0); header.addView(compact("⛶", v -> focus("scratchpad")), new LinearLayout.LayoutParams(dp(42), dp(28))); whiteboardToolLabel = label("笔",10,Color.GRAY); header.addView(whiteboardToolLabel, new LinearLayout.LayoutParams(dp(28), dp(28))); header.addView(compact("文本框", v -> setWhiteboardTool(WhiteboardSceneView.Tool.TEXT)), new LinearLayout.LayoutParams(dp(68), dp(28))); header.addView(compact("颜色", v -> showPenPalette()), new LinearLayout.LayoutParams(dp(52), dp(28))); header.addView(compact("像素擦除", v -> setWhiteboardTool(WhiteboardSceneView.Tool.PIXEL_ERASER)), new LinearLayout.LayoutParams(dp(78), dp(28))); header.addView(compact("整笔擦除", v -> setWhiteboardTool(WhiteboardSceneView.Tool.STROKE_ERASER)), new LinearLayout.LayoutParams(dp(78), dp(28))); whiteboard = new WhiteboardSceneView(this); pane.addView(whiteboard, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private final Runnable saveScratchpadTask = this::saveScratchpad;
    private void saveScratchpad() { if (preferences != null) preferences.edit().putString("scratchpad." + scratchpad.sessionId, scratchpad.text).apply(); }

    private void showSticker() { if (agentLayer == null) return; if (suggestionSticker != null && suggestionSticker.getParent() != null) agentLayer.removeView(suggestionSticker); suggestionSticker = sticker("公式修正\n$F(\\omega)=...$", "$F(\\omega) = \\int f(t) e^{-j\\omega t} dt$"); FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(150), dp(112), Gravity.TOP | Gravity.RIGHT); lp.setMargins(0, dp(6), dp(8), 0); agentLayer.addView(suggestionSticker, lp); TextView current = suggestionSticker; handler.postDelayed(() -> { if (current.getParent() == agentLayer) agentLayer.removeView(current); }, 5000); }
    private TextView sticker(String preview, String value) { TextView card = label("建议\n" + preview + "\n拖到白板插入", 11, TEXT); card.setPadding(dp(8), dp(6), dp(4), dp(4)); card.setBackgroundColor(Color.rgb(255, 248, 202)); card.setElevation(dp(5)); card.setOnClickListener(v -> acceptSuggestion(value)); card.setOnLongClickListener(v -> { v.startDragAndDrop(null, new View.DragShadowBuilder(v), value, 0); return true; }); return card; }
    private void acceptSuggestion(String text) { if (whiteboard != null) { whiteboard.addStickerMarkdown(text); showStatus("建议已插入白板"); } }
    private void setWhiteboardTool(WhiteboardSceneView.Tool tool){if(whiteboard!=null)whiteboard.setTool(tool);if(whiteboardToolLabel!=null)whiteboardToolLabel.setText(tool==WhiteboardSceneView.Tool.TEXT?"字":tool==WhiteboardSceneView.Tool.PIXEL_ERASER?"像":tool==WhiteboardSceneView.Tool.STROKE_ERASER?"整":"笔");}
    private void showPenPalette(){if(whiteboard==null)return;String[] names={"黑色","蓝色","红色","绿色","橙色","紫色"};new android.app.AlertDialog.Builder(this).setTitle("笔迹颜色").setSingleChoiceItems(names,0,(dialog,which)->{whiteboard.setPenColor(whiteboard.palette()[which]);setWhiteboardTool(WhiteboardSceneView.Tool.PEN);dialog.dismiss();}).show();}

    private LinearLayout pane(String title, String trailing) { LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setBackgroundColor(Color.WHITE); LinearLayout h = new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL); h.setPadding(dp(8), 0, dp(4), 0); TextView t=label(title,11,TEXT); h.addView(t,new LinearLayout.LayoutParams(0,dp(28),1)); h.addView(label(trailing,10,Color.GRAY),new LinearLayout.LayoutParams(-2,dp(28))); p.addView(h); View line=new View(this); line.setBackgroundColor(LINE); p.addView(line,new LinearLayout.LayoutParams(-1,dp(1))); return p; }
    private LinearLayout body() { LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(8),dp(8),dp(8),dp(8)); return b; }
    private void addNote(LinearLayout body,String text,int size,boolean strong){ TextView v=label(text,size,strong?TEXT:Color.rgb(80,88,100)); v.setPadding(dp(8),dp(7),dp(4),dp(7)); if(strong)v.setBackgroundColor(Color.rgb(247,249,252)); body.addView(v); }
    private TextView label(String text,int size,int color){ TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v; }
    private Button compact(String text,View.OnClickListener click){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setTextColor(TEXT);b.setAllCaps(false);b.setMinHeight(0);b.setMinWidth(0);b.setPadding(dp(5),0,dp(5),0);b.setOnClickListener(click);return b;}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}

    private void choosePdf(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),OPEN_PDF);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==OPEN_PDF&&result==RESULT_OK&&data!=null)try{pdf.open(data.getData());renderPdf();}catch(Exception e){showStatus("课件打开失败");}}
    private void renderPdf(){if(!pdf.isOpen() || pdfCanvas == null)return;try{pdfPage.setText("p"+(pdf.currentPage()+1)+"/"+pdf.pageCount()); pdfCanvas.setPages(pdf.pageCount()); }catch(Exception e){showStatus("课件渲染失败");}}
    private void page(int delta){try{if(delta<0)pdf.previous();else pdf.next();renderPdf();}catch(Exception ignored){showStatus("请先导入课件");}}
    private void showToc(){ui.tocOpen=!ui.tocOpen;showStatus(ui.tocOpen?"目录打开：点击目录项只滚动 Notes":"目录关闭");}
    private void toggleNotesPreview(){if(notesPreviewMode){notesPane.removeView(notesPreviewScroll);notesPane.addView(notesScroll,new LinearLayout.LayoutParams(-1,0,1));notesPreviewMode=false;showStatus("源码编辑");}else{notesPane.removeView(notesScroll);notesPreview=label("",16,TEXT);notesPreview.setText(MarkdownRenderEngine.render(notesEditor.getText().toString()));notesPreview.setGravity(Gravity.TOP|Gravity.START);notesPreview.setPadding(dp(14),dp(10),dp(14),dp(10));notesPreviewScroll=new ScrollView(this);notesPreviewScroll.setFillViewport(true);notesPreviewScroll.addView(notesPreview,new ScrollView.LayoutParams(-1,-2));notesPreview.setOnDragListener(this::dropIntoNotes);notesPreviewScroll.setOnDragListener(this::dropIntoNotes);notesPane.addView(notesPreviewScroll,new LinearLayout.LayoutParams(-1,0,1));notesPreviewMode=true;showStatus("资料预览");}}
    private boolean dropIntoNotes(View view, android.view.DragEvent event){if(event.getAction()!=android.view.DragEvent.ACTION_DROP)return true;try{String text=String.valueOf(event.getLocalState());if(notesPreviewMode)toggleNotesPreview();int at=Math.max(0,notesEditor.getSelectionStart());notesEditor.getText().insert(at,"\n"+text+"\n");return true;}catch(RuntimeException e){showStatus("资料拖入失败");return true;}}
    private void agentUpdateNotes(String markdown, boolean bringToFront){if(markdown==null||markdown.isEmpty()||notesEditor.hasFocus())return;notesEditor.setText(markdown);if(notesPreviewMode&&notesPreview!=null)notesPreview.setText(MarkdownRenderEngine.render(markdown));if(bringToFront&&!notesPreviewMode)showStatus("Agent 已前台更新资料");}
    private void agentRevealNotesLine(int line){if(line<0||notesEditor==null)return;String value=notesEditor.getText().toString();int at=0;for(int i=0;i<line&&at<value.length();i++){int next=value.indexOf('\n',at);at=next<0?value.length():next+1;}notesEditor.setSelection(Math.min(at,value.length()));}
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

    private final class PdfCanvasView extends View { private final List<android.graphics.Bitmap> pages = new ArrayList<>(); private final ScaleGestureDetector scaleGesture; private float scale = 1f, offsetX, offsetY, lastX, lastY, downX, downY; private boolean multiTouch; PdfCanvasView() { super(LectureWorkspaceActivity.this); setBackgroundColor(Color.rgb(248,249,251)); scaleGesture = new ScaleGestureDetector(LectureWorkspaceActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() { public boolean onScale(ScaleGestureDetector d) { float old = scale; float next = Math.max(.5f, Math.min(3f, old * d.getScaleFactor())); float contentX = (d.getFocusX() - offsetX) / old; float contentY = (d.getFocusY() - offsetY) / old; scale = next; offsetX = d.getFocusX() - contentX * scale; offsetY = d.getFocusY() - contentY * scale; invalidate(); return true; } }); } void setPages(int count) { pages.clear(); try { for (int i=0;i<count;i++) pages.add(pdf.renderPageBitmap(i,900,1200)); } catch (Exception e) { showStatus("课件渲染失败"); } scale=1f; offsetX=0; offsetY=dp(12); invalidate(); } @Override protected void onDraw(android.graphics.Canvas canvas) { super.onDraw(canvas); canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale); float y=0; for (android.graphics.Bitmap page:pages) { canvas.drawBitmap(page, 0, y, null); y += page.getHeight() + dp(16); } canvas.restore(); } @Override public boolean onTouchEvent(MotionEvent e) { scaleGesture.onTouchEvent(e); int action=e.getActionMasked(); if (action==MotionEvent.ACTION_DOWN) { multiTouch=false; lastX=downX=e.getX(); lastY=downY=e.getY(); return true; } if (action==MotionEvent.ACTION_POINTER_DOWN) { multiTouch=true; lastX=e.getX(); lastY=e.getY(); return true; } if (action==MotionEvent.ACTION_MOVE) { if (!scaleGesture.isInProgress()) { float x=e.getX(), y=e.getY(); if (multiTouch || e.getPointerCount()>1) { offsetX += x-lastX; offsetY += y-lastY; } else { offsetX += x-lastX; offsetY += y-lastY; } lastX=x; lastY=y; invalidate(); } return true; } if (action==MotionEvent.ACTION_POINTER_UP) { lastX=e.getX(); lastY=e.getY(); return true; } return action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL; } }

    private final class WhiteboardView extends View { private final List<Path> strokes = new ArrayList<>(), erasers = new ArrayList<>(); private final ScaleGestureDetector scaleGesture; private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), erase = new Paint(Paint.ANTI_ALIAS_FLAG); private final int[] colors = {Color.rgb(35,45,60), Color.rgb(37,99,235), Color.rgb(220,38,38), Color.rgb(22,163,74)}; private float boardScale = 1f, offsetX, offsetY, lastX, lastY; private boolean panning, textMode, pixelErase; private int colorIndex; private String inserted = ""; WhiteboardView() { super(LectureWorkspaceActivity.this); setBackgroundColor(Color.WHITE); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(dp(3)); ink.setStrokeCap(Paint.Cap.ROUND); erase.setColor(Color.WHITE); erase.setStyle(Paint.Style.STROKE); erase.setStrokeWidth(dp(28)); erase.setStrokeCap(Paint.Cap.ROUND); scaleGesture = new ScaleGestureDetector(LectureWorkspaceActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() { public boolean onScale(ScaleGestureDetector d) { float old=boardScale, next=Math.max(.5f,Math.min(4f,old*d.getScaleFactor())); float x=(d.getFocusX()-offsetX)/old, y=(d.getFocusY()-offsetY)/old; boardScale=next; offsetX=d.getFocusX()-x*next; offsetY=d.getFocusY()-y*next; invalidate(); return true; } }); setOnDragListener((v,e)->{if(e.getAction()==android.view.DragEvent.ACTION_DROP){acceptSuggestion(String.valueOf(e.getLocalState()));return true;}return true;}); } void nextColor(){colorIndex=(colorIndex+1)%colors.length;invalidate();} void setEraser(boolean pixel){pixelErase=pixel;textMode=false;} public void insertText(String text){inserted=text;invalidate();} private void textBox(){final EditText input=new EditText(LectureWorkspaceActivity.this);input.setSingleLine(false);new android.app.AlertDialog.Builder(LectureWorkspaceActivity.this).setTitle("文本框").setView(input).setPositiveButton("插入",(d,w)->{inserted=input.getText().toString();textMode=false;invalidate();}).setNegativeButton("取消",null).show();} @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);canvas.save();canvas.translate(offsetX,offsetY);canvas.scale(boardScale,boardScale);if(!inserted.isEmpty()){Paint sticker=new Paint(Paint.ANTI_ALIAS_FLAG);sticker.setColor(Color.rgb(255,248,202));canvas.drawRect(dp(12),dp(12),dp(390),dp(78),sticker);Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(TEXT);text.setTextSize(dp(14));canvas.drawText("贴纸  "+inserted,dp(22),dp(50),text);}for(Path stroke:strokes){ink.setColor(colors[colorIndex]);canvas.drawPath(stroke,ink);}for(Path stroke:erasers)canvas.drawPath(stroke,erase);canvas.restore();} @Override public boolean onTouchEvent(MotionEvent e){scaleGesture.onTouchEvent(e);int action=e.getActionMasked();if(e.getPointerCount()>1||scaleGesture.isInProgress()){panning=true;if(action==MotionEvent.ACTION_MOVE){float x=e.getX(),y=e.getY();offsetX+=x-lastX;offsetY+=y-lastY;lastX=x;lastY=y;invalidate();}if(action==MotionEvent.ACTION_POINTER_DOWN){lastX=e.getX();lastY=e.getY();}return true;}if(action==MotionEvent.ACTION_DOWN){if(textMode){textBox();return true;}panning=false;float x=(e.getX()-offsetX)/boardScale,y=(e.getY()-offsetY)/boardScale;if(pixelErase){Path eraser=new Path();eraser.moveTo(x,y);erasers.add(eraser);}else if(!erasers.isEmpty()&&e.getToolType(0)==MotionEvent.TOOL_TYPE_UNKNOWN){for(int i=strokes.size()-1;i>=0;i--){android.graphics.RectF bounds=new android.graphics.RectF();strokes.get(i).computeBounds(bounds,true);if(bounds.contains(x,y)){strokes.remove(i);break;}}}else{Path stroke=new Path();stroke.moveTo(x,y);strokes.add(stroke);}invalidate();return true;}if(action==MotionEvent.ACTION_MOVE&&!panning){float x=(e.getX()-offsetX)/boardScale,y=(e.getY()-offsetY)/boardScale;if(pixelErase&&!erasers.isEmpty())erasers.get(erasers.size()-1).lineTo(x,y);else if(!pixelErase&&!strokes.isEmpty())strokes.get(strokes.size()-1).lineTo(x,y);invalidate();return true;}return action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL;} }

    private final class SplitHandle extends View { private final boolean horizontal; private final java.util.function.Consumer<Float> callback; private long down; private boolean armed; private float start; private final Runnable arm=()->{armed=true;performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);setBackgroundColor(BLUE);}; SplitHandle(boolean horizontal,java.util.function.Consumer<Float> callback){super(LectureWorkspaceActivity.this);this.horizontal=horizontal;this.callback=callback;setBackgroundColor(LINE);setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){down=System.currentTimeMillis();start=horizontal?e.getY():e.getX();armed=false;handler.postDelayed(arm,1500);return true;}if(e.getAction()==MotionEvent.ACTION_MOVE&&armed){float now=horizontal?e.getY():e.getX();ViewGroup parent=(ViewGroup)getParent();float size=horizontal?Math.max(1,parent.getHeight()):Math.max(1,parent.getWidth());callback.accept((now-start)/size);start=now;return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){handler.removeCallbacks(arm);armed=false;setBackgroundColor(LINE);return true;}return true;});} }
}
