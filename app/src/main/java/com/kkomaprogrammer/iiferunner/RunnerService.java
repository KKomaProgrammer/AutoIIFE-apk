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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RunnerService extends Service {
    private static final String CHANNEL_ID = "iife_runner";
    private static final int NOTIFICATION_ID = 7201;
    private PowerManager.WakeLock wakeLock;
    private final List<WebView> webViews = new ArrayList<>();
    private String code;
    private int injectedPages = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, createNotification("실행 준비 중"));
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences("runner_prefs", MODE_PRIVATE);
        String patternText = p.getString("urlPatterns", null);
        if (patternText == null) patternText = p.getString("url", "https://example.com/*");
        code = p.getString("code", "(() => {})();");

        destroyRunnerWebViews();
        injectedPages = 0;

        List<String> patterns = parsePatterns(patternText);
        for (String pattern : patterns) {
            String startUrl = patternToStartUrl(pattern);
            if (startUrl == null) continue;
            WebView view = createRunnerWebView(pattern);
            webViews.add(view);
            view.loadUrl(startUrl);
        }

        updateNotification("실행 중: " + webViews.size() + "개 URL/패턴");
        return START_STICKY;
    }

    private WebView createRunnerWebView(final String pattern) {
        WebView view = new WebView(getApplicationContext());
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setDatabaseEnabled(true);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 26) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView finishedView, String url) {
                if (matchesPattern(url, pattern)) {
                    inject(finishedView);
                    injectedPages++;
                    updateNotification("IIFE 실행 중: " + injectedPages + "회 / " + webViews.size() + "개 대상");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        return view;
    }

    private void inject(WebView view) {
        if (code == null || code.trim().isEmpty()) return;
        String wrapped = "try{\n" + code + "\n}catch(e){console.error('IIFE Runner:',e);}";
        view.evaluateJavascript(wrapped, null);
    }

    public static List<String> parsePatterns(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.replace("\r", "").split("\n");
        for (String line : lines) {
            String p = line.trim();
            if (p.isEmpty() || p.startsWith("#")) continue;
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    public static boolean matchesPattern(String url, String wildcard) {
        if (url == null || wildcard == null || wildcard.trim().isEmpty()) return false;
        String[] parts = wildcard.trim().split("\\*", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            regex.append(Pattern.quote(parts[i]));
            if (i < parts.length - 1) regex.append(".*");
        }
        regex.append("$");
        try {
            return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(url).matches();
        } catch (Exception e) {
            return false;
        }
    }

    public static String patternToStartUrl(String pattern) {
        if (pattern == null) return null;
        String p = pattern.trim();
        if (p.isEmpty()) return null;

        if (!p.contains("://")) p = "https://" + p;
        if (p.startsWith("*://")) p = "https://" + p.substring(4);

        int scheme = p.indexOf("://");
        if (scheme < 0) return null;
        int hostStart = scheme + 3;
        int pathStart = p.indexOf('/', hostStart);
        String prefix = p.substring(0, hostStart);
        String host = pathStart >= 0 ? p.substring(hostStart, pathStart) : p.substring(hostStart);
        String rest = pathStart >= 0 ? p.substring(pathStart) : "/";

        host = host.equals("*") ? "example.com" : host.replace("*", "www");
        rest = rest.replace("*", "");
        if (host.isEmpty()) return null;
        if (rest.isEmpty()) rest = "/";

        String url = prefix + host + rest;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null;
        return url;
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

    private void destroyRunnerWebViews() {
        for (WebView view : webViews) {
            try {
                view.stopLoading();
                view.loadUrl("about:blank");
                view.destroy();
            } catch (Exception ignored) {}
        }
        webViews.clear();
    }

    @Override
    public void onDestroy() {
        destroyRunnerWebViews();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
