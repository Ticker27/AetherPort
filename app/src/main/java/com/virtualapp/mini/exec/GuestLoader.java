package com.virtualapp.mini.exec;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;

import com.virtualapp.mini.container.GuestHooks;
import com.virtualapp.mini.container.MiniLog;
import com.virtualapp.mini.container.NativeEnv;
import com.virtualapp.mini.container.PluginInstaller;
import com.virtualapp.mini.container.SandboxStore;
import com.virtualapp.mini.container.VPackageInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GuestLoader — เตรียม environment ของเกสต์ "ใน process นี้" แล้วผูกเข้า GuestIdentity
 * (ครึ่งแรกของ GuestLauncher เดิม — ครึ่งหลังที่สร้าง Activity ด้วย reflection ถูกถอดออก
 *  เพราะผิดกลไก: framework ต้องเป็นคนสร้าง Activity ผ่าน Instrumentation/HCallback)
 *
 * ลำดับคงเดิมตามหลักฐาน (AetherEngine GuestLauncher step 2–5a):
 *   1. sandbox apk มีจริง
 *   2. ensure layout
 *   3. แตก native จาก base + split ทุกตัว → sandbox lib
 *   4. DexClassLoader(base+split, oat, lib, hostLoader)
 *   5. plugin pipeline (ensure → load) ก่อน boot guest
 *   6. ApplicationInfo ชี้ sandbox
 *   7. setenv HOME=<sandbox user dir>  (Unity/IL2CPP ctor chain)
 *   8. GuestIdentity.bind(...)
 */
public final class GuestLoader {

    private static final String TAG = "AetherPort";

    private GuestLoader() {}

    /** @return null เมื่อสำเร็จ, ข้อความ error เมื่อล้ม (stage-style เหมือนต้นทาง) */
    public static String prepare(Context ctx, String pkg) {
        return prepare(ctx, pkg, true);
    }

    /**
     * @param withPlugin false = ข้ามขั้น plugin (ใช้ตอนเตรียมแบบ lazy ใน Instrumentation.newActivity
     *   ซึ่งรันบน main thread — การดาวน์โหลด .bin หลาย MB จะทำ ANR)
     *   plugin ที่เคย materialize แล้วจะถูกโหลดอยู่ดีเพราะ PluginLoader อ่านจาก workspace
     */
    public static String prepare(Context ctx, String pkg, boolean withPlugin) {
        File apk = SandboxStore.guestApk(ctx, pkg);
        if (!apk.exists()) return "container:not-installed(sandbox apk missing)";

        MiniLog.startSession(ctx, pkg);
        MiniLog.stage(MiniLog.lastSession(),
                "[session] prepare " + pkg + " apk=" + apk.length() + "B");

        if (apk.canWrite()) VPackageInstaller.markReadOnly(apk);

        SandboxStore.ensureGuestLayout(ctx, pkg);
        File libDir = SandboxStore.guestLibDir(ctx, pkg);

        // 3) native จาก base + split ทุกตัว
        int n = extractNatives(apk, libDir);
        MiniLog.stage(MiniLog.lastSession(), "[session] natives " + n + " libs → " + libDir);

        // 4) class path = base + split ทั้งหมด
        StringBuilder cp = new StringBuilder(apk.getAbsolutePath());
        File[] apks = SandboxStore.guestDir(ctx, pkg)
                .listFiles((d, name) -> name.endsWith(".apk"));
        if (apks != null) {
            for (File f : apks) {
                if (f.getAbsolutePath().equals(apk.getAbsolutePath())) continue;
                cp.append(':').append(f.getAbsolutePath());
            }
        }
        File oat = new File(new File(SandboxStore.root(ctx), "cache/oat"), pkg);
        if (!oat.exists()) oat.mkdirs();

        ClassLoader guestLoader;
        try {
            guestLoader = new dalvik.system.DexClassLoader(
                    cp.toString(), oat.getAbsolutePath(), libDir.getAbsolutePath(),
                    GuestLoader.class.getClassLoader());
        } catch (Throwable t) {
            return "container:classloader-failed:" + t;
        }
        MiniLog.stage(MiniLog.lastSession(), "[session] classloader ready (base+splits)");

        // 5) plugin pipeline ก่อน boot guest (parity KOS: กดเล่น → plugin ก่อน)
        if (withPlugin) {
            try {
                String pl = GuestHooks.ensurePlugin(ctx, pkg, 45000L);
                MiniLog.stage(MiniLog.lastSession(), "[plugin] ensure → " + pl);
                String lr = GuestHooks.loadPlugin(ctx, pkg, guestLoader);
                MiniLog.stage(MiniLog.lastSession(), "[plugin] loader → " + lr);
            } catch (Throwable t) {
                MiniLog.stage(MiniLog.lastSession(), "[plugin] error " + t);
            }
        } else {
            // lazy path: อย่าดาวน์โหลด/โหลด plugin บน main thread — ปล่อยให้ปุ่ม Prepare plugin ทำ
            MiniLog.stage(MiniLog.lastSession(), "[plugin] skipped (lazy prepare)");
        }

        // 6) ApplicationInfo ชี้ sandbox
        ApplicationInfo ai = ComponentResolver.guestApplicationInfo(ctx, pkg, null);
        if (ai == null) return "container:no-appinfo";

        // 7) HOME สำหรับ native ctor chain
        try {
            File home = SandboxStore.guestUserDataDir(ctx, pkg);
            NativeEnv.setEnv("HOME", home.getAbsolutePath());
            File library = new File(home, "Library");
            if (!library.exists()) library.mkdirs();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "HOME setup: " + t);
        }

