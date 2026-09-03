package com.kkomaprogrammer.iiferunner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS = "runner_prefs";
    private EditText targetInput, codeInput;
    private CheckBox autoStart, nativeTimers, overlayMode;
    private TextView status;
    private WebView loginWebView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14); root.setPadding(pad,pad,pad,pad); scroll.addView(root);

        TextView title = new TextView(this); title.setText("IIFE Screen-Off Runner 2.0"); title.setTextSize(22); root.addView(title, matchWrap());
        TextView hint = new TextView(this);
        hint.setText("한 줄에 하나씩 입력하세요.\n일반: https://playentry.org/*\n와일드카드 호스트: 실제시작URL | 패턴\n예) https://app.example.com/ | https://*.example.com/*");
        root.addView(hint, matchWrap());

        targetInput = new EditText(this); targetInput.setSingleLine(false); targetInput.setGravity(Gravity.TOP|Gravity.START); targetInput.setMinLines(4);
        targetInput.setHint("https://playentry.org/*\nhttps://app.example.com/ | https://*.example.com/*");
        String saved = p.getString("targets", p.getString("urlPatterns", "https://playentry.org/*")); targetInput.setText(saved); root.addView(targetInput, matchWrap());

        codeInput = new EditText(this); codeInput.setGravity(Gravity.TOP|Gravity.START); codeInput.setMinLines(10);
        codeInput.setHint("(() => { /* IIFE */ })();"); codeInput.setText(p.getString("code", "(() => { console.log('IIFE runner OK', new Date()); })();"));
        root.addView(codeInput, matchWrap());

        nativeTimers = new CheckBox(this); nativeTimers.setText("강력 타이머: IIFE의 setTimeout/setInterval을 Android 네이티브 타이머로 실행"); nativeTimers.setChecked(p.getBoolean("nativeTimers", true)); root.addView(nativeTimers, matchWrap());
        overlayMode = new CheckBox(this); overlayMode.setText("강력 WebView 유지: 1px 오버레이에 실행 WebView 연결"); overlayMode.setChecked(p.getBoolean("overlayMode", true)); root.addView(overlayMode, matchWrap());
        autoStart = new CheckBox(this); autoStart.setText("기기 재부팅 후 자동 재실행"); autoStart.setChecked(p.getBoolean("autostart", true)); root.addView(autoStart, matchWrap());

        Button battery = new Button(this); battery.setText("1. 배터리 최적화 제외 허용 (중요)"); battery.setOnClickListener(v -> requestIgnoreBatteryOptimization()); root.addView(battery, matchWrap());
        Button overlay = new Button(this); overlay.setText("2. 다른 앱 위 표시 허용 (강력 WebView 유지용)"); overlay.setOnClickListener(v -> requestOverlayPermission()); root.addView(overlay, matchWrap());
        Button start = new Button(this); start.setText("3. 저장하고 실행"); start.setOnClickListener(v -> startRunner()); root.addView(start, matchWrap());
        Button stop = new Button(this); stop.setText("실행 중지"); stop.setOnClickListener(v -> { stopService(new Intent(this, RunnerService.class)); Toast.makeText(this,"중지했습니다.",Toast.LENGTH_SHORT).show(); }); root.addView(stop, matchWrap());
        Button login = new Button(this); login.setText("첫 대상 로그인/페이지 열기 (쿠키 공유)"); login.setOnClickListener(v -> openLoginWebView()); root.addView(login, matchWrap());
        Button refresh = new Button(this); refresh.setText("실행 상태 새로고침"); refresh.setOnClickListener(v -> refreshStatus()); root.addView(refresh, matchWrap());

        status = new TextView(this); status.setPadding(0,dp(10),0,dp(20)); root.addView(status, matchWrap());
        setContentView(scroll); refreshStatus();
    }

    private void startRunner() {
        String targets = targetInput.getText().toString().trim(), code = codeInput.getText().toString();
        List<RunnerService.TargetSpec> parsed = RunnerService.parseTargets(targets);
        if (parsed.isEmpty() || code.trim().isEmpty()) { Toast.makeText(this,"실행 가능한 URL과 IIFE를 입력하세요.",Toast.LENGTH_LONG).show(); return; }
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("targets",targets).putString("code",code)
                .putBoolean("nativeTimers",nativeTimers.isChecked()).putBoolean("overlayMode",overlayMode.isChecked())
                .putBoolean("autostart",autoStart.isChecked()).apply();
        Intent i = new Intent(this, RunnerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, parsed.size()+"개 대상 실행 시작", Toast.LENGTH_SHORT).show();
        status.postDelayed(this::refreshStatus, 1200);
    }

    private void refreshStatus() {
        SharedPreferences p = getSharedPreferences(PREFS,MODE_PRIVATE);
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        boolean batteryOk = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        status.setText("배터리 최적화 제외: "+(batteryOk?"허용됨":"필요")+"\n다른 앱 위 표시: "+(overlayOk?"허용됨":"미허용")+"\n최근 상태: "+p.getString("lastStatus","아직 실행 기록 없음"));
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < 23) return;
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:"+getPackageName()))); }
        catch(Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
    }
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()))); }
        catch(Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void openLoginWebView() {
        if (loginWebView != null) return;
        List<RunnerService.TargetSpec> parsed = RunnerService.parseTargets(targetInput.getText().toString());
        if (parsed.isEmpty()) { Toast.makeText(this,"실행 가능한 첫 URL이 없습니다.",Toast.LENGTH_SHORT).show(); return; }
        loginWebView = new WebView(this); loginWebView.getSettings().setJavaScriptEnabled(true); loginWebView.getSettings().setDomStorageEnabled(true);
        loginWebView.setWebViewClient(new WebViewClient()); loginWebView.setWebChromeClient(new WebChromeClient());
        addContentView(loginWebView,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        loginWebView.loadUrl(parsed.get(0).startUrl);
    }

    @Override public void onBackPressed() {
        if (loginWebView != null) {
            if (loginWebView.canGoBack()) loginWebView.goBack();
            else { ((ViewGroup)loginWebView.getParent()).removeView(loginWebView); loginWebView.destroy(); loginWebView=null; }
            return;
        }
        super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); if(status!=null) refreshStatus(); }
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!= PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
    }
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
