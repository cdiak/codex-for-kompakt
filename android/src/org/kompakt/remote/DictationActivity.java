package org.kompakt.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

public final class DictationActivity extends Activity {
    private SpeechPanel panel;
    private int smsSession;
    private boolean confirmed, requestingPermission;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);smsSession=getIntent().getIntExtra("smsSession",0);
        getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        Button back=new Button(this);back.setText("Back");back.setAllCaps(false);back.setOnClickListener(v->finish());root.addView(back);
        panel=new SpeechPanel(this,new SpeechPanel.Host(){
            public void useText(String text){confirmed=true;if(smsSession!=0)SmsMicService.confirm(smsSession,text);else setResult(RESULT_OK,new Intent().putExtra("text",text));finish();}
            public void requestPermission(){requestingPermission=true;DictationActivity.this.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);}
        },false);
        root.addView(panel,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if(saved==null&&getIntent().getBooleanExtra("record",false))panel.post(()->panel.startRecording());
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        requestingPermission=false;
        if(code==1&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)panel.startRecording();
        else Toast.makeText(this,"Microphone access is needed for dictation.",Toast.LENGTH_LONG).show();
    }
    @Override public void onPause(){if(panel!=null)panel.cancel();if(smsSession!=0&&!confirmed&&!requestingPermission)SmsMicService.cancel(smsSession);super.onPause();}
}
