package dev.opencal.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hosts the bundled web UI and owns pairing, sync and on-device storage. */
public class MainActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 15 * 60_000L;
    private static final String KEY_FEED = "feed";
    private static final String KEY_EVENTS = "cached_events";

    private WebView web;
    private Secrets secrets;
    private PairingServer pairing;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private String feedUrl = "", pairingUrl = "", qr = "", lastError = "";
    private JSONArray events = new JSONArray();
    private boolean loaded = false, loading = false, demo = false, pairingMode = false;
    private long lastSynced = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (loaded && !feedUrl.isEmpty()) sync();
            ui.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        secrets = new Secrets(this);
        feedUrl = secrets.get(KEY_FEED);
        events = cachedEvents();
        pairing = new PairingServer(new PairingServer.Listener() {
            @Override public void onSubmitted(String url) { ui.post(() -> saveFeed(url)); }
            @Override public void onExpired(String url) { ui.post(() -> pairingExpired(url)); }
        });
        web = new WebView(this);
        web.setBackgroundColor(0xFFF8FAFD);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(false);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // The UI is bundled; never navigate away from it.
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new JsBridge(), "OpenCalNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, REFRESH_INTERVAL_MS);
        if (loaded && !feedUrl.isEmpty() && System.currentTimeMillis() - lastSynced > REFRESH_INTERVAL_MS) sync();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(tick);
        pairing.close();
        network.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // D-pad is left to the WebView for button focus.
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU: callUi("toggleSettings()"); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: callUi("handleBack()"); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: callUi("navigate(-1)"); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: callUi("navigate(1)"); return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    static boolean isValidFeedUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            String path = uri.getRawPath();
            return uri.getScheme().equalsIgnoreCase("https") &&
                host != null && host.equalsIgnoreCase("calendar.google.com") &&
                uri.getUserInfo() == null && uri.getPort() == -1 &&
                path != null && path.startsWith("/calendar/ical/") && path.endsWith(".ics");
        } catch (Exception e) { return false; }
    }

    /** Methods callable from app.js as window.OpenCalNative.*. Each hops to the UI thread. */
    private final class JsBridge {
        @JavascriptInterface public void ready() { ui.post(() -> {
            loaded = true;
            if (feedUrl.isEmpty()) beginPairing();
            else { notifyState(); sync(); }
        }); }
        @JavascriptInterface public void refresh() { ui.post(MainActivity.this::sync); }
        @JavascriptInterface public void pair() { ui.post(MainActivity.this::beginPairing); }
        @JavascriptInterface public void cancelPairing() { ui.post(MainActivity.this::cancelPairing); }
        @JavascriptInterface public void save(String url) { ui.post(() -> saveFeed(url)); }
        @JavascriptInterface public void disconnect() { ui.post(() -> {
            try { secrets.put(KEY_FEED, ""); secrets.put(KEY_EVENTS, ""); } catch (Exception ignored) { }
            feedUrl = ""; events = new JSONArray(); lastSynced = 0; lastError = ""; demo = false;
            beginPairing();
        }); }
        @JavascriptInterface public void preview() { ui.post(() -> {
            demo = true; pairingMode = false; pairing.close(); pairingUrl = ""; qr = "";
            events = sampleEvents(); lastError = "";
            notifyState();
        }); }
        @JavascriptInterface public void exitPreview() { ui.post(() -> {
            demo = false; events = new JSONArray(); beginPairing();
        }); }
    }

    private void beginPairing() {
        demo = false; pairingMode = true;
        pairingUrl = pairing.start();
        qr = pairingUrl.isEmpty() ? "" : qrPng(pairingUrl);
        lastError = pairingUrl.isEmpty()
            ? "No local Wi-Fi address found, or port 8765 is in use. Enter the iCal address with the remote instead."
            : "";
        notifyState();
    }

    /** Leaves the pairing screen and returns to the calendar that is already connected. */
    private void cancelPairing() {
        if (feedUrl.isEmpty()) return;
        pairing.close();
        pairingMode = false; demo = false; pairingUrl = ""; qr = ""; lastError = "";
        events = cachedEvents();
        notifyState();
        sync();
    }

    private void pairingExpired(String url) {
        if (!pairingMode || !url.equals(pairingUrl)) return;
        pairingUrl = ""; qr = "";
        lastError = "Pairing code expired. Select New pairing code to get a fresh one.";
        notifyState();
    }

    private void saveFeed(String url) {
        if (!isValidFeedUrl(url)) {
            lastError = "Enter your Google Calendar 'Secret address in iCal format' (an https://calendar.google.com/calendar/ical/… .ics URL).";
            notifyState();
            return;
        }
        try {
            secrets.put(KEY_FEED, url.trim());
            secrets.put(KEY_EVENTS, "");
            feedUrl = url.trim(); events = new JSONArray(); demo = false; pairingMode = false;
            pairing.close(); pairingUrl = ""; qr = ""; lastError = ""; lastSynced = 0;
            notifyState();
            sync();
        } catch (Exception e) {
            lastError = "Unable to store the calendar securely on this TV: " + e.getMessage();
            notifyState();
        }
    }

    private void sync() {
        if (feedUrl.isEmpty() || demo || !syncing.compareAndSet(false, true)) return;
        loading = true; lastError = ""; notifyState();
        final String requested = feedUrl;
        network.execute(() -> {
            JSONArray fetched = null;
            String failure = null;
            try {
                fetched = IcalReader.download(requested);
            } catch (Exception e) {
                failure = e.getMessage() == null ? "Unable to read calendar. Check Wi-Fi and your feed link." : e.getMessage();
            }
            final JSONArray result = fetched;
            final String error = failure;
            ui.post(() -> {
                // The feed may have been replaced or removed while this request was in flight.
                boolean stale = !requested.equals(feedUrl);
                if (!stale) {
                    if (result != null) {
                        events = result;
                        lastSynced = System.currentTimeMillis();
                        try { secrets.put(KEY_EVENTS, events.toString()); } catch (Exception ignored) { }
                        lastError = "";
                    } else {
                        lastError = error;
                    }
                }
                loading = false;
                syncing.set(false);
                notifyState();
                if (stale) sync();
            });
        });
    }

    /** Pushes the full UI state to app.js (window.OpenCalUI.receive). */
    private void notifyState() {
        if (!loaded || web == null) return;
        try {
            JSONObject state = new JSONObject();
            state.put("paired", !feedUrl.isEmpty() && !pairingMode);
            state.put("hasFeed", !feedUrl.isEmpty());
            state.put("demo", demo);
            state.put("pairingUrl", pairingUrl);
            state.put("qr", qr);
            state.put("events", events);
            state.put("loading", loading);
            state.put("error", lastError);
            state.put("lastSynced", lastSynced);
            state.put("timeZone", ZoneId.systemDefault().getId());
            callUi("receive(" + state + ")");
        } catch (Exception ignored) { }
    }

    private void callUi(String call) {
        if (web != null) web.evaluateJavascript("window.OpenCalUI && OpenCalUI." + call, null);
    }

    private JSONArray cachedEvents() {
        try {
            String cached = secrets.get(KEY_EVENTS);
            if (!cached.isEmpty()) return new JSONArray(cached);
        } catch (Exception ignored) { }
        return new JSONArray();
    }

    private static String qrPng(String url) {
        try {
            Map<EncodeHintType, Object> hint = new HashMap<>();
            hint.put(EncodeHintType.MARGIN, 1);
            BitMatrix bits = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 380, 380, hint);
            Bitmap bitmap = Bitmap.createBitmap(bits.getWidth(), bits.getHeight(), Bitmap.Config.ARGB_8888);
            for (int y = 0; y < bits.getHeight(); y++)
                for (int x = 0; x < bits.getWidth(); x++) bitmap.setPixel(x, y, bits.get(x, y) ? 0xFF15233A : 0xFFFFFFFF);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private static JSONArray sampleEvents() {
        JSONArray json = new JSONArray();
        LocalDate today = LocalDate.now();
        int[][] blocks = {{9, 0, 9, 30}, {11, 0, 12, 0}, {14, 0, 15, 30}, {17, 0, 17, 45}};
        String[] titles = {"Morning planning", "Design review", "Focus time", "Project check-in"};
        String[] locations = {"", "Conference call", "", ""};
        for (int i = 0; i < blocks.length; i++) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", "demo:" + i);
                item.put("title", titles[i]);
                item.put("start", today.atTime(blocks[i][0], blocks[i][1]).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                item.put("end", today.atTime(blocks[i][2], blocks[i][3]).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                item.put("allDay", false);
                item.put("location", locations[i]);
                json.put(item);
            } catch (Exception ignored) { }
        }
        return json;
    }
}
