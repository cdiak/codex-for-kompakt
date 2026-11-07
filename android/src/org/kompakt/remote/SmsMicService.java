package org.kompakt.remote;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.Toast;

/** Opt-in SMS overlay. Only the original compose field is read or changed. */
public final class SmsMicService extends AccessibilityService {
    private static final String SMS="com.mudita.messages";
    private static SmsMicService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private View microphone;
    private AccessibilityNodeInfo target;
    private String draft;
    private int selectionStart,selectionEnd,session;
    private boolean visible;
    private int lastX=-1,lastY=-1;
    private final Runnable update=this::position;
    @Override protected void onServiceConnected(){instance=this;windows=(WindowManager)getSystemService(WINDOW_SERVICE);position();}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        String pkg=String.valueOf(event.getPackageName());
        int type=event.getEventType();
        if(SMS.equals(pkg)||type==AccessibilityEvent.TYPE_WINDOWS_CHANGED||type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            main.removeCallbacks(update);main.postDelayed(update,100);
        }
    }
    private AccessibilityNodeInfo field(){
        // An accessibility overlay can itself become the active window. Follow
        // the focused APPLICATION window, ignoring our own overlay and the IME.
        AccessibilityNodeInfo root=null;
        for(AccessibilityWindowInfo window:getWindows()){
            if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&window.isFocused())root=window.getRoot();
            window.recycle();
            if(root!=null)break;
        }
        if(root==null)return null;
        try{
            if(!SMS.contentEquals(root.getPackageName()==null?"":root.getPackageName()))return null;
            AccessibilityNodeInfo focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(focus!=null&&focus.isEditable()&&"com.mudita.messages:id/compose_message_text".equals(focus.getViewIdResourceName()))return focus;
            if(focus!=null)focus.recycle();return null;
        }finally{root.recycle();}
    }
    private void position(){
        if(windows==null||target!=null){hide();return;}
        AccessibilityNodeInfo input=field();
        if(input==null){hide();return;}
        Rect bounds=new Rect();input.getBoundsInScreen(bounds);input.recycle();
        if(bounds.isEmpty()){hide();return;}
        int size=dp(44);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=getResources().getDisplayMetrics().widthPixels-size-dp(10);p.y=Math.max(dp(108),bounds.top-size-dp(8));
        if(microphone==null){
            microphone=new View(this){
                final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                final Bitmap blossom=BitmapFactory.decodeResource(getResources(),R.drawable.icon);
                final Rect artwork=new Rect(blossom.getWidth()*17/100,blossom.getHeight()*17/100,blossom.getWidth()*84/100,blossom.getHeight()*84/100);
                @Override protected void onDraw(Canvas c){
                    float x=getWidth()/2f,y=getHeight()/2f;pen.setColor(Color.WHITE);pen.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(21),pen);
                    pen.setColor(Color.BLACK);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(1));c.drawCircle(x,y,dp(21),pen);
                    pen.setStyle(Paint.Style.FILL);
                    c.drawBitmap(blossom,artwork,new RectF(x-dp(15),y-dp(15),x+dp(15),y+dp(15)),pen);
                }
            };
            microphone.setContentDescription("Dictate SMS draft");microphone.setOnClickListener(v->record());
        }
        try{
            if(visible){if(p.x!=lastX||p.y!=lastY)windows.updateViewLayout(microphone,p);}
            else{windows.addView(microphone,p);visible=true;}
            lastX=p.x;lastY=p.y;
        }
        catch(RuntimeException ignored){hide();}
    }
    private void record(){
        AccessibilityNodeInfo input=field();if(input==null){hide();return;}
        clearTarget();target=input;draft=input.getText()==null?"":input.getText().toString();
        selectionStart=input.getTextSelectionStart();selectionEnd=input.getTextSelectionEnd();int id=++session;hide();
        try{startActivity(new Intent(this,DictationActivity.class).putExtra("smsSession",id).putExtra("record",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));}
        catch(RuntimeException error){clearTarget();Toast.makeText(this,"Could not open the recorder.",Toast.LENGTH_LONG).show();}
    }
    public static void confirm(int id,String text){
        SmsMicService s=instance;if(s!=null&&s.session==id&&s.target!=null)s.main.postDelayed(()->s.insert(id,text,0),350);
    }
    private void insert(int id,String text,int attempt){
        if(id!=session||target==null)return;
        AccessibilityNodeInfo current=field();
        if(current==null){
            if(attempt<8){main.postDelayed(()->insert(id,text,attempt+1),200);return;}
            clearTarget();Toast.makeText(this,"Return to the original SMS draft and try again.",Toast.LENGTH_LONG).show();return;
        }
        try{
            String now=current.getText()==null?"":current.getText().toString();
            if(!current.equals(target)||current.getWindowId()!=target.getWindowId()||!now.equals(draft)){
                Toast.makeText(this,"The draft changed; no text was inserted.",Toast.LENGTH_LONG).show();return;
            }
            int start=selectionStart<0?now.length():Math.min(selectionStart,now.length());
            int end=selectionEnd<start?start:Math.min(selectionEnd,now.length());
            String addition=text;
            if(start>0&&!Character.isWhitespace(now.charAt(start-1)))addition=" "+addition;
            if(end<now.length()&&!Character.isWhitespace(now.charAt(end)))addition+=" ";
            Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,now.substring(0,start)+addition+now.substring(end));
            if(current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value)){
                Bundle cursor=new Bundle();cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start+addition.length());cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,start+addition.length());
                current.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,cursor);
            }else Toast.makeText(this,"The SMS app could not accept the text.",Toast.LENGTH_LONG).show();
        }finally{current.recycle();clearTarget();main.postDelayed(update,150);}
    }
    public static void cancel(int id){SmsMicService s=instance;if(s!=null&&s.session==id){s.clearTarget();s.main.postDelayed(s.update,150);}}
    private void clearTarget(){if(target!=null)target.recycle();target=null;draft=null;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void hide(){if(visible&&windows!=null){try{windows.removeView(microphone);}catch(RuntimeException ignored){}visible=false;}}
    @Override public void onInterrupt(){clearTarget();hide();}
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);clearTarget();hide();if(instance==this)instance=null;super.onDestroy();}
}
