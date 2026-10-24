package com.virtualapp.mini.container;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PluginInstaller — pipeline ปลั๊กอิน (parity KOS 3.7 วัดสด 2026-10-20 21:21)
 *
 * ลำดับของ KOS ที่วัดได้:
 *   1) cache/<id>  = /data/user/0/com.kos/cache/82f8ddb6…  (9,533,457 B)
 *   2) sha256(ไฟล์)= 0553f818… = ค่า loader_<pkg> ใน KOS_App_Settings.xml
 *   3) workspace   = …/app_plugin_workspace_p0/plugin_<epochMs>.apk (sha เดียวกัน)
 *                    + lib/{libbytehook,libmkos,libshadowhook}.so
 *                    + oat/plugin_<epochMs>.apk.cur.prof (+ oat/arm64/…)
 *   4) vm config   = cache/blackbox_vm_config.properties → pathToApk=cache/<id>
 *
 * ที่นี่ทำแบบเดียวกัน แต่ตั้งชื่อ cache ด้วย sha256 ของเนื้อไฟล์ (self-verify ได้)
 * และไม่แตะระบบ login key (ตัดตาม scope ของโปรเจกต์)
 */
public final class PluginInstaller {

    private static final String TAG = "AetherPort";
    public static final String PREFS = "com.virtualapp.mini.plugin";
    public static final String KEY_LOADER_PREFIX = "loader_";
    public static final String VM_CONFIG = "aetherport_vm_config.properties";

    private PluginInstaller() {}

