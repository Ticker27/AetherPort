package com.virtualapp.mini.container;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * VPackageInstaller — port ของ AetherEngine container/VPackageInstaller.java
 *
 * ต่างจากต้นทางเฉพาะ "handoff → system installer" ที่ตัดออก (เราไม่ส่งต่อให้ตัวติดตั้งระบบ;
 * virtual environment ของเราอ่าน APK จาก sandbox เอง)
 *
 * ขั้นตอนคงเดิมทุกขั้น (parity: install gate → copy → PackageSetting → uid → pin):
 *   1. PackageManager บนเครื่อง = source of truth (sourceDir + splitSourceDirs/splitNames)
 *   2. copy base + splits เข้า root/data/app/<pkg>/
 *   3. sha256 ต่อไฟล์ (self-record เพราะไม่มี C2)
 *   4. เทียบ pin กับ TargetRegistry (8BP = 56.31.0)
 *   5. เขียน package.conf (PackageSetting + states) + uid.conf
 *   6. เตรียม lib dir
 */
public final class VPackageInstaller {

    private VPackageInstaller() {}

    /** ผลลัพธ์แบบมีโครง (ให้ UI แสดง version ที่เจอบนเครื่อง + ผล pin ได้) */
    public static final class Result {
        public final boolean ok;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final String status;        // pin state: match | mismatch | allowed | unknown | n/a
        public final String detail;        // ข้อความเต็ม (parity: string ที่ต้นทางคืน)
        public final File apk;

        Result(boolean ok, String pkg, String ver, long code, String status, String detail, File apk) {
            this.ok = ok;
            this.packageName = pkg;
            this.versionName = ver;
            this.versionCode = code;
            this.status = status;
            this.detail = detail;
            this.apk = apk;
        }
    }

