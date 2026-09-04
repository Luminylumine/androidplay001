package com.androidplay.mdclient.whiteboard;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.Layout;
import android.text.StaticLayout;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import com.androidplay.mdclient.markdown.MarkdownRenderEngine;

public final class WhiteboardSceneView extends View {
    public enum Tool { PEN, PIXEL_ERASER, STROKE_ERASER, TEXT }
    private final WhiteboardSceneController scene = new WhiteboardSceneController();
    private final ScaleGestureDetector scaleGesture;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Tool tool = Tool.PEN;
    private int penColor = Color.rgb(35, 45, 60);
    private float boardScale = 1f, offsetX, offsetY, lastFocusX, lastFocusY;
    private InkStrokeItem activeStroke;
    private boolean multiTouch;
    private final int[] palette = {Color.BLACK, Color.rgb(37,99,235), Color.rgb(220,38,38), Color.rgb(22,163,74), Color.rgb(234,88,12), Color.rgb(124,58,237)};

    public WhiteboardSceneView(Context context) {
        super(context); setBackgroundColor(Color.WHITE); ink.setStyle(Paint.Style.STROKE); ink.setStrokeCap(Paint.Cap.ROUND); clear.setColor(Color.TRANSPARENT); clear.setStyle(Paint.Style.STROKE); clear.setStrokeCap(Paint.Cap.ROUND); clear.setStrokeWidth(28);
        scaleGesture = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() { @Override public boolean onScale(ScaleGestureDetector d) { float old=boardScale, next=Math.max(.5f,Math.min(4f,old*d.getScaleFactor())); float x=(d.getFocusX()-offsetX)/old,y=(d.getFocusY()-offsetY)/old; boardScale=next; offsetX=d.getFocusX()-x*next; offsetY=d.getFocusY()-y*next; invalidate(); return true; } });
        setOnDragListener((v,e)->{if(e.getAction()==android.view.DragEvent.ACTION_DROP){PointF p=world(e.getX(),e.getY());scene.addSticker(String.valueOf(e.getLocalState()),p.x,p.y);invalidate();return true;}return true;});
    }
    public WhiteboardSceneController scene(){return scene;}
    public void setTool(Tool next){tool=next;}
    public Tool tool(){return tool;}
    public int penColor(){return penColor;}
    public void setPenColor(int color){penColor=color;tool=Tool.PEN;}
    public int[] palette(){return palette.clone();}
    public void addStickerMarkdown(String markdown){scene.addSticker(markdown,world(getWidth()/2f,getHeight()/2f).x,world(getWidth()/2f,getHeight()/2f).y);invalidate();}
    public void addTextBox(String text){PointF p=world(32+scene.items().size()*16,32+scene.items().size()*16);scene.addTextBox(text,p.x,p.y);invalidate();}
    private PointF world(float x,float y){return new PointF((x-offsetX)/boardScale,(y-offsetY)/boardScale);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.translate(offsetX,offsetY);canvas.scale(boardScale,boardScale);canvas.saveLayer(null,null);for(WhiteboardItem item:scene.items()){if(item instanceof InkStrokeItem){InkStrokeItem s=(InkStrokeItem)item;Path path=new Path();for(int i=0;i<s.points.size();i++){PointF p=s.points.get(i);if(i==0)path.moveTo(p.x,p.y);else path.lineTo(p.x,p.y);}if(s.toolKind==InkStrokeItem.ToolKind.PIXEL_ERASER){clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));clear.setStrokeWidth(28);canvas.drawPath(path,clear);clear.setXfermode(null);}else{ink.setColor(s.color);ink.setStrokeWidth(s.strokeWidth);canvas.drawPath(path,ink);}}}canvas.restore();for(WhiteboardItem item:scene.items()){if(item instanceof StickerItem)drawRichCard(canvas,(StickerItem)item,Color.rgb(255,248,202));else if(item instanceof TextBoxItem)drawRichCard(canvas,(TextBoxItem)item,Color.WHITE);}canvas.restore();}
    private void drawRichCard(Canvas canvas,WhiteboardItem item,int background){Paint box=new Paint(Paint.ANTI_ALIAS_FLAG);box.setColor(background);canvas.drawRect(item.x,item.y,item.x+item.width,item.y+item.height,box);Spanned content=item instanceof StickerItem?MarkdownRenderEngine.render(((StickerItem)item).markdown):MarkdownRenderEngine.render(((TextBoxItem)item).text);TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.rgb(31,36,45));text.setTextSize(14);StaticLayout layout=StaticLayout.Builder.obtain(content,0,content.length(),text,(int)item.width-16).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();canvas.save();canvas.translate(item.x+8,item.y+8);layout.draw(canvas);canvas.restore();}
    private void openTextBox(){EditText input=new EditText(getContext());input.setSingleLine(false);new AlertDialog.Builder(getContext()).setTitle("文本框").setView(input).setPositiveButton("插入",(d,w)->addTextBox(input.getText().toString())).setNegativeButton("取消",null).show();}
    @Override public boolean onTouchEvent(MotionEvent e){scaleGesture.onTouchEvent(e);int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){multiTouch=false;lastFocusX=e.getX();lastFocusY=e.getY();if(tool==Tool.TEXT){openTextBox();return true;}PointF p=world(e.getX(),e.getY());if(tool==Tool.STROKE_ERASER){scene.eraseStrokeAt(p.x,p.y,24);invalidate();return true;}activeStroke=scene.addStroke(penColor,tool==Tool.PIXEL_ERASER?28:3,tool==Tool.PIXEL_ERASER?InkStrokeItem.ToolKind.PIXEL_ERASER:InkStrokeItem.ToolKind.PEN,p);invalidate();return true;}if(action==MotionEvent.ACTION_POINTER_DOWN){multiTouch=true;lastFocusX=e.getX();lastFocusY=e.getY();activeStroke=null;return true;}if(action==MotionEvent.ACTION_MOVE){if(e.getPointerCount()>1||scaleGesture.isInProgress()){float fx=e.getX(),fy=e.getY();offsetX+=fx-lastFocusX;offsetY+=fy-lastFocusY;lastFocusX=fx;lastFocusY=fy;invalidate();return true;}if(activeStroke!=null){PointF p=world(e.getX(),e.getY());if(tool==Tool.STROKE_ERASER)scene.eraseStrokeAt(p.x,p.y,24);else activeStroke.points.add(p);invalidate();return true;}}if(action==MotionEvent.ACTION_POINTER_UP){multiTouch=true;return true;}if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){activeStroke=null;return true;}return true;}
}
