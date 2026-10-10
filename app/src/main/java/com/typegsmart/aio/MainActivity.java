package com.typegsmart.aio;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler;

/** WebView + جسر Type-G. الواجهة محلية من assets على https://typegsmart.local/. */
public class MainActivity extends AppCompatActivity {
    private WebView web;

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
            @Override public void onPageFinished(WebView v, String url) {
                // زرار ثابت لفتح إعداد Home Assistant — مزروع في body فمش بيتمسح مع إعادة رسم الواجهة
                if (url != null && (url.endsWith("/index.html") || url.endsWith("typegsmart.local/"))) {
                    v.evaluateJavascript(
                        "(function(){if(document.getElementById('ha-fab'))return;" +
                        "var b=document.createElement('button');b.id='ha-fab';b.textContent='HA';" +
                        "b.setAttribute('aria-label','Home Assistant');" +
                        "b.style.cssText='position:fixed;bottom:76px;inset-inline-start:16px;z-index:9999;" +
                        "width:48px;height:48px;border-radius:50%;border:0;background:#66edb4;color:#09291d;" +
                        "font-weight:700;font-size:14px;box-shadow:0 4px 14px rgba(0,0,0,.4)';" +
                        "b.onclick=function(){location.href='ha.html'};document.body.appendChild(b);})();", null);
                }
            }
        });

        web.addJavascriptInterface(new TypeGBridge(this), "TypeG");
        setContentView(web);

        ControlService.startSelf(this);
        web.loadUrl("https://typegsmart.local/index.html");
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.goBack && window.goBack()", null);
    }
}
