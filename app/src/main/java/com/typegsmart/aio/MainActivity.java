package com.typegsmart.aio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler;

/** WebView + جسر Type-G. الواجهة محلية من assets على https://typegsmart.local/. */
public class MainActivity extends AppCompatActivity {
    private WebView web;
    private int insetTop, insetBottom, insetLeft, insetRight;
    private static MainActivity current;

    /** حقن حواف النظام كمتغيرات CSS في :root. */
    private void applyInsets() {
        if (web == null) return;
        String js = "var r=document.documentElement.style;"
            + "r.setProperty('--inset-top','" + insetTop + "px');"
            + "r.setProperty('--inset-bottom','" + insetBottom + "px');"
            + "r.setProperty('--inset-left','" + insetLeft + "px');"
            + "r.setProperty('--inset-right','" + insetRight + "px');";
        web.evaluateJavascript(js, null);
    }

    /** دفع نتيجة/تقدّم للواجهة (من خيوط الخلفية). */
    public static void pushJs(final String js) {
        final MainActivity a = current;
        if (a == null || a.web == null) return;
        a.runOnUiThread(() -> a.web.evaluateJavascript(js, null));
    }

    /** طلب صلاحيات الواي فاي/الموقع اللازمة للإعداد. */
    public static void requestWifiPerms() {
        final MainActivity a = current;
        if (a == null) return;
        java.util.List<String> need = new java.util.ArrayList<>();
        if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 &&
            a.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (!need.isEmpty()) ActivityCompat.requestPermissions(a, need.toArray(new String[0]), 42);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain("typegsmart.local")
                .addPathHandler("/", new AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
            @Override public void onPageFinished(WebView v, String url) { applyInsets(); }  // بعد تحميل الصفحة
        });

        web.setBackgroundColor(0xFF0A1019);   // لون الخلفية في منطقة شرائط النظام
        // edge-to-edge: نحقن حواف النظام كمتغيرات CSS بدل padding على الـ WebView (أضمن مع العناصر الثابتة)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            float d = getResources().getDisplayMetrics().density;
            insetTop = Math.round(bars.top / d);  insetBottom = Math.round(bars.bottom / d);
            insetLeft = Math.round(bars.left / d); insetRight = Math.round(bars.right / d);
            applyInsets();
            return insets;
        });

        web.addJavascriptInterface(new TypeGBridge(this), "TypeG");
        setContentView(web);
        ViewCompat.requestApplyInsets(web);
        current = this;

        ControlService.startSelf(this);
        web.loadUrl("https://typegsmart.local/index.html");
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.goBack && window.goBack()", null);
    }
}
