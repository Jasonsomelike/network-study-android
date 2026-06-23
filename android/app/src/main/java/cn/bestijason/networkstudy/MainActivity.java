package cn.bestijason.networkstudy;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;
import com.tencent.tauth.IUiListener;
import com.tencent.tauth.Tencent;
import com.tencent.tauth.UiError;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends BridgeActivity {
    private static final String MOBILE_QQ_APP_ID = "1904508499";
    private static final String PREFERENCES = "network_study_settings";
    private static final String DOWNLOAD_TREE_URI = "download_tree_uri";
    private static final String DOWNLOAD_TREE_NAME = "download_tree_name";
    private static final String QQ_PENDING_RESULT = "qq_pending_result";
    private static final String QQ_LOGIN_PURPOSE = "qq_login_purpose";
    private static final String QQ_LOGIN_IN_FLIGHT = "qq_login_in_flight";
    private static final String QQ_LOGIN_STATUS = "qq_login_status";

    private WebView webView;
    private CookieManager cookieManager;
    private SharedPreferences preferences;
    private ActivityResultLauncher<Intent> directoryPicker;
    private View nativeRoot;
    private View nativeTopBar;
    private View nativeBottomNavigation;
    private TextView nativeTitle;
    private TextView nativeEyebrow;
    private boolean nativeShellVisible;
    private boolean keyboardVisible;
    private boolean nativeConversationDetail;
    private String nativeShellPath = "";
    private Tencent qqTencent;
    private String qqLoginPurpose = "login";
    private String pendingQqResult = "";
    private boolean qqLoginInFlight;
    private final NetworkStudyBridge networkStudyBridge = new NetworkStudyBridge();

    private final IUiListener qqLoginListener = new IUiListener() {
        @Override
        public void onComplete(Object value) {
            try {
                if (!(value instanceof JSONObject)) {
                    throw new IllegalStateException("QQ response is not JSON");
                }
                JSONObject response = (JSONObject) value;
                String accessToken = response.optString("access_token");
                String openId = response.optString("openid");
                String expiresIn = response.optString("expires_in");
                if (accessToken.isEmpty() || openId.isEmpty()) {
                    throw new IllegalStateException("QQ credentials missing");
                }
                if (qqTencent != null) {
                    qqTencent.setAccessToken(accessToken, expiresIn);
                    qqTencent.setOpenId(openId);
                }
                JSONObject detail = new JSONObject();
                detail.put("accessToken", accessToken);
                detail.put("openId", openId);
                detail.put("expiresIn", expiresIn);
                detail.put("purpose", qqLoginPurpose);
                storePendingQqResult("success", detail);
                finishQqLogin("callback-complete");
                dispatchPendingQqResult();
            } catch (Exception error) {
                dispatchQqError("QQ 登录结果无效，请重试");
            }
        }

        @Override
        public void onError(UiError error) {
            String message = error != null && error.errorMessage != null
                ? error.errorMessage
                : "QQ 登录失败，请重试";
            dispatchQqError(message);
        }

        @Override
        public void onCancel() {
            dispatchQqError("已取消 QQ 登录");
        }

        @Override
        public void onWarning(int code) {
            if (code != 0) {
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "QQ 登录提示：" + code, Toast.LENGTH_SHORT).show()
                );
            }
        }
    };

    @Override
    protected void load() {
        WebView initialWebView = findViewById(R.id.webview);
        if (initialWebView != null) {
            webView = initialWebView;
            WebSettings settings = initialWebView.getSettings();
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            String userAgent = settings.getUserAgentString();
            if (userAgent == null || !userAgent.contains("NetworkStudyAndroid/")) {
                settings.setUserAgentString(
                    (userAgent == null ? "" : userAgent + " ") + "NetworkStudyAndroid/1.6"
                );
            }
            initialWebView.addJavascriptInterface(networkStudyBridge, "NetworkStudyApp");
        }
        super.load();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupSystemBars();
        setupNativeShell();
        preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        pendingQqResult = preferences.getString(QQ_PENDING_RESULT, "");
        qqLoginPurpose = preferences.getString(QQ_LOGIN_PURPOSE, "login");
        qqLoginInFlight = preferences.getBoolean(QQ_LOGIN_IN_FLIGHT, false);
        initializeQqSdk();
        directoryPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Uri uri = result.getData().getData();
                if (uri == null) {
                    return;
                }
                int flags = result.getData().getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(uri, flags);
                    preferences.edit()
                        .putString(DOWNLOAD_TREE_URI, uri.toString())
                        .putString(DOWNLOAD_TREE_NAME, readableTreeName(uri))
                        .apply();
                    Toast.makeText(this, "下载位置已更新", Toast.LENGTH_SHORT).show();
                    notifyDownloadDirectoryChanged();
                } catch (Exception error) {
                    Toast.makeText(this, "无法使用该文件夹，请选择其他位置", Toast.LENGTH_SHORT).show();
                }
            }
        );

        webView = getBridge().getWebView();

        cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String treeUri = preferences.getString(DOWNLOAD_TREE_URI, "");
            if (!treeUri.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                downloadToSelectedDirectory(url, userAgent, contentDisposition, mimeType, treeUri);
            } else {
                enqueueSystemDownload(url, userAgent, contentDisposition, mimeType);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
            getWindow(),
            getWindow().getDecorView()
        );
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
    }

    private void setupNativeShell() {
        nativeRoot = findViewById(R.id.native_root);
        nativeTopBar = findViewById(R.id.native_top_bar);
        nativeBottomNavigation = findViewById(R.id.native_bottom_navigation);
        nativeTitle = findViewById(R.id.native_title);
        nativeEyebrow = findViewById(R.id.native_eyebrow);

        findViewById(R.id.native_profile_button).setOnClickListener(
            view -> dispatchNativeNavigation("/profile")
        );
        findViewById(R.id.nav_chat).setOnClickListener(view -> dispatchNativeNavigation("/chat"));
        findViewById(R.id.nav_library).setOnClickListener(view -> dispatchNativeNavigation("/library"));
        findViewById(R.id.nav_sources).setOnClickListener(view -> dispatchNativeNavigation("/sources"));
        findViewById(R.id.nav_graph).setOnClickListener(view -> dispatchNativeNavigation("/knowledge-graph"));
        findViewById(R.id.nav_profile).setOnClickListener(view -> dispatchNativeNavigation("/profile"));

        ViewCompat.setOnApplyWindowInsetsListener(nativeRoot, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            applyNativeShellVisibility();
            return insets;
        });
        ViewCompat.requestApplyInsets(nativeRoot);
        setNativeShellVisible(false);
    }

    private void setNativeShellVisible(boolean visible) {
        nativeShellVisible = visible;
        applyNativeShellVisibility();
        ViewCompat.requestApplyInsets(nativeRoot);
    }

    private void applyNativeShellVisibility() {
        boolean chatRoute = "/chat".equals(nativeShellPath);
        nativeTopBar.setVisibility(
            nativeShellVisible && !chatRoute ? View.VISIBLE : View.GONE
        );
        nativeBottomNavigation.setVisibility(
            nativeShellVisible
                && !keyboardVisible
                && !(chatRoute && nativeConversationDetail)
                ? View.VISIBLE
                : View.GONE
        );
    }

    private void updateNativeShell(String path, String title, String eyebrow) {
        nativeShellPath = path;
        if (!"/chat".equals(path)) {
            nativeConversationDetail = false;
        }
        nativeTitle.setText(title);
        nativeEyebrow.setText(eyebrow);
        findViewById(R.id.nav_chat).setSelected("/chat".equals(path));
        findViewById(R.id.nav_library).setSelected("/library".equals(path));
        findViewById(R.id.nav_sources).setSelected("/sources".equals(path));
        findViewById(R.id.nav_graph).setSelected("/knowledge-graph".equals(path));
        findViewById(R.id.nav_profile).setSelected("/profile".equals(path));
        setNativeShellVisible(true);
    }

    private void dispatchNativeNavigation(String path) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("href", path);
            dispatchWebEvent("network-study-native-nav", detail);
        } catch (Exception ignored) {
        }
    }

    private void dispatchQqError(String message) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("message", message);
            detail.put("purpose", qqLoginPurpose);
            storePendingQqResult("error", detail);
            finishQqLogin("error");
            dispatchPendingQqResult();
        } catch (Exception ignored) {
        }
    }

    private synchronized void storePendingQqResult(String status, JSONObject detail) {
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("status", status);
            envelope.put("detail", detail);
            pendingQqResult = envelope.toString();
            if (preferences != null) {
                preferences.edit().putString(QQ_PENDING_RESULT, pendingQqResult).apply();
            }
        } catch (Exception ignored) {
            pendingQqResult = "";
        }
    }

    private synchronized String consumePendingQqResultValue() {
        String result = pendingQqResult;
        pendingQqResult = "";
        if (preferences != null) {
            preferences.edit().remove(QQ_PENDING_RESULT).apply();
        }
        return result;
    }

    private synchronized String peekPendingQqResultValue() {
        return pendingQqResult;
    }

    private void dispatchPendingQqResult() {
        String value = peekPendingQqResultValue();
        if (value.isEmpty()) {
            return;
        }
        try {
            JSONObject envelope = new JSONObject(value);
            String status = envelope.optString("status");
            JSONObject detail = envelope.optJSONObject("detail");
            if (detail == null) {
                return;
            }
            dispatchWebEvent(
                "success".equals(status)
                    ? "network-study-qq-login"
                    : "network-study-qq-login-error",
                detail
            );
        } catch (Exception ignored) {
        }
    }

    private void dispatchWebEvent(String eventName, JSONObject detail) {
        if (webView == null) {
            return;
        }
        String script = "window.dispatchEvent(new CustomEvent("
            + JSONObject.quote(eventName)
            + ",{detail:"
            + detail.toString()
            + "}))";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private void startQqLogin(String purpose) {
        qqLoginPurpose = "bind".equals(purpose) ? "bind" : "login";
        synchronized (this) {
            pendingQqResult = "";
        }
        preferences.edit()
            .remove(QQ_PENDING_RESULT)
            .putString(QQ_LOGIN_PURPOSE, qqLoginPurpose)
            .putBoolean(QQ_LOGIN_IN_FLIGHT, true)
            .putString(QQ_LOGIN_STATUS, "starting")
            .apply();
        qqLoginInFlight = true;
        Tencent.setIsPermissionGranted(true, Build.MODEL);
        Tencent.resetTargetAppInfoCache();
        initializeQqSdk();
        if (qqTencent == null) {
            dispatchQqError("QQ SDK 初始化失败");
            return;
        }
        if ("bind".equals(qqLoginPurpose) && qqTencent.isSessionValid()) {
            qqTencent.logout(this);
        } else if (qqTencent.isSessionValid() && recoverQqSession("existing-session")) {
            return;
        }
        Toast.makeText(this, "正在打开 QQ 授权…", Toast.LENGTH_SHORT).show();
        updateQqStatus("launching-qq");
        int result = qqTencent.login(this, "get_user_info", qqLoginListener);
        if (result < 0) {
            dispatchQqError("QQ 登录启动失败，错误码：" + result);
        }
    }

    private void initializeQqSdk() {
        if (qqTencent != null) {
            return;
        }
        try {
            qqTencent = Tencent.createInstance(
                MOBILE_QQ_APP_ID,
                getApplicationContext(),
                getPackageName() + ".fileprovider"
            );
        } catch (Exception error) {
            updateQqStatus("sdk-init-failed");
        }
    }

    private void updateQqStatus(String status) {
        if (preferences != null) {
            preferences.edit().putString(QQ_LOGIN_STATUS, status).apply();
        }
    }

    private void finishQqLogin(String status) {
        qqLoginInFlight = false;
        if (preferences != null) {
            preferences.edit()
                .putBoolean(QQ_LOGIN_IN_FLIGHT, false)
                .putString(QQ_LOGIN_STATUS, status)
                .apply();
        }
    }

    private boolean recoverQqSession(String stage) {
        initializeQqSdk();
        if (!qqLoginInFlight || qqTencent == null || !qqTencent.isSessionValid()) {
            updateQqStatus(stage + "-waiting");
            return false;
        }
        String accessToken = qqTencent.getAccessToken();
        String openId = qqTencent.getOpenId();
        if (accessToken == null || accessToken.isEmpty() || openId == null || openId.isEmpty()) {
            updateQqStatus(stage + "-credentials-missing");
            return false;
        }
        try {
            JSONObject detail = new JSONObject();
            detail.put("accessToken", accessToken);
            detail.put("openId", openId);
            detail.put("expiresIn", String.valueOf(qqTencent.getExpiresIn()));
            detail.put("purpose", qqLoginPurpose);
            storePendingQqResult("success", detail);
            finishQqLogin(stage + "-recovered");
            dispatchPendingQqResult();
            return true;
        } catch (Exception error) {
            updateQqStatus(stage + "-recovery-failed");
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updateQqStatus("activity-result-" + requestCode + "-" + resultCode);
        Tencent.onActivityResultData(requestCode, resultCode, data, qqLoginListener);
        if (webView != null) {
            webView.postDelayed(() -> recoverQqSession("activity-result"), 350);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            updateQqStatus("new-intent");
            Tencent.handleResultData(intent, qqLoginListener);
            if (webView != null) {
                webView.postDelayed(() -> recoverQqSession("new-intent"), 350);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) {
            webView.postDelayed(() -> {
                recoverQqSession("resume-450");
                dispatchPendingQqResult();
            }, 450);
            webView.postDelayed(() -> {
                recoverQqSession("resume-1400");
                dispatchPendingQqResult();
            }, 1400);
            webView.postDelayed(() -> {
                recoverQqSession("resume-3000");
                dispatchPendingQqResult();
            }, 3000);
        }
    }

    private void enqueueSystemDownload(
        String url,
        String userAgent,
        String contentDisposition,
        String mimeType
    ) {
        try {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookies = cookieManager.getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            request.addRequestHeader("User-Agent", userAgent);
            request.setMimeType(mimeType);
            request.setTitle(filename);
            request.setDescription("正在下载学习资料");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(this, "已加入下载任务：" + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "下载启动失败，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadToSelectedDirectory(
        String url,
        String userAgent,
        String contentDisposition,
        String mimeType,
        String treeUriValue
    ) {
        Toast.makeText(this, "正在下载到所选文件夹", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("User-Agent", userAgent);
                String cookies = cookieManager.getCookie(url);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                }
                connection.connect();
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }

                String responseDisposition = connection.getHeaderField("Content-Disposition");
                String responseType = connection.getContentType();
                String filename = URLUtil.guessFileName(
                    connection.getURL().toString(),
                    responseDisposition != null ? responseDisposition : contentDisposition,
                    responseType != null ? responseType : mimeType
                );
                String finalMimeType = responseType != null ? responseType : mimeType;
                if (finalMimeType == null || finalMimeType.isEmpty()) {
                    finalMimeType = "application/octet-stream";
                }
                int mimeSeparator = finalMimeType.indexOf(';');
                if (mimeSeparator > 0) {
                    finalMimeType = finalMimeType.substring(0, mimeSeparator);
                }

                Uri treeUri = Uri.parse(treeUriValue);
                String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId);
                Uri destination = DocumentsContract.createDocument(
                    getContentResolver(),
                    parentUri,
                    finalMimeType,
                    filename
                );
                if (destination == null) {
                    throw new IllegalStateException("Unable to create destination");
                }

                try (
                    InputStream input = connection.getInputStream();
                    OutputStream output = getContentResolver().openOutputStream(destination, "w")
                ) {
                    if (output == null) {
                        throw new IllegalStateException("Unable to open destination");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
                runOnUiThread(() ->
                    Toast.makeText(this, "文件已保存：" + filename, Toast.LENGTH_LONG).show()
                );
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "自定义目录下载失败，已改用系统下载目录", Toast.LENGTH_LONG).show();
                    enqueueSystemDownload(url, userAgent, contentDisposition, mimeType);
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private String readableTreeName(Uri uri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            String decoded = URLDecoder.decode(documentId, StandardCharsets.UTF_8.name());
            int separator = decoded.lastIndexOf(':');
            String value = separator >= 0 ? decoded.substring(separator + 1) : decoded;
            return value.isEmpty() ? "所选文件夹" : value;
        } catch (Exception ignored) {
            return "所选文件夹";
        }
    }

    private void notifyDownloadDirectoryChanged() {
        if (webView == null) {
            return;
        }
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('network-study-download-directory-changed'))",
            null
        ));
    }

    private void saveImageBytes(byte[] bytes, String filename) {
        new Thread(() -> {
            try {
                String treeUriValue = preferences.getString(DOWNLOAD_TREE_URI, "");
                if (!treeUriValue.isEmpty()) {
                    Uri treeUri = Uri.parse(treeUriValue);
                    String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                    Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId);
                    Uri destination = DocumentsContract.createDocument(
                        getContentResolver(),
                        parentUri,
                        "image/png",
                        filename
                    );
                    if (destination == null) {
                        throw new IllegalStateException("Unable to create image");
                    }
                    try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) {
                            throw new IllegalStateException("Unable to open image destination");
                        }
                        output.write(bytes);
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri destination = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    );
                    if (destination == null) {
                        throw new IllegalStateException("Unable to create download image");
                    }
                    try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) {
                            throw new IllegalStateException("Unable to open download image");
                        }
                        output.write(bytes);
                    }
                } else {
                    File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IllegalStateException("Unable to create downloads directory");
                    }
                    try (OutputStream output = new FileOutputStream(new File(directory, filename))) {
                        output.write(bytes);
                    }
                }
                runOnUiThread(() ->
                    Toast.makeText(this, "分享图已保存：" + filename, Toast.LENGTH_LONG).show()
                );
            } catch (Exception error) {
                runOnUiThread(() ->
                    Toast.makeText(this, "分享图保存失败，请检查下载位置", Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private class NetworkStudyBridge {
        @JavascriptInterface
        public String getBridgeVersion() {
            return "1.6";
        }

        @JavascriptInterface
        public String getDownloadSettings() {
            try {
                String uri = preferences.getString(DOWNLOAD_TREE_URI, "");
                JSONObject value = new JSONObject();
                value.put("customDirectory", !uri.isEmpty());
                value.put(
                    "directoryName",
                    preferences.getString(DOWNLOAD_TREE_NAME, "系统 Download 文件夹")
                );
                String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
                value.put("appVersion", versionName != null ? versionName : "1.1");
                return value.toString();
            } catch (Exception error) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void chooseDownloadDirectory() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                );
                directoryPicker.launch(intent);
            });
        }

        @JavascriptInterface
        public void resetDownloadDirectory() {
            preferences.edit()
                .remove(DOWNLOAD_TREE_URI)
                .remove(DOWNLOAD_TREE_NAME)
                .apply();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "已恢复系统 Download 文件夹", Toast.LENGTH_SHORT).show();
                notifyDownloadDirectoryChanged();
            });
        }

        @JavascriptInterface
        public void saveBase64Image(String dataUrl, String filename) {
            try {
                int comma = dataUrl.indexOf(',');
                String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                saveImageBytes(bytes, filename);
            } catch (Exception error) {
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "分享图数据无效", Toast.LENGTH_SHORT).show()
                );
            }
        }

        @JavascriptInterface
        public void loginWithQQ() {
            runOnUiThread(() -> startQqLogin("login"));
        }

        @JavascriptInterface
        public void bindQQ() {
            runOnUiThread(() -> startQqLogin("bind"));
        }

        @JavascriptInterface
        public String consumePendingQqResult() {
            return consumePendingQqResultValue();
        }

        @JavascriptInterface
        public String getQqLoginStatus() {
            if (preferences == null) {
                return "bridge-not-ready";
            }
            return preferences.getString(QQ_LOGIN_STATUS, "idle");
        }

        @JavascriptInterface
        public void setShellState(String path, String title, String eyebrow) {
            runOnUiThread(() -> updateNativeShell(path, title, eyebrow));
        }

        @JavascriptInterface
        public void hideShell() {
            runOnUiThread(() -> setNativeShellVisible(false));
        }

        @JavascriptInterface
        public void setConversationMode(boolean detail) {
            runOnUiThread(() -> {
                nativeConversationDetail = detail;
                applyNativeShellVisibility();
            });
        }
    }
}