    /** cache ของแอป (parity: /data/user/0/<app>/cache/) */
    public static File appCache(Context c) {
        File d = c.getCacheDir();
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** workspace ของ guest ต่อ process (parity: app_plugin_workspace_p0) */
    public static File workspace(Context c, String pkg) {
        File d = new File(SandboxStore.guestUserDataDir(c, pkg), "app_plugin_workspace_p0");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** state machine ของปลั๊กอิน (KOS-parity): verify → reuse/re-materialize/download อัตโนมัติ ตามสิ่งที่มีจริง */
    public static String ensurePlugin(Context c, String pkg, long timeoutMs) {
        return RunOffMain.run(() -> ensurePluginInner(c, pkg, timeoutMs),
                timeoutMs + 60000L, "ensure-timeout", "plugin-ensure");
    }

    private static String ensurePluginInner(Context c, String pkg, long timeoutMs) {
        TargetRegistry.Target t = TargetRegistry.byPkg(pkg);
        if (t == null || t.pluginUrl == null) return "n/a";
        SandboxStore.ensureBaseLayout(c);
        SandboxStore.ensureGuestLayout(c, pkg);

        JSONObject conf = SandboxStore.readPackageSetting(c, pkg);
        JSONObject pl = conf == null ? null : conf.optJSONObject("plugin");
        String recSha = pl == null ? null : pl.optString("sha256", null);
        File cache = appCache(c);

        if (recSha != null && recSha.length() == 64) {
            File cached = new File(cache, recSha + ".bin");
            boolean reuse = false;
            if (cached.exists() && cached.length() > 0) {
                String actual = sha256File(cached);
                reuse = recSha.equals(actual);
                android.util.Log.i(TAG, "plugin cache verify: " + recSha.substring(0, 12)
                        + " → " + (reuse ? "ok" : "corrupt (download ใหม่)"));
            }
            if (reuse) {
                return "ready:" + recSha.substring(0, 12)
                        + " (" + materialize(c, pkg, cached, recSha, conf) + ")";
            }
            // self-heal: workspace หาย/ไม่ครบ แต่ cache ยังอยู่ → materialize จาก cache ให้ใหม่
        }

        File tmp = new File(cache, "aether_plugin.tmp");
        String sha = download(t.pluginUrl, tmp, timeoutMs);
        if (sha == null) {
            // self-heal แบบ KOS (CDN rotated/404): ใช้ cache bin ล่าสุดที่เคยดาวน์โหลดไว้แทน
            File alt = newestCacheBin(cache);
            if (alt == null) return "download-failed";
            sha = sha256File(alt);
            android.util.Log.i(TAG, "plugin cdn 404 → serving cache " + alt.getName()
                    + " (sha=" + sha.substring(0, 12) + "…)");
            return "ready-from-cache:" + sha.substring(0, 12)
                    + " (" + materialize(c, pkg, alt, sha, conf) + ")";
        }

        File[] bins = cache.listFiles((d, n) -> n.endsWith(".bin"));
        if (bins != null) {
            for (File f : bins) {
                if (!f.getName().equals(sha + ".bin")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        File dst = new File(cache, sha + ".bin");
        if (!tmp.renameTo(dst)) {
            try {
                copyFile(tmp, dst);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } catch (Exception e) {
                android.util.Log.w(TAG, "plugin cache: " + e);
                return "cache-write-failed";
            }
        }
        return "downloaded:" + sha.substring(0, 12) + " (" + materialize(c, pkg, dst, sha, conf) + ")";
    }

    /** กระจายเข้า workspace (apk + lib + oat) + บันทึก prefs loader_ + vm config */
    private static String materialize(Context c, String pkg, File bin, String sha, JSONObject conf) {
        try {
            File ws = workspace(c, pkg);
            File libDir = new File(ws, "lib");
            File oatDir = new File(new File(ws, "oat"), "arm64");
            if (!libDir.exists()) libDir.mkdirs();
            if (!oatDir.exists()) oatDir.mkdirs();

            // ล้างของเก่าใน workspace (legacy plugin.apk + plugin_*.apk) — เก็บชุดเดียว
            File[] olds = ws.listFiles((d, n) -> n.endsWith(".apk"));
            if (olds != null) {
                for (File f : olds) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            File apk = new File(ws, "plugin_" + System.currentTimeMillis() + ".apk");
            copyFile(bin, apk);
            VPackageInstaller.markReadOnly(apk);   // Android 14+: dex ที่เขียนได้โหลดไม่ได้

            int libs = extractLibs(apk, libDir);

            JSONObject setting = conf != null ? conf : new JSONObject();
            JSONObject p = setting.optJSONObject("plugin");
            if (p == null) p = new JSONObject();
            p.put("url", TargetRegistry.byPkg(pkg) != null ? TargetRegistry.byPkg(pkg).pluginUrl : "");
            p.put("cachePath", bin.getAbsolutePath());
            p.put("sha256", sha);
            p.put("size", bin.length());
            p.put("apk", apk.getAbsolutePath());
            p.put("libs", libs);
            p.put("preparedAt", System.currentTimeMillis());
            JSONObject wsj = new JSONObject();
            wsj.put("apk", apk.getName());
            wsj.put("libDir", libDir.getAbsolutePath());
            wsj.put("oatDir", oatDir.getAbsolutePath());
            wsj.put("at", System.currentTimeMillis());
            p.put("workspace", wsj);
            setting.put("plugin", p);
            SandboxStore.writePackageSetting(c, pkg, setting);

            // prefs: loader_<pkg> = sha256 (parity KOS_App_Settings)
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_LOADER_PREFIX + pkg, sha).apply();

            // vm config (parity blackbox_vm_config.properties)
            writeVmConfig(c, pkg, bin.getAbsolutePath(), sha);

            android.util.Log.i(TAG, "plugin materialized: apk=" + apk.getName()
                    + " libs=" + libs + " sha=" + sha.substring(0, 12) + "…");
            MiniLog.engine(c, "plugin: apk=" + apk.getName()
                    + " libs=" + libs + " sha=" + sha);
            return "workspace-ok:" + libs + "lib";
        } catch (Throwable t) {
            android.util.Log.w(TAG, "plugin materialize: " + t);
            return "workspace-fail";
        }
    }

    private static void writeVmConfig(Context c, String pkg, String apkPath, String sha) {
        try {
            String gid = c.getSharedPreferences("aether", Context.MODE_PRIVATE)
                    .getString("gid", null);
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(new java.util.Date()).append('\n');
            sb.append("enable_plugin=true\n");
            sb.append("plugin_target_package=").append(pkg).append('\n');
            sb.append("pathToApk=").append(apkPath).append('\n');
            sb.append("loader_sha256=").append(sha).append('\n');
            if (gid != null) sb.append("g_id=").append(gid).append('\n');
            File f = new File(appCache(c), VM_CONFIG);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "vm config: " + t);
        }
    }

    /** แตก lib/<abi>/*.so ของปลั๊กอินเข้า workspace/lib (parity: KOS แตก libbytehook/mkos/shadowhook) */
    private static int extractLibs(File apk, File libDir) {
        int n = 0;
        try (ZipFile zf = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith("lib/arm64-v8a/") || !name.endsWith(".so")) continue;
                File out = new File(libDir, name.substring(name.lastIndexOf('/') + 1));
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                }
                n++;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "plugin extractLibs: " + t);
        }
        return n;
    }

    /** cache bin ล่าสุดที่มี (ชื่อ <sha>.bin เท่านั้น) — สำหรับ self-heal เมื่อ CDN หาย */
    private static File newestCacheBin(File cache) {
        File[] all = cache.listFiles((d, n) -> n.length() == 68 && n.endsWith(".bin"));
        if (all == null || all.length == 0) return null;
        File best = all[0];
        long bm = best.lastModified();
        for (File f : all) if (f.lastModified() > bm) { bm = f.lastModified(); best = f; }
        return best.length() > 0 ? best : null;
    }

    private static String download(String urlStr, File dst, long timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout((int) Math.max(20000L, timeoutMs));
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AetherEngine/1.0");
            int code = c.getResponseCode();
            if (code != 200) {
                android.util.Log.w(TAG, "plugin download HTTP " + code);
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    total += n;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            android.util.Log.i(TAG, "plugin downloaded: " + total + " B sha=" + sb.substring(0, 12) + "…");
            return sb.toString();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "plugin download: " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String sha256File(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        File d = dst.getParentFile();
        if (d != null && !d.exists()) d.mkdirs();
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
