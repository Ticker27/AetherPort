package com.virtualapp.mini.exec;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;

import com.virtualapp.mini.model.StubActivityRecord;
import com.virtualapp.mini.stub.StubActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GuestIdentity — ตัวตนเกสต์ "ระดับ process" (แทน VClientImpl ของ VirtualApp)
 *
 * หลักการที่ VirtualApp ยึด และโปรเจกต์เดิมของเราละเมิด:
 *   1 process = 1 เกสต์ เท่านั้น
 *   เพราะ package identity ถูกเขียนลง ActivityThread/LoadedApk ซึ่งเป็น static/instance ของ process
 *   ถ้าเขียนใน main process ของ host → host พัง (นี่คือบั๊กของ ProcessAdoption เดิม)
 *
 * ที่นี่เก็บสถานะเดียวต่อ process และเป็นจุดเดียวที่แตะ ActivityThread
 */
public final class GuestIdentity {

    private static final String TAG = "AetherPort";

    /** process ของเกสต์ ตามที่ประกาศใน manifest (android:process) */
    public static final String PROC_0 = ":p0";
    public static final String PROC_1 = ":p1";

    /** stub class ที่ผูกกับแต่ละ process — ต้องตรงกับ manifest */
    private static final String[][] STUB_ACTIVITIES = {
            {PROC_0, "com.virtualapp.mini.stub.StubActivity$C0"},
            {PROC_1, "com.virtualapp.mini.stub.StubActivity$C1"},
    };

    private static volatile String sGuestPkg;
    private static volatile String sGuestApk;
    private static volatile ClassLoader sGuestLoader;
    private static volatile ApplicationInfo sGuestAppInfo;
    private static volatile boolean sPrepared;

    private GuestIdentity() {}

    // ── process name ────────────────────────────────────────────

    public static String currentProcessName(Context ctx) {
        // path 1: Application.getProcessName (API 28+)
        try {
            Method m = Context.class.getMethod("getProcessName");
            Object r = m.invoke(ctx.getApplicationContext());
            if (r != null) return (String) r;
        } catch (Throwable ignored) {}
        // path 2: ActivityThread.currentProcessName
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentProcessName");
            Object r = m.invoke(null);
            if (r != null) return (String) r;
        } catch (Throwable ignored) {}
        // path 3: /proc/self/cmdline
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n > 0) return new String(buf, 0, n).trim();
        } catch (Throwable ignored) {}
        return ctx.getPackageName();
    }

    /** process ที่ระบุใน manifest ตรงกับ process ที่รันอยู่หรือไม่ */
    public static boolean isStubProcess(Context ctx) {
        String cur = currentProcessName(ctx);
        for (String[] row : STUB_ACTIVITIES) {
            if (cur.endsWith(row[0])) return true;
        }
        return false;
    }

    public static boolean isMainProcess(Context ctx) {
        return !isStubProcess(ctx);
    }

    // ── guest state (one per process) ───────────────────────────

    public static void bind(String pkg, String apkPath, ClassLoader loader, ApplicationInfo ai) {
        if (sPrepared && !TextUtils.equals(sGuestPkg, pkg)) {
            throw new IllegalStateException("process already hosts guest '" + sGuestPkg
                    + "' — 1 process = 1 guest");
        }
        sGuestPkg = pkg;
        sGuestApk = apkPath;
        sGuestLoader = loader;
        sGuestAppInfo = ai;
        sPrepared = true;
        android.util.Log.i(TAG, "guest bound in " + pkg + " → " + apkPath);
    }

    public static boolean isPrepared() { return sPrepared; }
    public static String guestPkg() { return sGuestPkg; }
    public static String guestApk() { return sGuestApk; }
    public static ClassLoader guestLoader() { return sGuestLoader; }
    public static ApplicationInfo guestAppInfo() { return sGuestAppInfo; }

    /** pkg นี้เป็นของเกสต์ใน process นี้หรือไม่ (ใช้ใน PMS proxy) */
    public static boolean isGuestPackage(String pkg) {
        return pkg != null && TextUtils.equals(pkg, sGuestPkg);
    }

    // ── cross-process launch ────────────────────────────────────

    /**
     * เปิด stub ตัวที่ผูกกับ process เป้าหมาย
     * (แทน VActivityManager.get().startActivity ของต้นฉบับ ซึ่งเป็น IPC ไป server)
     */
    public static void requestCrossProcessLaunch(Context ctx, StubActivityRecord r) {
        String target = r.info != null && r.info.processName != null ? r.info.processName : PROC_0;
        String stubName = null;
        for (String[] row : STUB_ACTIVITIES) {
            if (target.endsWith(row[0])) { stubName = row[1]; break; }
        }
        if (stubName == null) {
            android.util.Log.w(TAG, "no stub for process " + target);
            return;
        }
        Intent stub = new Intent();
        stub.setClassName(ctx, stubName);
        stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        r.saveToIntent(stub);
        ctx.startActivity(stub);
    }

    /** สร้าง intent ที่ชี้ stub สำหรับ process ของ activity นี้ (ใช้โดย AMS proxy) */
    public static Intent makeStubIntent(Context ctx, Intent guestIntent, ActivityInfo info, int userId) {
        String proc = info != null && info.processName != null ? info.processName : PROC_0;
        String stubName = STUB_ACTIVITIES[0][1];
        for (String[] row : STUB_ACTIVITIES) {
            if (proc.endsWith(row[0])) { stubName = row[1]; break; }
        }
        Intent stub = new Intent(guestIntent);
        stub.setClassName(ctx, stubName);
        stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        StubActivityRecord r = new StubActivityRecord(guestIntent,
                info, null, userId);
        r.saveToIntent(stub);
        return stub;
    }

    // ── ActivityThread identity (ทำเฉพาะใน :pN) ─────────────────

    /**
     * เขียนตัวตนเกสต์ลง ActivityThread/LoadedApk — เรียกได้เฉพาะใน :pN
     * ถ้าถูกเรียกใน main process จะ throw (กันบั๊กเดิม)
     */
    public static void adoptProcessIdentity(Context ctx) throws IllegalStateException {
        if (isMainProcess(ctx)) {
            throw new IllegalStateException(
                    "refuse to adopt guest identity in main process — would corrupt host");
        }
        if (sGuestAppInfo == null) return;
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("currentActivityThread").invoke(null);

            Object bind = getField(at, "mBoundApplication");
            if (bind != null) {
                setField(bind, "appInfo", sGuestAppInfo);
                setField(bind, "processName", sGuestPkg);
                Object loadedApk = getField(bind, "info");
                if (loadedApk != null) {
                    setField(loadedApk, "mApplicationInfo", sGuestAppInfo);
                    setField(loadedApk, "mAppDir", sGuestApk);
                    setField(loadedApk, "mResDir", sGuestApk);
                    setField(loadedApk, "mClassLoader", sGuestLoader);
                    setField(loadedApk, "mPackageName", sGuestPkg);
                }
            }
            // cmdline ของ process = ชื่อเกม (parity: Process.setArgV0 ในต้นฉบับ)
            try {
                Method m = android.os.Process.class.getMethod("setArgV0", String.class);
                m.invoke(null, sGuestPkg);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "setArgV0: " + t);
            }
            android.util.Log.i(TAG, "process identity adopted → " + sGuestPkg
                    + " in " + currentProcessName(ctx));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "adoptProcessIdentity failed", t);
        }
    }

    private static Object getField(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(Object o, String name, Object v) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, v);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "setField " + name + ": " + t);
        }
    }
}
