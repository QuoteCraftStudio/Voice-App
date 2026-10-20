package ca.quotecraftstudio.voicereader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native speech remains independent from WebView and Activity lifecycle. It is a foreground
 * media-playback service using Android's installed TextToSpeech engine and a MediaSession.
 */
public final class VoiceReaderService extends Service {
    public static final String ACTION_START="ca.quotecraftstudio.voicereader.START";
    public static final String ACTION_PAUSE="ca.quotecraftstudio.voicereader.PAUSE";
    public static final String ACTION_RESUME="ca.quotecraftstudio.voicereader.RESUME";
    public static final String ACTION_STOP="ca.quotecraftstudio.voicereader.STOP";
    public static final String ACTION_RATE="ca.quotecraftstudio.voicereader.RATE";
    private static final String CHANNEL="reader_playback";
    private static final int NOTIFICATION_ID=3117;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady=false, loaded=false, active=false, paused=false, pausedForFocus=false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSession mediaSession;
    private String allText="", title="Voice Reader", sessionId="", voiceName="",language="en-CA";
    private int baseOffset=0,totalChars=0,cursor=0,currentOffset=0,chunkStart=0,chunkEnd=0,chunkResumeAt=0,spokenBase=0;
    private String chunk="",utteranceId="";
    private float rate=1.0f;
    private boolean wordsOnly=true;
    private int playbackGeneration=0,utteranceSequence=0;
    private long lastProgressAt=0;
    private final List<int[]> skips=new ArrayList<>();
    private static final Pattern VERSE=Pattern.compile("(?m)^\\s{0,3}\\d{1,3}(?:[.)])?[ \\t]+|\\b\\d{1,3}:\\d{1,3}\\b");

