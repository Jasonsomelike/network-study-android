package cn.bestijason.networkstudy;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
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
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;
import com.tencent.connect.UnionInfo;
import com.tencent.connect.common.Constants;
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
    private static final String BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested";
    private static final long BACK_EXIT_CONFIRM_MS = 2000L;

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
    private boolean nativeImagePreviewOpen;
    private long lastBackPressedAt;
    private String nativeShellPath = "";
    private Tencent qqTencent;
    private String qqLoginPurpose = "login";
    private String pendingQqResult = "";
    private boolean qqLoginInFlight;
    private boolean qqCheckInFlight;
    private boolean chatGenerationActive;
    private String activeChatGenerationConversationId = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final NetworkStudyBridge networkStudyBridge = new NetworkStudyBridge();

    private String qqNumberFromJson(JSONObject value) {
        if (value == null) {
            return "";
        }
        String[] keys = new String[] {
            "qqNumber",
            "qq_number",
            "qq",
            "uin",
            "qqNo",
            "qq_no",
            "account"
        };
        for (String key : keys) {
            String candidate = value.optString(key);
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String digits = candidate.replaceAll("\\D+", "");
            if (digits.length() >= 5 && digits.length() <= 12) {
                return digits;
            }
        }
        return "";
    }

    private void putQqNumberIfPresent(JSONObject detail, JSONObject source) {
        try {
            String qqNumber = qqNumberFromJson(source);
            if (!qqNumber.isEmpty()) {
                detail.put("qqNumber", qqNumber);
            }
        } catch (Exception ignored) {
        }
    }

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
                    qqTencent.saveSession(response);
                    qqTencent.setAccessToken(accessToken, expiresIn);
                    qqTencent.setOpenId(openId);
                }
                JSONObject detail = new JSONObject();
                detail.put("accessToken", accessToken);
                detail.put("openId", openId);
                detail.put("expiresIn", expiresIn);
                detail.put("purpose", qqLoginPurpose);
                putQqNumberIfPresent(detail, response);
                completeQqSuccess(detail, "callback-complete", true, true);
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
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            String userAgent = settings.getUserAgentString();
            String normalizedUserAgent = userAgent == null ? "" : userAgent.replaceAll("\\s*NetworkStudyAndroid/[\\w.\\-]+", "");
            settings.setUserAgentString(
                (normalizedUserAgent.isEmpty() ? "" : normalizedUserAgent + " ") + "NetworkStudyAndroid/1.14.0"
            );
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
                handleNativeBackPressed();
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

    private boolean isPrimaryShellRoute() {
        return nativeShellVisible && (
            nativeShellPath == null
                || nativeShellPath.isEmpty()
                || "/chat".equals(nativeShellPath)
                || "/library".equals(nativeShellPath)
                || "/sources".equals(nativeShellPath)
                || "/knowledge-graph".equals(nativeShellPath)
                || "/profile".equals(nativeShellPath)
        );
    }

    private void dispatchNativeBackAction(String action) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("action", action);
            detail.put("path", nativeShellPath);
            detail.put("at", System.currentTimeMillis());
            dispatchWebEvent("network-study-native-back", detail);
        } catch (Exception ignored) {
        }
    }

    private void confirmOrExitApp() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt <= BACK_EXIT_CONFIRM_MS) {
            lastBackPressedAt = 0L;
            stopChatForegroundService();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask();
                } else {
                    finishAffinity();
                }
            } catch (Exception ignored) {
                finishAffinity();
            }
            mainHandler.postDelayed(() -> {
                try {
                    System.exit(0);
                } catch (Exception ignored) {
                }
            }, 250);
            return;
        }
        lastBackPressedAt = now;
        Toast.makeText(this, "再按一次退出 App", Toast.LENGTH_SHORT).show();
    }

    private void handleNativeBackPressed() {
        if (nativeImagePreviewOpen) {
            nativeImagePreviewOpen = false;
            dispatchNativeBackAction("image-preview-close");
            return;
        }

        if (nativeConversationDetail) {
            nativeConversationDetail = false;
            applyNativeShellVisibility();
            dispatchNativeBackAction("chat-list");
            return;
        }

        if (isPrimaryShellRoute()) {
            confirmOrExitApp();
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        confirmOrExitApp();
    }

    private void dispatchAppLifecycleState(String state) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("state", state);
            detail.put("path", nativeShellPath);
            detail.put("conversationDetail", nativeConversationDetail);
            detail.put("at", System.currentTimeMillis());
            dispatchWebEvent("network-study-app-lifecycle", detail);
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

    private synchronized void completeQqSuccessNow(JSONObject detail, String status, boolean shouldDispatch) {
        if (!qqLoginInFlight && pendingQqResult != null && !pendingQqResult.isEmpty()) {
            return;
        }
        storePendingQqResult("success", detail);
        finishQqLogin(status);
        if (shouldDispatch) {
            dispatchPendingQqResult();
        }
    }

    private void completeQqSuccess(JSONObject detail, String status, boolean shouldDispatch, boolean waitForUnion) {
        if (!waitForUnion || qqTencent == null) {
            completeQqSuccessNow(detail, status, shouldDispatch);
            return;
        }
        updateQqStatus(status + "-union");
        final boolean[] completed = { false };
        Runnable finishWithoutUnion = () -> {
            synchronized (completed) {
                if (completed[0]) {
                    return;
                }
                completed[0] = true;
            }
            completeQqSuccessNow(detail, status, shouldDispatch);
        };
        try {
            UnionInfo unionInfo = new UnionInfo(this, qqTencent.getQQToken());
            unionInfo.getUnionId(new IUiListener() {
                @Override
                public void onComplete(Object value) {
                    synchronized (completed) {
                        if (completed[0]) {
                            return;
                        }
                        completed[0] = true;
                    }
                    try {
                        if (value instanceof JSONObject) {
                            String unionId = ((JSONObject) value).optString("unionid");
                            if (!unionId.isEmpty()) {
                                detail.put("unionId", unionId);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    completeQqSuccessNow(detail, status, shouldDispatch);
                }

                @Override
                public void onError(UiError error) {
                    finishWithoutUnion.run();
                }

                @Override
                public void onCancel() {
                    finishWithoutUnion.run();
                }

                @Override
                public void onWarning(int code) {
                    finishWithoutUnion.run();
                }
            });
            if (webView != null) {
                webView.postDelayed(finishWithoutUnion, 2500);
            }
        } catch (Exception ignored) {
            finishWithoutUnion.run();
        }
    }

    private synchronized String consumePendingQqResultValue() {
        if ((pendingQqResult == null || pendingQqResult.isEmpty()) && qqLoginInFlight) {
            recoverQqSession("consume", false);
        }
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

    private void dispatchChatEnteredEvent() {
        mainHandler.postDelayed(() -> {
            try {
                JSONObject detail = new JSONObject();
                detail.put("path", nativeShellPath);
                detail.put("conversationDetail", nativeConversationDetail);
                detail.put("at", System.currentTimeMillis());
                dispatchWebEvent("network-study-chat-entered", detail);
            } catch (Exception ignored) {
            }
        }, 80);
    }

    private void setChatGenerationForeground(boolean active, String conversationId) {
        chatGenerationActive = active;
        activeChatGenerationConversationId = conversationId == null ? "" : conversationId;
        Intent intent = new Intent(this, ChatForegroundService.class);
        intent.putExtra(ChatForegroundService.EXTRA_CONVERSATION_ID, activeChatGenerationConversationId);
        if (active) {
            intent.setAction(ChatForegroundService.ACTION_START);
            try {
                ContextCompat.startForegroundService(this, intent);
                requestBatteryOptimizationExemptionIfNeeded();
            } catch (Exception error) {
                Toast.makeText(this, "后台生成保活启动失败，将在返回前台后自动恢复", Toast.LENGTH_SHORT).show();
            }
        } else {
            stopService(intent);
        }
    }

    private void requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || preferences == null) {
            return;
        }
        if (preferences.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            preferences.edit().putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true).apply();
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "允许后台运行可提升对话生成稳定性", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            preferences.edit().putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true).apply();
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(fallback);
            } catch (Exception ignored) {
                Toast.makeText(this, "可在系统设置中允许本应用后台运行", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopChatForegroundService() {
        chatGenerationActive = false;
        activeChatGenerationConversationId = "";
        Intent intent = new Intent(this, ChatForegroundService.class);
        intent.setAction(ChatForegroundService.ACTION_STOP);
        try {
            stopService(intent);
        } catch (Exception ignored) {
        }
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
        if (!qqTencent.isQQInstalled(this)) {
            dispatchQqError("未检测到 QQ 客户端，请安装或更新 QQ 后重试");
            return;
        }
        if (qqTencent.isSessionValid()) {
            qqTencent.logout(this);
        }
        Toast.makeText(this, "正在打开 QQ 授权…", Toast.LENGTH_SHORT).show();
        updateQqStatus("launching-qq");
        int result = qqTencent.login(this, "get_user_info", qqLoginListener);
        if (result < 0) {
            dispatchQqError("QQ 登录启动失败，错误码：" + result);
            return;
        }
        if (webView != null) {
            webView.postDelayed(() -> {
                if (!recoverQqSession("launch-2000")) {
                    requestQqCheckLogin("launch-2000");
                }
            }, 2000);
            webView.postDelayed(() -> {
                if (!recoverQqSession("launch-5000")) {
                    requestQqCheckLogin("launch-5000");
                }
            }, 5000);
            webView.postDelayed(() -> {
                if (!recoverQqSession("launch-10000")) {
                    requestQqCheckLogin("launch-10000");
                }
            }, 10000);
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
        return recoverQqSession(stage, true);
    }

    private boolean recoverQqSession(String stage, boolean shouldDispatch) {
        initializeQqSdk();
        if (preferences != null) {
            qqLoginPurpose = preferences.getString(QQ_LOGIN_PURPOSE, qqLoginPurpose);
            qqLoginInFlight = preferences.getBoolean(QQ_LOGIN_IN_FLIGHT, qqLoginInFlight);
        }
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
            completeQqSuccess(detail, stage + "-recovered", shouldDispatch, shouldDispatch);
            return true;
        } catch (Exception error) {
            updateQqStatus(stage + "-recovery-failed");
            return false;
        }
    }

    private void requestQqCheckLogin(String stage) {
        initializeQqSdk();
        if (!qqLoginInFlight || qqTencent == null || qqCheckInFlight) {
            return;
        }
        qqCheckInFlight = true;
        updateQqStatus(stage + "-check-login");
        try {
            qqTencent.checkLogin(new IUiListener() {
                @Override
                public void onComplete(Object value) {
                    qqCheckInFlight = false;
                    if (value instanceof JSONObject) {
                        JSONObject response = (JSONObject) value;
                        if (!response.optString("access_token").isEmpty()
                            && !response.optString("openid").isEmpty()) {
                            qqLoginListener.onComplete(value);
                            return;
                        }
                    }
                    if (!recoverQqSession(stage + "-check-complete")) {
                        updateQqStatus(stage + "-check-complete-waiting");
                    }
                }

                @Override
                public void onError(UiError error) {
                    qqCheckInFlight = false;
                    String code = error != null ? String.valueOf(error.errorCode) : "unknown";
                    updateQqStatus(stage + "-check-error-" + code);
                    dispatchPendingQqResult();
                }

                @Override
                public void onCancel() {
                    qqCheckInFlight = false;
                    updateQqStatus(stage + "-check-cancel");
                    dispatchPendingQqResult();
                }

                @Override
                public void onWarning(int code) {
                    if (code != 0) {
                        updateQqStatus(stage + "-check-warning-" + code);
                    }
                }
            });
        } catch (Exception error) {
            qqCheckInFlight = false;
            updateQqStatus(stage + "-check-failed");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        updateQqStatus("activity-result-" + requestCode + "-" + resultCode);
        boolean handled = Tencent.onActivityResultData(requestCode, resultCode, data, qqLoginListener);
        if (!handled && data != null && requestCode == Constants.REQUEST_LOGIN && qqTencent != null) {
            qqTencent.handleLoginData(data, qqLoginListener);
        }
        if (data != null) {
            Tencent.handleResultData(data, qqLoginListener);
        }
        super.onActivityResult(requestCode, resultCode, data);
        if (webView != null) {
            webView.postDelayed(() -> {
                if (!recoverQqSession("activity-result")) {
                    requestQqCheckLogin("activity-result");
                }
            }, 350);
            webView.postDelayed(() -> dispatchPendingQqResult(), 900);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            updateQqStatus("new-intent");
            Tencent.handleResultData(intent, qqLoginListener);
            if (qqTencent != null) {
                qqTencent.handleLoginData(intent, qqLoginListener);
            }
            if (webView != null) {
                webView.postDelayed(() -> {
                    if (!recoverQqSession("new-intent")) {
                        requestQqCheckLogin("new-intent");
                    }
                }, 350);
                webView.postDelayed(() -> dispatchPendingQqResult(), 900);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        dispatchAppLifecycleState("resume");
        if (webView != null) {
            webView.postDelayed(() -> {
                if (!recoverQqSession("resume-450")) {
                    requestQqCheckLogin("resume-450");
                }
            }, 450);
            webView.postDelayed(() -> {
                if (!recoverQqSession("resume-1400")) {
                    requestQqCheckLogin("resume-1400");
                }
            }, 1400);
            webView.postDelayed(() -> {
                if (!recoverQqSession("resume-3000")) {
                    requestQqCheckLogin("resume-3000");
                }
            }, 3000);
        }
    }

    @Override
    public void onPause() {
        dispatchAppLifecycleState("pause");
        super.onPause();
    }

    @Override
    public void onDestroy() {
        stopChatForegroundService();
        super.onDestroy();
    }

    private void enqueueSystemDownload(
        String url,
        String userAgent,
        String contentDisposition,
        String mimeType
    ) {
        try {
            String filename = resolveDownloadFilename(url, contentDisposition, mimeType);
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

    private String normalizeMimeType(String mimeType) {
        String value = mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType;
        int mimeSeparator = value.indexOf(';');
        if (mimeSeparator > 0) {
            value = value.substring(0, mimeSeparator);
        }
        return value.trim().isEmpty() ? "application/octet-stream" : value.trim();
    }

    private String sanitizeFilename(String value) {
        if (value == null) {
            return "";
        }
        String decoded = value;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
        }
        decoded = decoded.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        return decoded.length() > 180 ? decoded.substring(0, 180) : decoded;
    }

    private String filenameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) {
            return "";
        }
        String lower = contentDisposition.toLowerCase();
        int starIndex = lower.indexOf("filename*=");
        if (starIndex >= 0) {
            String value = contentDisposition.substring(starIndex + "filename*=".length()).split(";", 2)[0].trim();
            int quote = value.indexOf("''");
            if (quote >= 0) {
                value = value.substring(quote + 2);
            }
            value = value.replace("\"", "");
            return sanitizeFilename(value);
        }
        int index = lower.indexOf("filename=");
        if (index >= 0) {
            String value = contentDisposition.substring(index + "filename=".length()).split(";", 2)[0].trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                value = value.substring(1, value.length() - 1);
            }
            return sanitizeFilename(value);
        }
        return "";
    }

    private String resolveDownloadFilename(String url, String contentDisposition, String mimeType) {
        try {
            Uri uri = Uri.parse(url);
            String queryFilename = sanitizeFilename(uri.getQueryParameter("filename"));
            if (!queryFilename.isEmpty()) {
                return queryFilename;
            }
        } catch (Exception ignored) {
        }
        String dispositionFilename = filenameFromContentDisposition(contentDisposition);
        if (!dispositionFilename.isEmpty()) {
            return dispositionFilename;
        }
        String guessed = sanitizeFilename(URLUtil.guessFileName(url, contentDisposition, mimeType));
        return guessed.isEmpty() || guessed.matches("(?i)^file(?:\\.[a-z0-9]+)?$")
            ? "知行网络学堂文件"
            : guessed;
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
                String filename = resolveDownloadFilename(
                    connection.getURL().toString(),
                    responseDisposition != null ? responseDisposition : contentDisposition,
                    responseType != null ? responseType : mimeType
                );
                String finalMimeType = normalizeMimeType(responseType != null ? responseType : mimeType);

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

    private String currentWebViewUserAgent() {
        try {
            if (webView != null && webView.getSettings() != null) {
                String value = webView.getSettings().getUserAgentString();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
        }
        return "NetworkStudyAndroid/1.14.0";
    }

    private String contentDispositionForFilename(String filename) {
        String safeFilename = sanitizeFilename(filename);
        if (safeFilename.isEmpty()) {
            return "";
        }
        return "attachment; filename=\"" + safeFilename.replace("\"", "") + "\"";
    }

    private void downloadUrlFromBridge(String url, String filename) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "下载链接无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String finalUrl = url.trim();
        String mimeType = finalUrl.toLowerCase().contains(".apk")
            ? "application/vnd.android.package-archive"
            : "application/octet-stream";
        String disposition = contentDispositionForFilename(filename);
        String treeUri = preferences.getString(DOWNLOAD_TREE_URI, "");
        if (!treeUri.isEmpty() && (finalUrl.startsWith("http://") || finalUrl.startsWith("https://"))) {
            downloadToSelectedDirectory(finalUrl, currentWebViewUserAgent(), disposition, mimeType, treeUri);
        } else {
            enqueueSystemDownload(finalUrl, currentWebViewUserAgent(), disposition, mimeType);
        }
    }

    private void openExternalUrlFromBridge(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "链接无效", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "无法打开外部链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveFileBytes(byte[] bytes, String filename, String mimeType, String successPrefix, String errorMessage) {
        new Thread(() -> {
            try {
                String finalMimeType = normalizeMimeType(mimeType);
                String safeFilename = sanitizeFilename(filename);
                if (safeFilename.isEmpty()) {
                    safeFilename = "知行网络学堂文件";
                }
                String treeUriValue = preferences.getString(DOWNLOAD_TREE_URI, "");
                if (!treeUriValue.isEmpty()) {
                    Uri treeUri = Uri.parse(treeUriValue);
                    String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                    Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId);
                    Uri destination = DocumentsContract.createDocument(
                        getContentResolver(),
                        parentUri,
                        finalMimeType,
                        safeFilename
                    );
                    if (destination == null) {
                        throw new IllegalStateException("Unable to create file");
                    }
                    try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) {
                            throw new IllegalStateException("Unable to open file destination");
                        }
                        output.write(bytes);
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeFilename);
                    values.put(MediaStore.Downloads.MIME_TYPE, finalMimeType);
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
                    try (OutputStream output = new FileOutputStream(new File(directory, safeFilename))) {
                        output.write(bytes);
                    }
                }
                String savedName = safeFilename;
                runOnUiThread(() ->
                    Toast.makeText(this, successPrefix + "已保存：" + savedName, Toast.LENGTH_LONG).show()
                );
            } catch (Exception error) {
                runOnUiThread(() ->
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void saveImageBytes(byte[] bytes, String filename) {
        saveFileBytes(bytes, filename, "image/png", "分享图", "分享图保存失败，请检查下载位置");
    }

    private class NetworkStudyBridge {
        @JavascriptInterface
        public String getBridgeVersion() {
            return "1.14.0";
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
                PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
                String versionName = packageInfo.versionName;
                value.put("appVersion", versionName != null ? versionName : "1.1");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    value.put("appVersionCode", packageInfo.getLongVersionCode());
                } else {
                    value.put("appVersionCode", packageInfo.versionCode);
                }
                return value.toString();
            } catch (Exception error) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            runOnUiThread(() -> openExternalUrlFromBridge(url));
        }

        @JavascriptInterface
        public void downloadUrl(String url, String filename) {
            runOnUiThread(() -> downloadUrlFromBridge(url, filename));
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
        public void saveBase64File(String dataUrl, String filename, String mimeType) {
            try {
                int comma = dataUrl.indexOf(',');
                String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                saveFileBytes(
                    bytes,
                    filename,
                    mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType,
                    "文件",
                    "文件保存失败，请检查下载位置"
                );
            } catch (Exception error) {
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "文件数据无效", Toast.LENGTH_SHORT).show()
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
            if (qqLoginInFlight && !recoverQqSession("status-query", false)) {
                runOnUiThread(() -> requestQqCheckLogin("status-query"));
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
                if (detail) {
                    dispatchChatEnteredEvent();
                }
            });
        }

        @JavascriptInterface
        public void setChatGenerationActive(boolean active, String conversationId) {
            runOnUiThread(() -> setChatGenerationForeground(active, conversationId));
        }

        @JavascriptInterface
        public void setImagePreviewOpen(boolean open) {
            runOnUiThread(() -> nativeImagePreviewOpen = open);
        }
    }
}
