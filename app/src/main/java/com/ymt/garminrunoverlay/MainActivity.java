package com.ymt.garminrunoverlay;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://garmin-run-overlay-587q24.v2.appdeploy.ai/?native=1";
    private static final String DEEP_LINK_SCHEME = "garminrunoverlay";
    private static final String DEEP_LINK_HOST = "oauth-complete";
    private static final int FILE_CHOOSER_REQUEST = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieManager.getInstance().setAcceptCookie(true);
        webView = new WebView(this);
        configureWebView(webView, null, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        setContentView(webView);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (!handleDeepLink(getIntent())) {
            webView.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleDeepLink(intent) && webView != null) {
            webView.loadUrl(APP_URL);
        }
    }

    private boolean handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        Uri uri = intent.getData();
        if (!DEEP_LINK_SCHEME.equals(uri.getScheme()) || !DEEP_LINK_HOST.equals(uri.getHost())) {
            return false;
        }

        String status = uri.getQueryParameter("status");
        String error = uri.getQueryParameter("error");
        String target;
        if ("connected".equals(status)) {
            target = APP_URL + "#fitness-ai=connected";
        } else {
            String message = error == null || error.isEmpty() ? "OAuth 인증이 완료되지 않았습니다." : error;
            target = APP_URL + "#fitness-ai-error=" + Uri.encode(message);
        }
        if (webView != null) webView.loadUrl(target);
        return true;
    }

    private void configureWebView(WebView view, Dialog popupDialog, boolean trustedMainView) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

        if (trustedMainView) {
            view.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        }

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView currentWebView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView source, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                final Dialog dialog = new Dialog(MainActivity.this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
                final WebView child = new WebView(MainActivity.this);
                configureWebView(child, dialog, false);
                dialog.setContentView(child, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                dialog.setOnDismissListener(d -> child.destroy());
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                dialog.show();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (popupDialog != null && popupDialog.isShowing()) popupDialog.dismiss();
            }
        });
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void openExternal(String url) {
            if (url == null) return;
            Uri uri;
            try {
                uri = Uri.parse(url);
            } catch (Exception ignored) {
                return;
            }
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return;
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }
}
