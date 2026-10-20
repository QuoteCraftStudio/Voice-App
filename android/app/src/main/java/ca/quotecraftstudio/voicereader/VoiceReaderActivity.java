package ca.quotecraftstudio.voicereader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline-first reading UI, served at an HTTPS pseudo-origin so IndexedDB remains available. */
public final class VoiceReaderActivity extends Activity {
    private static WeakReference<VoiceReaderActivity> live = new WeakReference<>(null);
    private WebView web;
    private TextToSpeech enumerator;
    private volatile boolean voicesReady = false;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_PICKER = 742;
    private final NativeBridge nativeBridge = new NativeBridge();
    private final AtomicBoolean pageReady = new AtomicBoolean(false);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        live = new WeakReference<>(this);
        web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(nativeBridge, "VoiceReaderAndroid");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
                    picker.setType("*/*");
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(picker, "Choose a book or document"), FILE_PICKER);
                    return true;
                } catch (Exception ex) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost()) ||
                    !uri.getPath().startsWith("/assets/")) return null;
                String asset = uri.getPath().substring("/assets/".length());
                if (asset.contains("..")) return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                try {
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(asset));
                    if (mime == null) mime = "application/octet-stream";
                    return new WebResourceResponse(mime, "UTF-8", getAssets().open(asset));
                } catch (IOException ex) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                if (request.isForMainFrame()) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                pageReady.set(true);
                notifyVoicesReady();
                runOnUiThread(() -> web.evaluateJavascript("window.voiceReaderNativeEvent&&window.voiceReaderNativeEvent({type:'ready'})", null));
            }
        });
        enumerator = new TextToSpeech(this, init -> {
            voicesReady = init == TextToSpeech.SUCCESS;
            notifyVoicesReady();
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/voice-reader.html");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 514);
        }
    }

    private void notifyVoicesReady() {
        if (pageReady.get() && web != null && voicesReady) runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.voiceReaderNativeVoicesReady&&window.voiceReaderNativeVoicesReady()", null);
        });
    }

    public static void notifyReader(JSONObject event) {
        VoiceReaderActivity activity = live.get();
        if (activity == null || activity.web == null || !activity.pageReady.get()) return;
        final String js = "window.voiceReaderNativeEvent&&window.voiceReaderNativeEvent(" + event.toString() + ")";
        activity.runOnUiThread(() -> {if (activity.web != null) activity.web.evaluateJavascript(js, null);});
    }

    @Override protected void onResume() {
        super.onResume();
        live = new WeakReference<>(this);
        if (web != null) web.onResume();
        if (pageReady.get() && web != null) web.evaluateJavascript("window.nativeSynchronize&&window.nativeSynchronize()", null);
    }
    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER && fileCallback != null) {
            Uri[] result = resultCode == RESULT_OK && data != null && data.getData() != null ? new Uri[]{data.getData()} : null;
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        nativeBridge.cancelTransfer();
        if (enumerator != null) enumerator.shutdown();
        if (live.get() == this) live.clear();
        if (web != null) {web.destroy();web = null;}
        super.onDestroy();
    }

    private final class NativeBridge {
        private BufferedWriter writer;
        private BufferedWriter skips;
        private File speechFile;
        private File skipsFile;
        private String jsonConfig;

        @JavascriptInterface public synchronized void begin(String json) {
            cancelTransfer();
            try {
                JSONObject config = new JSONObject(json);
                if (config.optString("sessionId", "").length() > 100) throw new IOException("Invalid session");
                jsonConfig = config.toString();
                speechFile = File.createTempFile("voice-text-", ".txt", getCacheDir());
                skipsFile = File.createTempFile("voice-skips-", ".txt", getCacheDir());
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(speechFile), StandardCharsets.UTF_8));
                skips = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(skipsFile), StandardCharsets.UTF_8));
            } catch (Exception ex) { cancelTransfer(); throw new IllegalStateException("Cannot prepare reading", ex); }
        }
        @JavascriptInterface public synchronized void append(String text) {
            if (writer == null || text == null || text.length() > 30000) throw new IllegalStateException("Invalid transfer");
            try { writer.write(text); } catch (IOException ex) {throw new IllegalStateException(ex);}
        }
        @JavascriptInterface public synchronized void appendSkips(String ranges) {
            if (skips == null || ranges == null || ranges.length() > 30000) throw new IllegalStateException("Invalid skip data");
            try {skips.write(ranges);} catch (IOException ex) {throw new IllegalStateException(ex);}
        }
        @JavascriptInterface public synchronized void finish() {
            if (writer == null || skips == null || speechFile == null || jsonConfig == null) throw new IllegalStateException("No prepared document");
            try {writer.close();skips.close();} catch (IOException ex) {throw new IllegalStateException(ex);}
            writer = null;skips = null;
            final Intent start = new Intent(VoiceReaderActivity.this, VoiceReaderService.class);
            start.setAction(VoiceReaderService.ACTION_START);
            start.putExtra("document", speechFile.getAbsolutePath());
            start.putExtra("skips", skipsFile.getAbsolutePath());
            start.putExtra("config", jsonConfig);
            speechFile = null;skipsFile = null;jsonConfig = null;
            runOnUiThread(() -> startForegroundService(start));
        }
        @JavascriptInterface public synchronized void cancelTransfer() {
            if (writer != null) try {writer.close();} catch (IOException ignored) {}
            if (skips != null) try {skips.close();} catch (IOException ignored) {}
            writer = null;skips = null;jsonConfig = null;
            if (speechFile != null) speechFile.delete();
            if (skipsFile != null) skipsFile.delete();
            speechFile = null;skipsFile = null;
        }
        private void command(String action) {
            if (!getSharedPreferences("playback",MODE_PRIVATE).getBoolean("active",false)) return;
            runOnUiThread(() -> {Intent intent = new Intent(VoiceReaderActivity.this, VoiceReaderService.class); intent.setAction(action); startService(intent);});
        }
        @JavascriptInterface public void pause() {command(VoiceReaderService.ACTION_PAUSE);}
        @JavascriptInterface public void resume() {command(VoiceReaderService.ACTION_RESUME);}
        @JavascriptInterface public void stop() {command(VoiceReaderService.ACTION_STOP);}
        @JavascriptInterface public void setRate(double rate) {
            if (!Double.isFinite(rate) || rate < .5 || rate > 2) return;
            runOnUiThread(() -> {Intent intent = new Intent(VoiceReaderActivity.this,VoiceReaderService.class);intent.setAction(VoiceReaderService.ACTION_RATE);intent.putExtra("rate",(float)rate);if (getSharedPreferences("playback",MODE_PRIVATE).getBoolean("active",false))startService(intent);});
        }
        @JavascriptInterface public String getState() {
            android.content.SharedPreferences prefs=getSharedPreferences("playback",MODE_PRIVATE);
            JSONObject state=new JSONObject();
            try {state.put("active",prefs.getBoolean("active",false));state.put("paused",prefs.getBoolean("paused",false));state.put("offset",prefs.getInt("offset",0));state.put("sessionId",prefs.getString("sessionId",""));}catch(Exception ignored){}
            return state.toString();
        }
        @JavascriptInterface public String listVoices() {
            JSONArray result=new JSONArray();
            if (!voicesReady || enumerator == null) return result.toString();
            try {
                Set<Voice> all=enumerator.getVoices();
                if (all != null) for (Voice v:all) {
                    JSONObject voice = new JSONObject();
                    voice.put("name",v.getName());voice.put("lang",v.getLocale().toLanguageTag());voice.put("default",false);result.put(voice);
                }
            }catch(Exception ignored){}
            return result.toString();
        }
    }
}
