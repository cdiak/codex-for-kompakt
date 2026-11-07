package org.kompakt.remote;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.util.Arrays;
import java.util.Locale;

/** Recorder shared by the app and IME. Cancellation invalidates every late callback. */
public final class SpeechPanel extends LinearLayout {
    public interface Host { void useText(String text); void requestPermission(); }
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView status, preview;
    private final Button record, insert, clear;
    private volatile boolean recording;
    private volatile AudioRecord microphone;
    private volatile int generation;
    private WhisperEngine.Job job;
    private String result = "";
    private long started;
    private boolean busy, allowed = true;
    private final Runnable ticker = new Runnable() {
        public void run() {
            if (recording) {
                status.setText("Recording · "+((SystemClock.elapsedRealtime()-started)/1000)+" / 30 sec");
                main.postDelayed(this,1000);
            }
        }
    };
    public SpeechPanel(Context context, Host host, boolean compact) {
        super(context); this.host=host; setOrientation(VERTICAL); setBackgroundColor(Color.WHITE);
        setPadding(dp(16),dp(12),dp(16),dp(10));
        TextView title=label("Whisper · English · On device",compact?16:20); title.setTypeface(null,Typeface.BOLD); addView(title);
        status=label("Record a short message. Audio stays on this phone.",14); status.setPadding(0,dp(8),0,dp(8)); addView(status);
        ScrollView scroll=new ScrollView(context); scroll.setSmoothScrollingEnabled(false);
        preview=label("",17); preview.setTextIsSelectable(true); preview.setPadding(dp(8),dp(6),dp(8),dp(6)); scroll.addView(preview);
        addView(scroll,new LayoutParams(-1,compact?dp(86):0,compact?0:1));
        record=button("Record",v->{if(recording)stopRecording();else startRecording();}); addView(record,new LayoutParams(-1,dp(50)));
        LinearLayout actions=new LinearLayout(context); actions.setOrientation(HORIZONTAL);
        clear=button("Cancel",v->cancel()); insert=button("Use text",v->{if(!result.isEmpty()&&!busy)host.useText(result);});
        LayoutParams a=new LayoutParams(0,dp(48),1); a.setMargins(0,dp(6),dp(4),0);actions.addView(clear,a);
        LayoutParams b=new LayoutParams(0,dp(48),1); b.setMargins(dp(4),dp(6),0,0);actions.addView(insert,b); addView(actions); refresh();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView t=new TextView(getContext());t.setText(text);t.setTextSize(size);t.setTextColor(Color.BLACK);return t;}
    private Button button(String text,OnClickListener action){
        Button b=new Button(getContext());b.setText(text);b.setAllCaps(false);b.setTextSize(16);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.GRAY,Color.BLACK}));
        GradientDrawable shape=new GradientDrawable();shape.setColor(Color.WHITE);shape.setCornerRadius(dp(14));shape.setStroke(dp(1),Color.GRAY);b.setBackground(shape);b.setOnClickListener(action);return b;
    }
    public void setAllowed(boolean value){allowed=value;if(!value){cancel();status.setText("Voice typing is unavailable in this field.");}refresh();}
    private void refresh(){setKeepScreenOn(busy);record.setText(recording?"Stop recording":"Record");record.setEnabled(allowed&&(!busy||recording));insert.setEnabled(allowed&&!busy&&!result.isEmpty());clear.setEnabled(busy||!result.isEmpty());}
    public void startRecording(){
        if(busy||!allowed)return;
        if(getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){host.requestPermission();return;}
        cancel();int session=++generation;busy=true;recording=true;result="";preview.setText("");started=SystemClock.elapsedRealtime();
        status.setText("Starting microphone…");refresh();main.post(ticker);
        new Thread(()->capture(session),"Whisper microphone").start();
    }
    private void capture(int session){
        short[] buffer=new short[16000*30];int size=0;String failure=null;AudioRecord recorder=null;
        try{
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("This microphone does not support 16 kHz audio.");
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("The microphone is unavailable.");
            microphone=recorder;recorder.startRecording();
            while(recording&&session==generation&&size<buffer.length){
                int n=recorder.read(buffer,size,Math.min(2048,buffer.length-size));
                if(n<0){if(recording)throw new IllegalStateException("Microphone recording was interrupted.");break;}
                size+=n;
            }
        }catch(Exception e){failure=e.getMessage();}
        finally{
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}
            if(microphone==recorder)microphone=null;
        }
        final short[] audio=Arrays.copyOf(buffer,size);Arrays.fill(buffer,(short)0);final String error=failure;
        main.post(()->{
            if(session!=generation){Arrays.fill(audio,(short)0);return;}
            recording=false;main.removeCallbacks(ticker);refresh();
            if(error!=null){Arrays.fill(audio,(short)0);finish("",0,error);return;}
            status.setText("Transcribing on this phone…");
            job=WhisperEngine.transcribe(getContext(),audio,(text,ms,problem)->{if(session==generation)finish(text,ms,problem);});
        });
    }
    private void finish(String text,long ms,String error){
        busy=false;job=null;result=error==null?text:"";preview.setText(result);
        status.setText(error!=null?error:result.isEmpty()?"No speech detected. Try again.":String.format(Locale.US,"Ready in %.1f sec · Review before using",ms/1000.0));refresh();
    }
    private void stopRecording(){recording=false;AudioRecord r=microphone;if(r!=null)try{r.stop();}catch(Exception ignored){}record.setEnabled(false);}
    public void cancel(){
        ++generation;recording=false;main.removeCallbacks(ticker);
        AudioRecord r=microphone;if(r!=null)try{r.stop();}catch(Exception ignored){}
        if(job!=null)job.cancel();job=null;busy=false;result="";preview.setText("");status.setText("Record a short message. Audio stays on this phone.");refresh();
    }
    @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
}
