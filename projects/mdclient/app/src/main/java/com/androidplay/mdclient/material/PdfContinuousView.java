package com.androidplay.mdclient.material;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.widget.OverScroller;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Continuous PDF pages with focal-point zoom, constrained pan, and release fling. */
public final class PdfContinuousView extends View {
    private final PdfMaterialController controller;
    private final List<Bitmap> pages = new ArrayList<>();
    private final ScaleGestureDetector scaleGesture;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private float scale = 1f, offsetX, offsetY, lastX, lastY, lastFocusX, lastFocusY;
    private boolean multiTouch;

    public PdfContinuousView(Context context, PdfMaterialController controller) {
        super(context); this.controller = controller; scroller = new OverScroller(context);
        scaleGesture = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { float old=scale, next=Math.max(.5f,Math.min(3f,old*d.getScaleFactor())); float cx=(d.getFocusX()-offsetX)/old, cy=(d.getFocusY()-offsetY)/old; scale=next; offsetX=d.getFocusX()-cx*scale; offsetY=d.getFocusY()-cy*scale; clampOffsets(); invalidate(); return true; }
        }); setBackgroundColor(0xfff8f9fb);
    }
    public void setPages(int count) { pages.clear(); try { for(int i=0;i<count;i++) pages.add(controller.renderPageBitmap(i,900,1200)); } catch(Exception ignored) {} scale=1f; offsetX=0; offsetY=12; clampOffsets(); invalidate(); }
    private float contentHeight(){return pages.size()==0?0:pages.size()*1200f+(pages.size()-1)*16f;}
    private float contentWidth(){return pages.size()==0?0:900f*scale;}
    private void clampOffsets(){float width=contentWidth(); if(width<=getWidth())offsetX=(getWidth()-width)/2f;else offsetX=Math.max(getWidth()-width,Math.min(0,offsetX));float height=contentHeight()*scale;if(height<=getHeight())offsetY=(getHeight()-height)/2f;else offsetY=Math.max(getHeight()-height,Math.min(0,offsetY));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){clampOffsets();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.translate(offsetX,offsetY);canvas.scale(scale,scale);float y=0;for(Bitmap page:pages){canvas.drawBitmap(page,0,y,null);y+=page.getHeight()+16;}canvas.restore();}
    @Override public void computeScroll(){if(scroller.computeScrollOffset()){offsetX=scroller.getCurrX();offsetY=scroller.getCurrY();clampOffsets();postInvalidateOnAnimation();}}
    @Override public boolean onTouchEvent(MotionEvent e){if(velocity==null)velocity=VelocityTracker.obtain();velocity.addMovement(e);scaleGesture.onTouchEvent(e);int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){scroller.abortAnimation();multiTouch=false;lastX=lastFocusX=e.getX();lastY=lastFocusY=e.getY();return true;}if(action==MotionEvent.ACTION_POINTER_DOWN){multiTouch=true;lastFocusX=e.getX();lastFocusY=e.getY();velocity.clear();return true;}if(action==MotionEvent.ACTION_MOVE){float x=e.getX(),y=e.getY();if(e.getPointerCount()>1||scaleGesture.isInProgress()){offsetX+=x-lastFocusX;offsetY+=y-lastFocusY;lastFocusX=x;lastFocusY=y;}else{offsetX+=x-lastX;offsetY+=y-lastY;}lastX=x;lastY=y;clampOffsets();invalidate();return true;}if(action==MotionEvent.ACTION_POINTER_UP){multiTouch=true;lastX=e.getX();lastY=e.getY();return true;}if(action==MotionEvent.ACTION_UP){if(!multiTouch&&!scaleGesture.isInProgress()){velocity.computeCurrentVelocity(1000);float vx=velocity.getXVelocity(),vy=velocity.getYVelocity();if(contentWidth()>getWidth()||contentHeight()*scale>getHeight())scroller.fling(Math.round(offsetX),Math.round(offsetY),Math.round(vx),Math.round(vy),contentWidth()>getWidth()?Math.round(getWidth()-contentWidth()):Math.round(offsetX),contentWidth()>getWidth()?0:Math.round(offsetX),contentHeight()*scale>getHeight()?Math.round(getHeight()-contentHeight()*scale):Math.round(offsetY),contentHeight()*scale>getHeight()?0:Math.round(offsetY));invalidate();}velocity.recycle();velocity=null;return true;}if(action==MotionEvent.ACTION_CANCEL){velocity.recycle();velocity=null;return true;}return true;}
}
