package org.kompakt.remote.tests;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.kompakt.remote.WhisperEngine;
public class VoiceSmokeTest extends Instrumentation {
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle result=new Bundle();try{
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open("coding-task.wav")){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
  byte[] wav=out.toByteArray();ByteBuffer bytes=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);int offset=12,length=0;
  while(offset+8<wav.length){int n=bytes.getInt(offset+4);if(new String(wav,offset,4,"US-ASCII").equals("data")){length=n;offset+=8;break;}offset+=8+n+(n&1);}
  short[] audio=new short[length/2];for(int i=0;i<audio.length;i++)audio[i]=bytes.getShort(offset+i*2);short[] original=audio.clone();
  CountDownLatch completed=new CountDownLatch(1);AtomicReference<String> transcript=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();AtomicLong time=new AtomicLong();
  WhisperEngine.transcribe(getTargetContext(),audio,(text,ms,problem)->{transcript.set(text);error.set(problem);time.set(ms);completed.countDown();});
  if(!completed.await(45,TimeUnit.SECONDS))throw new AssertionError("Transcription timed out");
  if(error.get()!=null)throw new AssertionError(error.get());
  if(!transcript.get().toLowerCase().contains("python script"))throw new AssertionError("Unexpected sample transcript: "+transcript.get());
  result.putString("transcript",transcript.get());result.putLong("transcription_ms",time.get());
  CountDownLatch silence=new CountDownLatch(1);AtomicReference<String> silentText=new AtomicReference<>();
  WhisperEngine.transcribe(getTargetContext(),new short[32000],(text,ms,problem)->{silentText.set(text);silence.countDown();});
  if(!silence.await(5,TimeUnit.SECONDS)||!"".equals(silentText.get()))throw new AssertionError("Silence generated text");
  AtomicBoolean callback=new AtomicBoolean();WhisperEngine.Job cancelled=WhisperEngine.transcribe(getTargetContext(),original.clone(),(text,ms,problem)->callback.set(true));Thread.sleep(300);cancelled.cancel();Thread.sleep(500);
  if(callback.get())throw new AssertionError("Cancelled job delivered text");
  CountDownLatch recovered=new CountDownLatch(1);AtomicReference<String> recovery=new AtomicReference<>();
  WhisperEngine.transcribe(getTargetContext(),original,(text,ms,problem)->{recovery.set(problem==null?text:problem);recovered.countDown();});
  if(!recovered.await(45,TimeUnit.SECONDS)||!recovery.get().toLowerCase().contains("python script"))throw new AssertionError("Engine did not recover after cancellation");
  if(callback.get())throw new AssertionError("Late cancelled callback");
  result.putString("checks","native transcription, silence, cancelled decode, recovery after cancellation");finish(-1,result);
 }catch(Throwable e){result.putString("error",e.toString());finish(0,result);}}
}