        // 8) ผูกเป็นเกสต์ของ process นี้
        try {
            GuestIdentity.bind(pkg, apk.getAbsolutePath(), guestLoader, ai);
        } catch (IllegalStateException e) {
            return "container:process-busy:" + e.getMessage();
        }

        MiniLog.stage(MiniLog.lastSession(), "[session] guest bound in "
                + GuestIdentity.currentProcessName(ctx));

        // 8b) เขียนตัวตนเกสต์ลง ActivityThread ของ process นี้ (ทำได้เฉพาะ :pN)
        try {
            GuestIdentity.adoptProcessIdentity(ctx);
        } catch (Throwable t) {
            MiniLog.stage(MiniLog.lastSession(), "[session] adopt skipped: " + t);
        }

        // 9) Application + providers ของเกสต์ (ช่องที่ v2 ยังไม่ปิด)
        String rtErr = GuestRuntime.boot(ctx);
        if (rtErr != null) return rtErr;

        return null;
    }

    /**
     * เปิด launcher activity ของเกสต์ ผ่าน stub component
     * (ไม่สร้าง Activity เอง — ปล่อยให้ ActivityThread/Instrumentation ทำ)
     */
    public static String launch(Context ctx, String pkg) {
        if (!GuestIdentity.isPrepared()) {
            String err = prepare(ctx, pkg);
            if (err != null) return err;
        }

        ActivityInfo info = ComponentResolver.resolveLauncher(ctx, pkg);
        if (info == null) return "container:no-launcher-activity";

        Intent guest = new Intent(Intent.ACTION_MAIN);
        guest.setClassName(pkg, info.name);
        guest.addCategory(Intent.CATEGORY_LAUNCHER);

        Intent stub = GuestIdentity.makeStubIntent(ctx, guest, info, 0);
        stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(stub);

        MiniLog.stage(MiniLog.lastSession(), "[session] stub launch → "
                + stub.getComponent().flattenToShortString() + " for " + info.name);
        return "container:launched(" + pkg + "/" + info.name + ")";
    }

    /** แตก lib/arm64-v8a/*.so จากทุก apk ใน guestDir → libDir (ทำครั้งเดียว) */
    private static int extractNatives(File baseApk, File libDir) {
        File[] have = libDir.listFiles((d, f) -> f.endsWith(".so"));
        if (have != null && have.length > 0) return have.length;

        List<File> apks = new ArrayList<>();
        File[] all = baseApk.getParentFile() != null
                ? baseApk.getParentFile().listFiles((d, f) -> f.endsWith(".apk")) : null;
        if (all != null) java.util.Collections.addAll(apks, all);
        if (apks.isEmpty()) apks.add(baseApk);

        int ok = 0;
        for (File apk : apks) {
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
                    out.setReadable(true, false);
                    out.setExecutable(true, false);
                    ok++;
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "extract " + apk.getName() + ": " + t);
            }
        }
        return ok;
    }
}
