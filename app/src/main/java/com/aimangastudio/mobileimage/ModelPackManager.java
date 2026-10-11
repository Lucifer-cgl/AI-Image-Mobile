package com.aimangastudio.mobileimage;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModelPackManager {
    interface ProgressSink {
        void update(String packageId, int percent, String message) throws Exception;
    }

    private final Context context;
    private final File root;
    private volatile boolean cancelled;

    ModelPackManager(Context context) {
        this.context = context.getApplicationContext();
        this.root = new File(context.getFilesDir(), "model-packs");
    }

    JSONObject catalogWithState() throws Exception {
        JSONObject catalog = readCatalog();
        JSONArray packages = catalog.getJSONArray("packages");
        for (int i = 0; i < packages.length(); i++) {
            JSONObject pack = packages.getJSONObject(i);
            JSONArray files = pack.optJSONArray("files");
            boolean installed = files != null && files.length() > 0;
            long installedBytes = 0;
            if (files != null) {
                for (int j = 0; j < files.length(); j++) {
                    JSONObject spec = files.getJSONObject(j);
                    File local = localFile(pack.getString("id"), spec.getString("name"));
                    long expected = spec.optLong("bytes", -1);
                    if (!local.isFile() || expected <= 0 || local.length() != expected) installed = false;
                    if (local.isFile()) installedBytes += local.length();
                }
            }
            pack.put("installed", installed).put("installed_bytes", installedBytes);
        }
        return catalog;
    }

    File installedFileForRole(String role) throws Exception {
        JSONArray packages = catalogWithState().getJSONArray("packages");
        for (int i = 0; i < packages.length(); i++) {
            JSONObject pack = packages.getJSONObject(i);
            if (!role.equals(pack.optString("role")) || !pack.optBoolean("installed")) continue;
            JSONArray files = pack.getJSONArray("files");
            if (files.length() > 0) return localFile(pack.getString("id"), files.getJSONObject(0).getString("name"));
        }
        return null;
    }

    void download(String packageId, ProgressSink sink) throws Exception {
        cancelled = false;
        JSONObject pack = findPackage(packageId);
        JSONArray files = pack.optJSONArray("files");
        if (files == null || files.length() == 0) throw new IllegalStateException("此模型包尚未锁定发布文件");
        for (int i = 0; i < files.length(); i++) {
            if (cancelled) throw new InterruptedException("下载已取消");
            JSONObject spec = files.getJSONObject(i);
            if (spec.optJSONArray("parts") != null) downloadMultipart(packageId, spec, sink);
            else downloadFile(packageId, spec, sink);
        }
    }

    void cancel() { cancelled = true; }

    boolean delete(String packageId) throws Exception {
        JSONObject pack = findPackage(packageId);
        JSONArray files = pack.optJSONArray("files");
        boolean ok = true;
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                File file = localFile(packageId, files.getJSONObject(i).getString("name"));
                File partial = new File(file.getAbsolutePath() + ".partial");
                if (file.exists()) ok &= file.delete();
                if (partial.exists()) ok &= partial.delete();
                File parent = file.getParentFile();
                File[] partFiles = parent == null ? null : parent.listFiles((dir, name) ->
                    name.startsWith(file.getName() + ".part-") && name.endsWith(".partial"));
                if (partFiles != null) for (File part : partFiles) ok &= part.delete();
            }
        }
        return ok;
    }

    private void downloadFile(String packageId, JSONObject spec, ProgressSink sink) throws Exception {
        String name = spec.getString("name");
        long expected = spec.getLong("bytes");
        String expectedSha = spec.getString("sha256").toLowerCase(Locale.ROOT);
        if (expected <= 0 || expectedSha.length() != 64) throw new IllegalStateException("模型包缺少有效大小或SHA-256");
        File target = localFile(packageId, name);
        File dir = target.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法建立模型目录");
        if (target.isFile() && target.length() == expected && sha256(target).equals(expectedSha)) {
            sink.update(packageId, 100, name + " 已安装并通过校验");
            return;
        }

        File partial = new File(target.getAbsolutePath() + ".partial");
        JSONArray sources = spec.getJSONArray("sources");
        Exception last = null;
        for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex++) {
            JSONObject source = sources.getJSONObject(sourceIndex);
            String region = source.optString("region", "source");
            try {
                sink.update(packageId, percent(partial.length(), expected), "正在连接" + region + "下载源");
                transfer(source.getString("url"), partial, expected, 0, expected, packageId, name, sink);
                if (partial.length() != expected) throw new IllegalStateException("文件大小错误");
                sink.update(packageId, 99, "正在校验SHA-256");
                if (!sha256(partial).equals(expectedSha)) throw new IllegalStateException("SHA-256不一致");
                if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧模型");
                if (!partial.renameTo(target)) throw new IllegalStateException("无法完成模型安装");
                sink.update(packageId, 100, name + " 安装完成");
                return;
            } catch (Exception error) {
                last = error;
                sink.update(packageId, percent(partial.length(), expected), region + "失败，准备切换下载源");
            }
        }
        throw new IllegalStateException("所有下载源均失败：" + (last == null ? "未知错误" : last.getMessage()));
    }

    private void downloadMultipart(String packageId, JSONObject spec, ProgressSink sink) throws Exception {
        String name = spec.getString("name");
        long expected = spec.getLong("bytes");
        String expectedSha = spec.getString("sha256").toLowerCase(Locale.ROOT);
        JSONArray parts = spec.getJSONArray("parts");
        if (expected <= 0 || expectedSha.length() != 64 || parts.length() < 2) {
            throw new IllegalStateException("分片模型缺少有效大小、SHA-256或分片");
        }
        File target = localFile(packageId, name);
        File dir = target.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法建立模型目录");
        if (target.isFile() && target.length() == expected && sha256(target).equals(expectedSha)) {
            sink.update(packageId, 100, name + " 已安装并通过校验");
            return;
        }

        long declaredTotal = 0;
        for (int i = 0; i < parts.length(); i++) declaredTotal += parts.getJSONObject(i).getLong("bytes");
        if (declaredTotal != expected) throw new IllegalStateException("模型分片总大小与目标文件不一致");

        long completed = 0;
        File[] staged = new File[parts.length()];
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            long partBytes = part.getLong("bytes");
            String partSha = part.getString("sha256").toLowerCase(Locale.ROOT);
            File partial = new File(target.getAbsolutePath() + String.format(Locale.ROOT, ".part-%03d.partial", i + 1));
            staged[i] = partial;
            if (!(partial.isFile() && partial.length() == partBytes && sha256(partial).equals(partSha))) {
                if (partial.length() > partBytes && !partial.delete()) throw new IOException("无法清理损坏分片");
                downloadPart(packageId, part, partial, completed, expected, sink);
            }
            completed += partBytes;
        }

        File assembled = new File(target.getAbsolutePath() + ".partial");
        sink.update(packageId, 98, "正在合并模型分片");
        try (FileOutputStream out = new FileOutputStream(assembled, false)) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            for (File part : staged) {
                try (FileInputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (cancelled) throw new InterruptedException("下载已取消");
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
        if (assembled.length() != expected) throw new IllegalStateException("合并后的模型大小错误");
        sink.update(packageId, 99, "正在校验完整模型SHA-256");
        if (!sha256(assembled).equals(expectedSha)) throw new IllegalStateException("完整模型SHA-256不一致");
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧模型");
        if (!assembled.renameTo(target)) throw new IllegalStateException("无法完成模型安装");
        for (File part : staged) if (part.exists() && !part.delete()) part.deleteOnExit();
        sink.update(packageId, 100, name + " 安装完成");
    }

    private void downloadPart(String packageId, JSONObject part, File partial, long completed,
                              long total, ProgressSink sink) throws Exception {
        String name = part.getString("name");
        long expected = part.getLong("bytes");
        String expectedSha = part.getString("sha256").toLowerCase(Locale.ROOT);
        JSONArray sources = part.getJSONArray("sources");
        Exception last = null;
        for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex++) {
            JSONObject source = sources.getJSONObject(sourceIndex);
            String region = source.optString("region", "source");
            try {
                sink.update(packageId, percent(completed + partial.length(), total),
                    "正在连接" + region + "下载源（分片）");
                transfer(source.getString("url"), partial, expected, completed, total, packageId, name, sink);
                if (partial.length() != expected) throw new IllegalStateException("分片大小错误");
                sink.update(packageId, Math.min(97, percent(completed + expected, total)), "正在校验分片SHA-256");
                if (!sha256(partial).equals(expectedSha)) {
                    if (!partial.delete()) throw new IOException("无法清理校验失败的分片");
                    throw new IllegalStateException("分片SHA-256不一致");
                }
                return;
            } catch (Exception error) {
                last = error;
                sink.update(packageId, percent(completed + Math.min(partial.length(), expected), total),
                    region + "失败，准备切换下载源");
            }
        }
        throw new IllegalStateException("所有分片下载源均失败：" + (last == null ? "未知错误" : last.getMessage()));
    }

    private void transfer(String url, File partial, long expected, long progressBase, long progressTotal,
                          String packageId, String name, ProgressSink sink) throws Exception {
        long offset = partial.isFile() ? partial.length() : 0;
        if (offset >= expected) offset = 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AI-Image-Mobile/0.1");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        int response = connection.getResponseCode();
        boolean append = offset > 0 && response == HttpURLConnection.HTTP_PARTIAL;
        if (response < 200 || response >= 300) throw new IllegalStateException("HTTP " + response);
        if (!append) offset = 0;
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial, append)) {
            byte[] buffer = new byte[1024 * 1024];
            long copied = offset;
            long lastNotify = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (cancelled) throw new InterruptedException("下载已取消");
                out.write(buffer, 0, n);
                copied += n;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 400) {
                    sink.update(packageId, Math.min(97, percent(progressBase + copied, progressTotal)), "正在下载 " + name);
                    lastNotify = now;
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject readCatalog() throws Exception {
        try (InputStream in = context.getAssets().open("catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private JSONObject findPackage(String id) throws Exception {
        JSONArray packages = readCatalog().getJSONArray("packages");
        for (int i = 0; i < packages.length(); i++) {
            JSONObject pack = packages.getJSONObject(i);
            if (id.equals(pack.getString("id"))) return pack;
        }
        throw new IllegalArgumentException("未知模型包：" + id);
    }

    private File localFile(String packageId, String name) {
        if (!packageId.matches("[a-z0-9-]+") || !name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("非法模型文件名");
        return new File(new File(root, packageId), name);
    }

    private static int percent(long value, long total) {
        return total <= 0 ? 0 : (int)Math.max(0, Math.min(100, value * 100 / total));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
}
