package com.kkomaprogrammer.iiferunner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "runner_prefs";
    private EditText urlInput;
    private EditText codeInput;
    private CheckBox autoStart;
    private WebView loginWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("IIFE Screen-Off Runner");
        title.setTextSize(22);
        root.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("대상 URL과 IIFE를 저장한 뒤 실행하세요. 실행 중에는 알림이 유지되고 화면이 꺼져도 CPU wake-lock을 잡습니다.");
        root.addView(hint, matchWrap());

        urlInput = new EditText(this);
        urlInput.setHint("https://example.com/");
        urlInput.setSingleLine(true);
        urlInput.setText(p.getString("url", "https://example.com/"));
        root.addView(urlInput, matchWrap());

        codeInput = new EditText(this);
        codeInput.setHint("(() => { /* 실행할 IIFE */ })();");
        codeInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        codeInput.setMinLines(9);
        codeInput.setText(p.getString("code", "(() => {\n  console.log('IIFE runner active', new Date());\n})();"));
        root.addView(codeInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        autoStart = new CheckBox(this);
        autoStart.setText("기기 재부팅 후 자동 재실행");
        autoStart.setChecked(p.getBoolean("autostart", true));
        root.addView(autoStart, matchWrap());

        Button start = new Button(this);
        start.setText("저장하고 실행");
        start.setOnClickListener(v -> startRunner());
        root.addView(start, matchWrap());

        Button stop = new Button(this);
        stop.setText("실행 중지");
        stop.setOnClickListener(v -> stopService(new Intent(this, RunnerService.class)));
        root.addView(stop, matchWrap());

        Button login = new Button(this);
        login.setText("로그인/페이지 열기 (쿠키 공유)");
        login.setOnClickListener(v -> openLoginWebView());
        root.addView(login, matchWrap());

        Button battery = new Button(this);
        battery.setText("배터리 최적화 설정 열기");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        root.addView(battery, matchWrap());

        setContentView(root);
    }

    private void startRunner() {
        String url = urlInput.getText().toString().trim();
        String code = codeInput.getText().toString();
        if (url.isEmpty() || code.trim().isEmpty()) {
            Toast.makeText(this, "URL과 IIFE 코드를 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("url", url)
                .putString("code", code)
                .putBoolean("autostart", autoStart.isChecked())
                .apply();

        Intent i = new Intent(this, RunnerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        Toast.makeText(this, "백그라운드 실행을 시작했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void openLoginWebView() {
        if (loginWebView != null) return;
        loginWebView = new WebView(this);
        loginWebView.getSettings().setJavaScriptEnabled(true);
        loginWebView.getSettings().setDomStorageEnabled(true);
        loginWebView.setWebViewClient(new WebViewClient());
        loginWebView.setWebChromeClient(new WebChromeClient());
        addContentView(loginWebView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loginWebView.loadUrl(urlInput.getText().toString().trim());
    }

    @Override
    public void onBackPressed() {
        if (loginWebView != null) {
            if (loginWebView.canGoBack()) {
                loginWebView.goBack();
            } else {
                ((ViewGroup) loginWebView.getParent()).removeView(loginWebView);
                loginWebView.destroy();
                loginWebView = null;
            }
            return;
        }
        super.onBackPressed();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
