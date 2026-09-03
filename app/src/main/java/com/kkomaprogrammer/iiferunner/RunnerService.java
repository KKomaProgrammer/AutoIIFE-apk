package com.kkomaprogrammer.iiferunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class RunnerService extends Service {
    private static final String CHANNEL_ID = "iife_runner";
    private static final int NOTIFICATION_ID = 7201;
    private PowerManager.WakeLock wakeLock;
    private WebView webView;
    private String code;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, createNotification("실행 준비 중"));
        acquireWakeLock();
        createRunnerWebView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences("runner_prefs", MODE_PRIVATE);
        String url = p.getString("url", "https://example.com/");
        code = p.getString("code", "(() => {})();");
        if (webView != null) {
            webView.loadUrl(url);
            updateNotification("실행 중: " + compact(url));
        }
        return START_STICKY;
    }

    private void createRunnerWebView() {
        webView = new WebView(getApplicationContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 26) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                inject(view);
                updateNotification("IIFE 실행 중: " + compact(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
    }

    private void inject(WebView view) {
        if (code == null || code.trim().isEmpty()) return;
        // 새 문서마다 1회 주입. 페이지 자체의 SPA 내부 전환은 IIFE가 계속 살아있도록 작성하는 것이 가장 안정적입니다.
        String wrapped = "try{\n" + code + "\n}catch(e){console.error('IIFE Runner:',e);}";
        view.evaluateJavascript(wrapped, null);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IifeRunner::ScreenOffExecution");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "IIFE 백그라운드 실행",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("화면이 꺼져도 IIFE 실행을 유지하기 위한 포그라운드 서비스");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("IIFE Screen-Off Runner")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setColor(Color.DKGRAY)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, createNotification(text));
    }

    private String compact(String url) {
        if (url == null) return "";
        return url.length() > 55 ? url.substring(0, 52) + "..." : url;
    }

    @Override
    public void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