    @Override public void onCreate(){
        super.onCreate();
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Voice Reader playback",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Playback and lock-screen controls for Voice Reader");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        mediaSession=new MediaSession(this,"QuoteCraft Voice Reader");
        mediaSession.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){resumePlayback();}
            @Override public void onPause(){pausePlayback(false);}
            @Override public void onStop(){stopPlayback(true);}
        });
        mediaSession.setActive(true);
        tts=new TextToSpeech(getApplicationContext(),result->main.post(()->{
            ttsReady=result==TextToSpeech.SUCCESS;
            if(ttsReady){configureVoice();maybeSpeak();}
            else if(active)fail("The Android text-to-speech engine could not start. Check Text-to-speech settings.");
        }));
    }
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(ACTION_START.equals(action)){
            // Immediately promote this user-started service to foreground, then load the document.
            startForeground(NOTIFICATION_ID,notification("Preparing reading…"));
            loadDocument(intent);
        }else if(ACTION_PAUSE.equals(action)) pausePlayback(false);
        else if(ACTION_RESUME.equals(action))resumePlayback();
        else if(ACTION_STOP.equals(action))stopPlayback(true);
        else if(ACTION_RATE.equals(action)){
            rate=Math.max(.5f,Math.min(2f,intent.getFloatExtra("rate",1f)));
            if(ttsReady)tts.setSpeechRate(rate);
        }
        return START_NOT_STICKY;
    }

    private void loadDocument(Intent intent){
        int generation=++playbackGeneration;
        if(ttsReady)tts.stop();
        active=true;paused=false;pausedForFocus=false;loaded=false;allText="";chunk="";utteranceId="";cursor=0;currentOffset=0;chunkResumeAt=0;
        try {
            JSONObject config=new JSONObject(intent.getStringExtra("config"));
            sessionId=config.optString("sessionId","");
            title=config.optString("title","Voice Reader");
            if(title.length()>110)title=title.substring(0,110);
            baseOffset=Math.max(0,config.optInt("offset",0));
            totalChars=Math.max(baseOffset,config.optInt("totalChars",baseOffset));
            currentOffset=baseOffset;
            rate=(float)Math.max(.5,Math.min(2,config.optDouble("rate",1.0)));
            voiceName=config.optString("voiceName","");
            language=config.optString("language","en-CA");
            wordsOnly=config.optBoolean("wordsOnly",true);
        }catch(Exception ex){fail("Invalid reading settings.");return;}
        saveState();updateMediaState();
        emit("preparing",currentOffset,1,null);
        updateNotification("Preparing reading…");
        String textPath=intent.getStringExtra("document"),skipPath=intent.getStringExtra("skips");
        new Thread(()->{
            String value=null;List<int[]> ranges=new ArrayList<>();String error=null;
            try {
                if(textPath==null||!new File(textPath).getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator))throw new IllegalArgumentException("Document location is invalid");
                File file=new File(textPath);
                if(file.length()>120L*1024*1024)throw new IllegalArgumentException("Document too large for this reader");
                StringBuilder builder=new StringBuilder((int)Math.min(file.length(),20_000_000L));
                try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8),65536)){
                    char[] buffer=new char[32768];int n;
                    while((n=in.read(buffer))!=-1)builder.append(buffer,0,n);
                }
                value=builder.toString();
                if(skipPath!=null&&new File(skipPath).getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator)){
                    try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(skipPath),StandardCharsets.UTF_8))){
                        String line;while((line=in.readLine())!=null){String[] pair=line.split(",",2);if(pair.length==2){int a=Integer.parseInt(pair[0]),b=Integer.parseInt(pair[1]);if(b>a)ranges.add(new int[]{a,b});}}
                    }
                }
                ranges.sort(Comparator.comparingInt(a->a[0]));
            }catch(Exception ex){error=ex.getMessage()==null?"Could not load the book":ex.getMessage();}
            finally {if(textPath!=null)new File(textPath).delete();if(skipPath!=null)new File(skipPath).delete();}
            final String textResult=value, errorResult=error;
            main.post(()->{
                if(generation!=playbackGeneration||!active)return;
                if(errorResult!=null){fail(errorResult);return;}
                allText=textResult==null?"":textResult;
                skips.clear();skips.addAll(ranges);
                loaded=true;configureVoice();maybeSpeak();
            });
        },"VoiceReader-DocumentLoad").start();
    }

    private void configureVoice(){
        if(!ttsReady||tts==null)return;
        tts.setSpeechRate(rate);
        boolean selected=false;
        if(!voiceName.isEmpty()){
            try{for(Voice v:tts.getVoices()) if(v.getName().equals(voiceName)){tts.setVoice(v);selected=true;break;}}catch(Exception ignored){}
        }
        if(!selected){Locale locale=Locale.forLanguageTag(language);int result=tts.setLanguage(locale);if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.CANADA);}
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){main.post(()->{if(isCurrent(id)){emit("started",currentOffset,Math.max(1,chunk.length()),null);updateMediaState();}});}
            @Override public void onDone(String id){main.post(()->{
                if(!isCurrent(id)||paused||!active)return;
                currentOffset=Math.min(totalChars,chunkEnd);cursor=chunkEnd-baseOffset;
                chunk="";chunkResumeAt=0;
                saveState();emit("progress",currentOffset,1,null);maybeSpeak();
            });}
            @Override public void onError(String id){main.post(()->{if(isCurrent(id)&&active&&!paused)fail("The text-to-speech engine could not read this section.");});}
            @Override public void onRangeStart(String id,int start,int end,int frame){main.post(()->{
                if(!isCurrent(id)||paused||!active)return;
                chunkResumeAt=Math.max(chunkResumeAt,Math.min(chunk.length(),spokenBase+start));
                currentOffset=Math.min(totalChars,chunkStart+chunkResumeAt);
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastProgressAt>450){lastProgressAt=now;saveState();emit("progress",currentOffset,Math.max(1,end-start),null);}
            });}
        });
    }
    private boolean isCurrent(String id){return id!=null&&id.equals(utteranceId)&&active;}

    private void requestAudioFocus(){
        if(audioManager==null)return;
        if(focusRequest==null){
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change->{
                    if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
                        if(active&&!paused){pausedForFocus=true;pausePlayback(true);}
                    }else if(change==AudioManager.AUDIOFOCUS_GAIN&&pausedForFocus){pausedForFocus=false;resumePlayback();}
                }).build();
        }
        audioManager.requestAudioFocus(focusRequest);
    }
    private void abandonAudioFocus(){if(audioManager!=null&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);}

    private void maybeSpeak(){
        if(!active||paused||!loaded||!ttsReady)return;
        if(allText.isEmpty()){finish();return;}
        requestAudioFocus();
        if(!chunk.isEmpty()){
            if(chunkResumeAt>=chunk.length()){currentOffset=chunkEnd;chunk="";chunkResumeAt=0;}
            else{speakCurrent();return;}
        }
        while(cursor<allText.length()){
            int end=Math.min(cursor+460,allText.length());
            if(end<allText.length()){
                String snippet=allText.substring(cursor,end);
                int sentence=Math.max(snippet.lastIndexOf(". "),Math.max(snippet.lastIndexOf("! "),snippet.lastIndexOf("? ")));
                int whitespace=snippet.lastIndexOf(' ');
                if(sentence>80)end=cursor+sentence+2;
                else if(whitespace>80)end=cursor+whitespace+1;
            }
            if(end<=cursor)end=Math.min(cursor+460,allText.length());
            chunkStart=baseOffset+cursor;
            chunkEnd=baseOffset+end;
            chunk=allText.substring(cursor,end);
            cursor=end;chunkResumeAt=0;
            if(!spokenForm(chunk,chunkStart).trim().isEmpty()){speakCurrent();return;}
            currentOffset=chunkEnd;chunk="";
        }
        finish();
    }
    private String spokenForm(String raw,int absoluteStart){
        if(!wordsOnly)return raw;
        StringBuilder prepared=new StringBuilder(raw);
        for(int[] range:skips){if(range[0]>=absoluteStart+raw.length())break;if(range[1]<=absoluteStart)continue;
            for(int i=Math.max(0,range[0]-absoluteStart);i<Math.min(raw.length(),range[1]-absoluteStart);i++)prepared.setCharAt(i,' ');
        }
        Matcher verses=VERSE.matcher(prepared);
        while(verses.find())for(int i=verses.start();i<verses.end();i++)prepared.setCharAt(i,' ');
        return prepared.toString();
    }
    private void speakCurrent(){
        if(!active||paused||!ttsReady)return;
        String remaining=spokenForm(chunk.substring(chunkResumeAt),chunkStart+chunkResumeAt);
        if(remaining.trim().isEmpty()){
            currentOffset=chunkEnd;chunk="";chunkResumeAt=0;maybeSpeak();return;
        }
        spokenBase=chunkResumeAt;
        utteranceId=sessionId+":"+(++utteranceSequence);
        Bundle options=new Bundle();
        options.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f);
        int result=tts.speak(remaining,TextToSpeech.QUEUE_FLUSH,options,utteranceId);
        if(result==TextToSpeech.ERROR)fail("Android could not start the selected voice. Try Device default.");
        else {saveState();updateMediaState();updateNotification("Reading aloud");}
    }
    private void pausePlayback(boolean focus){
        if(!active||paused)return;
        paused=true;
        if(!focus)pausedForFocus=false;
        if(tts!=null)tts.stop();
        saveState();updateMediaState();updateNotification("Paused");emit("paused",currentOffset,1,null);
    }
    private void resumePlayback(){
        if(!active||!paused)return;
        paused=false;pausedForFocus=false;
        saveState();updateMediaState();updateNotification("Reading aloud");emit("resumed",currentOffset,1,null);
        maybeSpeak();
    }
    private void stopPlayback(boolean notify){
        if(!active){stopSelf();return;}
        ++playbackGeneration;
        active=false;paused=false;loaded=false;
        if(tts!=null)tts.stop();
        abandonAudioFocus();saveState();updateMediaState();
        if(notify)emit("stopped",currentOffset,1,null);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    private void finish(){
        currentOffset=totalChars;
        active=false;paused=false;
        abandonAudioFocus();saveState();updateMediaState();
        emit("finished",currentOffset,1,null);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    private void fail(String message){
        emit("error",currentOffset,1,message);
        stopPlayback(false);
    }
    private void saveState(){
        SharedPreferences.Editor editor=getSharedPreferences("playback",MODE_PRIVATE).edit();
        editor.putBoolean("active",active).putBoolean("paused",paused).putInt("offset",currentOffset).putString("sessionId",sessionId).apply();
    }
    private void emit(String type,int offset,int length,String message){
        JSONObject event=new JSONObject();
        try{event.put("type",type);event.put("sessionId",sessionId);event.put("offset",offset);event.put("length",length);if(message!=null)event.put("message",message);}catch(Exception ignored){}
        VoiceReaderActivity.notifyReader(event);
    }
    private PendingIntent serviceAction(String action,int id){
        Intent intent=new Intent(this,VoiceReaderService.class).setAction(action);
        return PendingIntent.getService(this,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(String state){
        Intent open=new Intent(this,VoiceReaderActivity.class);
        PendingIntent openApp=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Action toggle=new Notification.Action.Builder(paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,
            paused?"Resume":"Pause",serviceAction(paused?ACTION_RESUME:ACTION_PAUSE,1)).build();
        Notification.Action stop=new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop",serviceAction(ACTION_STOP,2)).build();
        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(state)
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(active&&!paused)
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1))
            .addAction(toggle).addAction(stop).build();
    }
    private void updateNotification(String message){
        if(active)getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(message));
    }
    private void updateMediaState(){
        if(mediaSession==null)return;
        int state=!active?PlaybackState.STATE_STOPPED:(paused?PlaybackState.STATE_PAUSED:PlaybackState.STATE_PLAYING);
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_PLAY_PAUSE;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state,PlaybackState.PLAYBACK_POSITION_UNKNOWN,rate).build());
        mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,"QuoteCraft Studio").build());
    }
    @Override public void onDestroy(){
        active=false;
        saveState();
        abandonAudioFocus();
        if(tts!=null){tts.stop();tts.shutdown();tts=null;}
        if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();mediaSession=null;}
        super.onDestroy();
    }
}