    /**
     * ติดตั้งจากแอปที่ติดตั้งอยู่บนเครื่อง
     * parity: AetherCore.installFromDevice → installFromDeviceInner
     */
    public static Result installFromDevice(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            if (pi == null || pi.applicationInfo == null) {
                return new Result(false, pkg, "?", 0, "unknown", "device:no-app-info", null);
            }

            String srcBase = pi.applicationInfo.sourceDir;
            if (srcBase == null || !new File(srcBase).exists()) {
                return new Result(false, pkg, "?", 0, "unknown", "device:source-missing", null);
            }

            String version = pi.versionName == null ? "?" : pi.versionName;
            long vCode = pi.getLongVersionCode();

            SandboxStore.ensureBaseLayout(ctx);
            SandboxStore.ensureGuestLayout(ctx, pkg);

            // 1) base.apk
            File dstBase = SandboxStore.guestApk(ctx, pkg);
            copyFile(new File(srcBase), dstBase);
            markReadOnly(dstBase);

            // 2) split APKs (source = device install; ไม่ดึงจากไฟล์ภายนอก)
            JSONArray splitArr = new JSONArray();
            String[] splitDirs = pi.applicationInfo.splitSourceDirs;
            String[] splitNames = pi.splitNames;
            if (splitDirs != null && splitDirs.length > 0) {
                for (int i = 0; i < splitDirs.length; i++) {
                    String sp = splitDirs[i];
                    if (sp == null || !new File(sp).exists()) continue;
                    String nm = (splitNames != null && i < splitNames.length && splitNames[i] != null)
                            ? splitNames[i] : ("split_" + i);
                    File dst = new File(SandboxStore.guestDir(ctx, pkg), nm + ".apk");
                    copyFile(new File(sp), dst);
                    markReadOnly(dst);
                    splitArr.put(dst.getAbsolutePath());
                }
            }

            // 3) sha256 (self-record)
            String sha = sha256(dstBase);

            // 4) pin check — 8BP = 56.31.0
            TargetRegistry.Target t = TargetRegistry.byPkg(pkg);
            String pin;
            String pinState;
            if (t == null) {
                pin = "n/a";
                pinState = "n/a";
            } else {
                VersionUtil.Result vr = VersionUtil.evaluate(version, t.pinVersion,
                        t.allowedVersions, t.versionPolicy);
                pinState = vr.state;
                pin = vr.state.equals("mismatch")
                        ? "mismatch(want=" + t.pinVersion + ")"
                        : vr.state;
            }

            // 5) package.conf (PackageSetting + states) + uid.conf
            JSONObject setting = new JSONObject();
            setting.put("apkPath", dstBase.getAbsolutePath());
            setting.put("versionName", version);
            setting.put("versionCode", vCode);
            setting.put("sha256", sha);
            setting.put("installedAt", System.currentTimeMillis());
            setting.put("pin", pin);
            setting.put("splits", splitArr);
            JSONObject states = new JSONObject();
            states.put("installed", true);
            states.put("stopped", false);
            states.put("hidden", false);
            states.put("launched", false);
            setting.put("states", states);
            SandboxStore.writePackageSetting(ctx, pkg, setting);
            int uid = SandboxStore.allocUid(ctx, pkg);
            SandboxStore.guestLibDir(ctx, pkg);

            String detail = "installed:" + pkg + "@" + version + " pin=" + pin + " uid=" + uid
                    + " splits=" + splitArr.length()
                    + " sha256=" + (sha != null && sha.length() >= 12 ? sha.substring(0, 12) + "…" : sha);
            MiniLog.engine(ctx, detail);
            return new Result(true, pkg, version, vCode, pinState, detail, dstBase);

        } catch (Exception e) {
            MiniLog.engine(ctx, "install device ERR: " + e);
            return new Result(false, pkg, "?", 0, "unknown", "ERR:" + e, null);
        }
    }

    /** ติดตั้งจาก APK ที่ผู้ใช้เลือก (file path) — ใช้คู่กับ flow เดิมของ MiniVirtualApp */
    public static Result installFromPath(Context ctx, String apkPath) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
            if (pi == null) {
                return new Result(false, null, "?", 0, "unknown", "ERR:not-an-apk", null);
            }
            String pkg = pi.packageName;
            String version = pi.versionName == null ? "?" : pi.versionName;

            SandboxStore.ensureBaseLayout(ctx);
            SandboxStore.ensureGuestLayout(ctx, pkg);

            File dst = SandboxStore.guestApk(ctx, pkg);
            copyFile(new File(apkPath), dst);
            markReadOnly(dst);

            String sha = sha256(dst);
            TargetRegistry.Target t = TargetRegistry.byPkg(pkg);
            String pinState = "n/a";
            String pin = "n/a";
            if (t != null) {
                VersionUtil.Result vr = VersionUtil.evaluate(version, t.pinVersion,
                        t.allowedVersions, t.versionPolicy);
                pinState = vr.state;
                pin = vr.state.equals("mismatch") ? "mismatch(want=" + t.pinVersion + ")" : vr.state;
            }

            JSONObject setting = new JSONObject();
            setting.put("apkPath", dst.getAbsolutePath());
            setting.put("versionName", version);
            setting.put("versionCode", pi.getLongVersionCode());
            setting.put("sha256", sha);
            setting.put("installedAt", System.currentTimeMillis());
            setting.put("pin", pin);
            JSONObject states = new JSONObject();
            states.put("installed", true);
            states.put("launched", false);
            setting.put("states", states);
            SandboxStore.writePackageSetting(ctx, pkg, setting);
            int uid = SandboxStore.allocUid(ctx, pkg);
            SandboxStore.guestLibDir(ctx, pkg);

            String detail = "installed:" + pkg + "@" + version + " pin=" + pin + " uid=" + uid;
            MiniLog.engine(ctx, detail);
            return new Result(true, pkg, version, pi.getLongVersionCode(), pinState, detail, dst);
        } catch (Exception e) {
            return new Result(false, null, "?", 0, "unknown", "ERR:" + e, null);
        }
    }

    /** Android ≥14: PathClassLoader ปฏิเสธ dex ที่เขียนได้ — mark read-only ก่อนโหลด */
    public static void markReadOnly(File f) {
        try {
            if (!f.setReadOnly()) f.setWritable(false, false);
            MiniLog.engine(null, "apk read-only: " + f.getName() + " writable=" + f.canWrite());
        } catch (Exception e) {
            MiniLog.engine(null, "setReadOnly: " + e.getMessage());
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    public static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
