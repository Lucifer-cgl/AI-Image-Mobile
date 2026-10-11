package com.aimangastudio.mobileimage;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.aimangastudio.planner.PlannerEngine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private ValueCallback<Uri[]> uploadCallback;
    private ModelPackManager modelPacks;
    private volatile File modelFile;
    private volatile File plannerFile;
    private volatile File decoderFile;
    private volatile String installStatus = "正在检查模型包…";
    private volatile int installProgress = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startService(new Intent(this, InferenceCleanupService.class));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        webView = new WebView(this);
        modelPacks = new ModelPackManager(this);
        setContentView(webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.addJavascriptInterface(new GeneratorBridge(), "AndroidGenerator");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                runDebugGenerationIfRequested();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                uploadCallback = callback;
                startActivityForResult(params.createIntent(), 42);
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/web/index.html");
        executor.execute(this::refreshModelState);
    }

    private void runDebugGenerationIfRequested() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String encoded = getIntent().getStringExtra("debug_prompt_b64");
        if (encoded == null || encoded.isEmpty()) return;
        try {
            String prompt = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            boolean generate = getIntent().getBooleanExtra("debug_generate", false);
            String script = "document.getElementById('prompt').value=" + JSONObject.quote(prompt) + ";"
                + (generate ? "document.getElementById('form').requestSubmit();" : "");
            webView.evaluateJavascript(script, null);
            getIntent().removeExtra("debug_prompt_b64");
            getIntent().removeExtra("debug_generate");
        } catch (Throwable error) {
            installStatus = "调试提示词注入失败：" + error.getMessage();
        }
    }

    private void refreshModelState() {
        try {
            modelFile = modelPacks.installedFileForRole("image");
            plannerFile = modelPacks.installedFileForRole("planner");
            decoderFile = modelPacks.installedFileForRole("decoder");
            if (modelFile == null || decoderFile == null) {
                installProgress = 0;
                installStatus = "请到“模型管理”安装生图模型和快速解码器";
            } else {
                installProgress = 100;
                installStatus = "LCM 生图模型与 TAESD 已就绪 · 完全离线";
            }
        } catch (Throwable error) {
            installStatus = "模型检查失败：" + error.getMessage();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 42 && uploadCallback != null) {
            uploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            uploadCallback = null;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        // Swiping the task away destroys this activity. Interrupt native generation,
        // then release the in-memory model without blocking the main/UI thread.
        NativeGenerator.cancel();
        executor.execute(NativeGenerator::release);
        executor.shutdown();
        super.onDestroy();
    }

    private final class GeneratorBridge {
        @JavascriptInterface public String status() { return installStatus; }

        @JavascriptInterface public int installProgress() { return installProgress; }

        @JavascriptInterface public String modelCatalog() {
            try { return modelPacks.catalogWithState().toString(); }
            catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
        }

        @JavascriptInterface public void downloadModel(String packageId) {
            executor.execute(() -> {
                try {
                    modelPacks.download(packageId, (id, percent, message) -> {
                        installProgress = percent;
                        installStatus = message;
                        callback("window.onModelProgress", new JSONObject().put("id", id).put("percent", percent).put("message", message));
                    });
                    refreshModelState();
                    callback("window.onModelChanged", new JSONObject().put("ok", true).put("id", packageId));
                } catch (Throwable error) {
                    try { callback("window.onModelChanged", new JSONObject().put("ok", false).put("id", packageId).put("error", error.getMessage())); }
                    catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface public void cancelModelDownload() { modelPacks.cancel(); }

        @JavascriptInterface public boolean deleteModel(String packageId) {
            try {
                NativeGenerator.cancel();
                NativeGenerator.release();
                boolean deleted = modelPacks.delete(packageId);
                refreshModelState();
                return deleted;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void generate(String json) {
            executor.execute(() -> {
                try {
                    if (modelFile == null) throw new IllegalStateException(installStatus);
                    JSONObject request = new JSONObject(json);
                    String prompt = request.optString("prompt", "");
                    String negative = request.optString("negative_prompt", "");
                    if (containsChinese(prompt)) {
                        if (plannerFile == null) throw new IllegalStateException("中文画面规划器尚未安装；请到模型管理下载，或暂时输入英文提示词");
                        progress(3, "正在用本地语言模型理解中文画面");
                        NativeGenerator.release();
                        String system = readAsset("planner/system.txt");
                        String planned = PlannerEngine.plan(plannerFile.getAbsolutePath(), system, prompt, 192);
                        int begin = planned.indexOf('{'), end = planned.lastIndexOf('}');
                        if (begin < 0 || end <= begin) throw new IllegalStateException("中文画面规划器未返回有效JSON");
                        JSONObject scene = new JSONObject(planned.substring(begin, end + 1));
                        if (scene.has("error")) throw new IllegalStateException(scene.getString("error"));
                        prompt = scene.getString("prompt");
                        if (negative.isEmpty()) negative = scene.optString("negative_prompt", "");
                        progress(12, "中文画面规划完成，已释放语言模型");
                    }
                    int width = request.optInt("width", 512);
                    int height = request.optInt("height", 512);
                    int outputWidth = request.optInt("output_width", width);
                    int outputHeight = request.optInt("output_height", height);
                    int steps = Math.max(1, Math.min(30, request.optInt("steps", 20)));
                    float strength = (float) request.optDouble("strength", 0.45);
                    boolean storageMapped = request.optBoolean("storage_mapped", true);
                    long seed = request.optLong("seed", System.currentTimeMillis());
                    byte[] initRgb = null;
                    int initWidth = 0;
                    int initHeight = 0;
                    String init = request.optString("init_image", "");
                    if (!init.isEmpty()) {
                        byte[] raw = Base64.decode(init.substring(init.indexOf(',') + 1), Base64.DEFAULT);
                        Bitmap source = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                        if (source == null) throw new IllegalArgumentException("无法读取上传的原图，请换用 JPG 或 PNG");
                        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
                        initRgb = bitmapToRgb(scaled);
                        initWidth = width;
                        initHeight = height;
                        if (scaled != source) scaled.recycle();
                        source.recycle();
                    }
                    progress(3, "准备生成参数");
                    if (decoderFile == null) throw new IllegalStateException("TAESD快速解码器尚未安装");
                    byte[] rgb = NativeGenerator.generate(modelFile.getAbsolutePath(), decoderFile.getAbsolutePath(), prompt, negative,
                        width, height, steps, seed, initRgb, initWidth, initHeight, strength,
                        storageMapped, this::progress);
                    if (rgb == null) {
                        String nativeError = NativeGenerator.lastError();
                        throw new IllegalStateException(nativeError == null || nativeError.isEmpty()
                            ? "本地模型没有返回图片，可能是手机内存不足" : nativeError);
                    }
                    progress(93, "处理输出图片");
                    Bitmap bitmap = rgbToBitmap(rgb, width, height);
                    if (outputWidth != width || outputHeight != height) {
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, outputWidth, outputHeight, true);
                        bitmap.recycle();
                        bitmap = scaled;
                    }
                    progress(97, "保存到本地历史");
                    File saved = saveHistory(bitmap, prompt);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    String data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
                    JSONObject result = new JSONObject().put("ok", true).put("data_url", "data:image/png;base64," + data)
                        .put("file", saved.getName()).put("seed", seed);
                    progress(100, "生成完成");
                    callback("window.onGenerationResult", result);
                } catch (Throwable error) {
                    try {
                        String message = error.getMessage();
                        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
                        callback("window.onGenerationResult", new JSONObject().put("ok", false).put("error", message));
                    }
                    catch (Exception ignored) {}
                }
            });
        }

        private void progress(int percent, String stage) {
            try {
                callback("window.onGenerationProgress", new JSONObject()
                    .put("percent", Math.max(0, Math.min(100, percent)))
                    .put("stage", stage));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public String history() {
            try {
                File dir = new File(getFilesDir(), "history");
                File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
                if (files == null) return "[]";
                Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                JSONArray array = new JSONArray();
                for (int i = 0; i < Math.min(files.length, 50); i++) {
                    array.put(new JSONObject().put("name", files[i].getName()).put("url", Uri.fromFile(files[i]).toString()).put("time", files[i].lastModified()));
                }
                return array.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface public boolean deleteHistory(String name) {
            if (!name.matches("[0-9]+\\.png")) return false;
            return new File(new File(getFilesDir(), "history"), name).delete();
        }

        @JavascriptInterface public boolean saveToGallery(String name) {
            try {
                if (!name.matches("[0-9]+\\.png")) return false;
                File source = new File(new File(getFilesDir(), "history"), name);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "本地画境-" + name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/本地画境");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (InputStream in = new java.io.FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                }
                return true;
            } catch (Exception e) { return false; }
        }
    }

    private void callback(String function, JSONObject value) {
        runOnUiThread(() -> webView.evaluateJavascript(function + "(" + JSONObject.quote(value.toString()) + ")", null));
    }

    private File saveHistory(Bitmap bitmap, String prompt) throws Exception {
        File dir = new File(getFilesDir(), "history");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法建立历史目录");
        File file = new File(dir, System.currentTimeMillis() + ".png");
        try (OutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        return file;
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u3400' && c <= '\u9fff') return true;
        }
        return false;
    }

    private static byte[] bitmapToRgb(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        byte[] rgb = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) { rgb[i * 3] = (byte)(pixels[i] >> 16); rgb[i * 3 + 1] = (byte)(pixels[i] >> 8); rgb[i * 3 + 2] = (byte)pixels[i]; }
        return rgb;
    }

    private static Bitmap rgbToBitmap(byte[] rgb, int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | ((rgb[i * 3] & 255) << 16) | ((rgb[i * 3 + 1] & 255) << 8) | (rgb[i * 3 + 2] & 255);
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
