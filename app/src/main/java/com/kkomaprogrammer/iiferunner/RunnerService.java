package com.kkomaprogrammer.iiferunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class RunnerService extends Service {
    private static final String CHANNEL_ID="iife_runner_v2"; private static final int NOTIFICATION_ID=7201;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<RunnerSlot> slots = new ArrayList<>();
    private PowerManager.WakeLock wakeLock; private String code=""; private boolean nativeTimers=true, overlayMode=true;
    private WindowManager windowManager; private FrameLayout overlayHost; private int successfulRuns=0;

    public static class TargetSpec {
        public final String startUrl, pattern;
        TargetSpec(String startUrl,String pattern){this.startUrl=startUrl;this.pattern=pattern;}
    }

    private class RunnerSlot {
        final TargetSpec target; WebView view; NativeTimerBridge bridge;
        RunnerSlot(TargetSpec t){target=t;}
        void destroy(){ if(bridge!=null)bridge.cancelAll(); if(view!=null){ try{ if(view.getParent() instanceof FrameLayout)((FrameLayout)view.getParent()).removeView(view); view.stopLoading(); view.destroy(); }catch(Exception ignored){} view=null;} }
    }

    @Override public void onCreate(){ super.onCreate(); createChannel(); startForegroundCompat(createNotification("실행 준비 중")); acquireWakeLock(); }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        SharedPreferences p=getSharedPreferences("runner_prefs",MODE_PRIVATE);
        String targetsText=p.getString("targets",p.getString("urlPatterns","https://playentry.org/*")); code=p.getString("code","(() => {})();");
        nativeTimers=p.getBoolean("nativeTimers",true); overlayMode=p.getBoolean("overlayMode",true); successfulRuns=0;
        destroyAll(); setupOverlayHost();
        List<TargetSpec> targets=parseTargets(targetsText);
        for(TargetSpec t:targets){RunnerSlot slot=new RunnerSlot(t);slots.add(slot);createAndLoad(slot);}
        setStatus("실행 시작: "+slots.size()+"개 대상"+(overlayHost!=null?" / 오버레이 유지 ON":" / 오버레이 유지 OFF"));
        return START_STICKY;
    }

    private void createAndLoad(final RunnerSlot slot){
        if(slot.view!=null)return;
        WebView v=new WebView(this); slot.view=v; slot.bridge=new NativeTimerBridge(v);
        v.getSettings().setJavaScriptEnabled(true); v.getSettings().setDomStorageEnabled(true); v.getSettings().setDatabaseEnabled(true); v.getSettings().setMediaPlaybackRequiresUserGesture(false);
        if(Build.VERSION.SDK_INT>=26)v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,false);
        v.addJavascriptInterface(slot.bridge,"IifeNativeTimer");
        v.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){if(m.messageLevel()== ConsoleMessage.MessageLevel.ERROR)setStatus("JS 콘솔 오류: "+clip(m.message(),300));return true;}});
        v.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){ if(slot.bridge!=null)slot.bridge.cancelAll(); }
            @Override public void onPageFinished(WebView view,String url){ if(matchesPattern(url,slot.target.pattern)) injectUserCode(slot,url); else setStatus("패턴 불일치: "+url); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){return false;}
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err){if(req.isForMainFrame())setStatus("페이지 로드 오류: "+err.getDescription());}
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail){
                setStatus("WebView 렌더러 종료 감지 → 자동 복구 중");
                if(slot.bridge!=null)slot.bridge.cancelAll(); try{if(view.getParent() instanceof FrameLayout)((FrameLayout)view.getParent()).removeView(view);}catch(Exception ignored){} try{view.destroy();}catch(Exception ignored){} slot.view=null; slot.bridge=null;
                main.postDelayed(() -> createAndLoad(slot),1500); return true;
            }
        });
        if(overlayHost!=null){ FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(1,1); overlayHost.addView(v,lp); }
        v.loadUrl(slot.target.startUrl);
    }

    private void injectUserCode(RunnerSlot slot,String url){
        if(slot.view==null||code.trim().isEmpty())return;
        String bootstrap="(() => { if (window.__IIFE_NATIVE_TIMER_API__) return; const cbs=new Map(); let next=100000; window.__IIFE_NATIVE_TIMER_FIRE__=(id)=>{const x=cbs.get(Number(id)); if(!x)return; try{x.fn(...x.args);}catch(e){console.error('[IIFE Runner timer]',e);} if(!x.repeat)cbs.delete(Number(id));}; window.__IIFE_NATIVE_TIMER_API__={setTimeout:(fn,ms,...args)=>{if(typeof fn!=='function')return window.setTimeout(fn,ms,...args);const id=next++;cbs.set(id,{fn,args,repeat:false});IifeNativeTimer.schedule(id,Math.max(0,Math.min(2147483647,Number(ms)||0)),false);return id;},setInterval:(fn,ms,...args)=>{if(typeof fn!=='function')return window.setInterval(fn,ms,...args);const id=next++;cbs.set(id,{fn,args,repeat:true});IifeNativeTimer.schedule(id,Math.max(10,Math.min(2147483647,Number(ms)||0)),true);return id;},clearTimeout:(id)=>{cbs.delete(Number(id));IifeNativeTimer.cancel(Number(id));},clearInterval:(id)=>{cbs.delete(Number(id));IifeNativeTimer.cancel(Number(id));}}; })();";
        slot.view.evaluateJavascript(bootstrap, ignored -> {
            if(slot.view==null)return;
            String wrapped;
            if(nativeTimers) wrapped="(() => { const __t=window.__IIFE_NATIVE_TIMER_API__; const setTimeout=__t.setTimeout, setInterval=__t.setInterval, clearTimeout=__t.clearTimeout, clearInterval=__t.clearInterval; try {\n"+code+"\n;document.documentElement&&document.documentElement.setAttribute('data-iife-runner-status','ok');} catch(e){document.documentElement&&document.documentElement.setAttribute('data-iife-runner-status','error:'+String(e));console.error('[IIFE Runner]',e);} })();";
            else wrapped="(() => { try {\n"+code+"\n;document.documentElement&&document.documentElement.setAttribute('data-iife-runner-status','ok');} catch(e){document.documentElement&&document.documentElement.setAttribute('data-iife-runner-status','error:'+String(e));console.error('[IIFE Runner]',e);} })();";
            slot.view.evaluateJavascript(wrapped, result -> { successfulRuns++; setStatus("IIFE 실행 성공 #"+successfulRuns+" : "+clip(url,120)); });
        });
    }

    public static List<TargetSpec> parseTargets(String text){
        List<TargetSpec> out=new ArrayList<>(); if(text==null)return out;
        for(String raw:text.replace("\r","").split("\n")){
            String line=raw.trim(); if(line.isEmpty()||line.startsWith("#"))continue;
            String start,pattern; int bar=line.indexOf('|');
            if(bar>=0){start=normalizeStartUrl(line.substring(0,bar).trim());pattern=line.substring(bar+1).trim();}
            else {pattern=line;start=patternToStartUrl(pattern);}
            if(start!=null&&!pattern.isEmpty())out.add(new TargetSpec(start,pattern));
        }
        return out;
    }

    private static String normalizeStartUrl(String s){if(s==null||s.trim().isEmpty())return null;s=s.trim();if(!s.contains("://"))s="https://"+s;return (s.startsWith("http://")||s.startsWith("https://"))?s:null;}
    public static String patternToStartUrl(String pattern){
        if(pattern==null)return null; String p=pattern.trim(); if(p.isEmpty())return null; if(!p.contains("://"))p="https://"+p; if(p.startsWith("*://"))p="https://"+p.substring(4);
        int scheme=p.indexOf("://"),hostStart=scheme+3,pathStart=p.indexOf('/',hostStart); if(scheme<0)return null;
        String host=pathStart>=0?p.substring(hostStart,pathStart):p.substring(hostStart); if(host.contains("*"))return null;
        String rest=pathStart>=0?p.substring(pathStart):"/"; int star=rest.indexOf('*'); if(star>=0)rest=rest.substring(0,star); if(rest.isEmpty())rest="/";
        return normalizeStartUrl(p.substring(0,hostStart)+host+rest);
    }
    public static boolean matchesPattern(String url,String wildcard){
        if(url==null||wildcard==null||wildcard.trim().isEmpty())return false; String[] parts=wildcard.trim().split("\\*",-1);StringBuilder regex=new StringBuilder("^");
        for(int i=0;i<parts.length;i++){regex.append(Pattern.quote(parts[i]));if(i<parts.length-1)regex.append(".*");}regex.append("$");
        try{return Pattern.compile(regex.toString(),Pattern.CASE_INSENSITIVE).matcher(url).matches();}catch(Exception e){return false;}
    }

    private class NativeTimerBridge {
        final WebView webView; final Map<Integer,Runnable> tasks=new ConcurrentHashMap<>();
        NativeTimerBridge(WebView v){webView=v;}
        @JavascriptInterface public void schedule(final int id, final double delayMs, final boolean repeat){
            main.post(() -> { cancelInternal(id); final long delay=Math.max(repeat?10:0,Math.min(2147483647L,(long)delayMs)); Runnable r=new Runnable(){@Override public void run(){if(!tasks.containsKey(id))return;try{webView.evaluateJavascript("window.__IIFE_NATIVE_TIMER_FIRE__&&window.__IIFE_NATIVE_TIMER_FIRE__("+id+");",null);}catch(Exception ignored){} if(repeat&&tasks.containsKey(id))main.postDelayed(this,delay);else tasks.remove(id);}}; tasks.put(id,r);main.postDelayed(r,delay); });
        }
        @JavascriptInterface public void cancel(final int id){main.post(() -> cancelInternal(id));}
        void cancelInternal(int id){Runnable r=tasks.remove(id);if(r!=null)main.removeCallbacks(r);} void cancelAll(){main.post(() -> {for(Runnable r:tasks.values())main.removeCallbacks(r);tasks.clear();});}
    }

    private void setupOverlayHost(){
        removeOverlayHost(); if(!overlayMode||Build.VERSION.SDK_INT<23||!Settings.canDrawOverlays(this))return;
        try{windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);overlayHost=new FrameLayout(this); WindowManager.LayoutParams lp=new WindowManager.LayoutParams(Math.max(1,slots.size()+1),1,Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.START;lp.x=0;lp.y=0;lp.alpha=0.01f;windowManager.addView(overlayHost,lp);}catch(Exception e){overlayHost=null;setStatus("오버레이 생성 실패: "+clip(String.valueOf(e),180));}
    }
    private void removeOverlayHost(){if(overlayHost!=null&&windowManager!=null){try{windowManager.removeViewImmediate(overlayHost);}catch(Exception ignored){}}overlayHost=null;windowManager=null;}
    private void acquireWakeLock(){PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IifeRunner::PersistentExecution");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
    private void startForegroundCompat(Notification n){if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION_ID,n);}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"IIFE 백그라운드 실행",NotificationManager.IMPORTANCE_LOW);c.setDescription("화면이 꺼진 동안 사용자가 지정한 웹 IIFE 실행 유지");((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    private Notification createNotification(String text){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);return b.setContentTitle("IIFE Screen-Off Runner").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setColor(Color.DKGRAY).setContentIntent(pi).build();}
    private void setStatus(String text){getSharedPreferences("runner_prefs",MODE_PRIVATE).edit().putString("lastStatus",text).apply();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,createNotification(text));}
    private static String clip(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n)+"…";}
    private void destroyAll(){for(RunnerSlot s:slots)s.destroy();slots.clear();removeOverlayHost();}
    @Override public void onDestroy(){destroyAll();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
