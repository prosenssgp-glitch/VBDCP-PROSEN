package com.vbdcp.attendance;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.ContentValues;
import android.content.ClipData;
import org.json.JSONArray;
import java.util.ArrayList;
import android.os.Build;
import android.util.Base64;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.io.FileOutputStream;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 101;
    private static final int FILE_CHOOSER = 102;
    private static final int REQ_CAMERA_PERMISSION = 103;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private android.webkit.PermissionRequest pendingCameraRequest;
    private boolean appPermissionsRequestInFlight = false;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        swipeRefresh = new SwipeRefreshLayout(this);
        webView = new WebView(this);
        swipeRefresh.addView(webView, new SwipeRefreshLayout.LayoutParams(-1, -1));
        setContentView(swipeRefresh);
        assetLoader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        setupWebView();
        requestAppPermissions();
        // Load the packaged app first: cold start works without Netlify/Internet.
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setLoadsImagesAutomatically(true);
        // App HTML/CSS/JS are packaged in the APK; network is only needed for cloud features.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        swipeRefresh.setOnRefreshListener(() -> webView.reload());
        swipeRefresh.setEnabled(true);

        webView.setWebViewClient(new LocalAssetClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }
                // Do not reject geolocation just because Android's permission dialog has not completed yet.
                if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                if (!appPermissionsRequestInFlight) {
                    appPermissionsRequestInFlight = true;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
                }
            }

            @Override public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri requestOrigin = request.getOrigin();
                    if (requestOrigin == null || !"appassets.androidplatform.net".equalsIgnoreCase(requestOrigin.getHost())) {
                        request.deny();
                        return;
                    }
                    if (android.os.Build.VERSION.SDK_INT < 23 ||
                        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                        return;
                    }

                    // Keep the WebView request alive while Android asks for camera permission.
                    if (pendingCameraRequest != null && pendingCameraRequest != request) {
                        pendingCameraRequest.deny();
                    }
                    pendingCameraRequest = request;
                    if (!appPermissionsRequestInFlight) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                    }
                });
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    launchEmployeeCamera(false);
                } catch (Exception e) {
                    fileCallback = null;
                    cameraUri = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "VBDCPNative");
    }

    private void launchEmployeeCamera(boolean front) {
        try {
            Intent camera = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            if (camera.resolveActivity(getPackageManager()) == null) throw new IllegalStateException("No camera app");
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "VBDCP");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create camera folder");
            File photo = File.createTempFile("VBDCP_EMP_", ".jpg", dir);
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            camera.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(camera, FILE_CHOOSER);
        } catch (Exception e) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = null; cameraUri = null;
        }
    }

    private void requestAppPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            java.util.ArrayList<String> p = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.CAMERA);
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
            if (android.os.Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (!p.isEmpty()) {
                appPermissionsRequestInFlight = true;
                requestPermissions(p.toArray(new String[0]), REQ_PERMS);
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS || requestCode == REQ_CAMERA_PERMISSION) {
            appPermissionsRequestInFlight = false;
            if (pendingCameraRequest != null) {
                android.webkit.PermissionRequest request = pendingCameraRequest;
                pendingCameraRequest = null;
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    request.deny();
                }
            }
            if (pendingGeoCallback != null) {
                GeolocationPermissions.Callback callback = pendingGeoCallback;
                String origin = pendingGeoOrigin;
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
                boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] results = (resultCode == RESULT_OK && cameraUri != null) ? new Uri[]{cameraUri} : null;
            fileCallback.onReceiveValue(results);
            fileCallback = null; cameraUri = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    private class NativeBridge {

        @JavascriptInterface public String saveFieldPhotoToGallery(String base64, String fileName) {
            try {
                if (base64 == null || base64.isEmpty()) return "ERROR:ছবির ডেটা খালি";
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safeName = (fileName == null ? "VBDCP_" + System.currentTimeMillis() + ".jpg" : fileName.replaceAll("[^A-Za-z0-9._-]", "_"));
                if (!safeName.toLowerCase(Locale.US).endsWith(".jpg")) safeName += ".jpg";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VBDCP");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "ERROR:Gallery-তে ছবি তৈরি করা যায়নি";
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("ছবির ফাইল খোলা যায়নি");
                        out.write(bytes);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                    return uri.toString();
                }
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return "ERROR:Storage permission দিন";
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VBDCP");
                if (!dir.exists() && !dir.mkdirs()) return "ERROR:Gallery folder তৈরি হয়নি";
                File image = new File(dir, safeName);
                try (FileOutputStream out = new FileOutputStream(image)) { out.write(bytes); }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(image)));
                return Uri.fromFile(image).toString();
            } catch (Exception e) { return "ERROR:" + (e.getMessage() == null ? "Gallery save failed" : e.getMessage()); }
        }
        @JavascriptInterface public void shareGalleryPhotos(String uriJson) {
            try {
                JSONArray values = new JSONArray(uriJson);
                final ArrayList<Uri> uris = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) {
                    String value = values.optString(i, "");
                    if (value.startsWith("content://")) uris.add(Uri.parse(value));
                }
                if (uris.isEmpty()) return;
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    send.setType("image/jpeg");
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ClipData clip = ClipData.newUri(getContentResolver(), "VBDCP work photos", uris.get(0));
                    for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                    send.setClipData(clip);
                    send.putExtra(Intent.EXTRA_TEXT, "VBDCP কাজের ছবি");
                    startActivity(Intent.createChooser(send, "ছবি Share করুন"));
                });
            } catch (Exception e) { android.util.Log.e("VBDCP", "Photo share failed", e); }
        }
        @JavascriptInterface public void openAppSettings() {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch(Exception ignored) {}
        }
        @JavascriptInterface public void openWhatsAppGroup(String url) { openExternalUrl(url); }
    }

    private void openExternalUrl(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) return;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(raw.trim()));
            if (raw.startsWith("whatsapp:")) i.setPackage("com.whatsapp");
            try { startActivity(i); } catch (Exception e) {
                i.setPackage(null);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    private class LocalAssetClient extends WebViewClient {
        private boolean isTrustedAppUrl(Uri u) {
            return u != null && "https".equalsIgnoreCase(u.getScheme()) &&
                "appassets.androidplatform.net".equalsIgnoreCase(u.getHost());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u=request.getUrl(); String scheme=u.getScheme();
            if ("about".equalsIgnoreCase(scheme)) return false;
            if (isTrustedAppUrl(u)) return false;
            openExternalUrl(u.toString());
            return true;
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri u=Uri.parse(url);
            if ("about".equalsIgnoreCase(u.getScheme())) return false;
            if (isTrustedAppUrl(u)) return false;
            openExternalUrl(url);
            return true;
        }
        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view,url);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            WebResourceResponse packaged = assetLoader.shouldInterceptRequest(u);
            if (packaged != null) return packaged;
            if ("vbdcp.local".equalsIgnoreCase(u.getHost())) {
                String path = u.getPath(); if (path == null || path.equals("/")) path = "/index.html";
                if (path.startsWith("/")) path = path.substring(1);
                try {
                    InputStream in = view.getContext().getAssets().open(path);
                    return new WebResourceResponse(mime(path), "UTF-8", 200, "OK", null, in);
                } catch (IOException ignored) {}
            }
            return super.shouldInterceptRequest(view, request);
        }
        private String mime(String p) {
            String ext=MimeTypeMap.getFileExtensionFromUrl(p).toLowerCase(Locale.US);
            String m=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if(m!=null)return m; if(ext.equals("js"))return "application/javascript"; if(ext.equals("svg"))return "image/svg+xml"; if(ext.equals("webmanifest"))return "application/manifest+json"; return "text/plain";
        }
    }
}
