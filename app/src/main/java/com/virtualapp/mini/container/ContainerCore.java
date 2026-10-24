package com.virtualapp.mini.container;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import com.virtualapp.mini.exec.ComponentResolver;
import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.GuestLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ContainerCore — coordinator ของ container (แทน AetherCore ของต้นทาง)
 *
 * เขียนใหม่ให้ตรงกลไก VirtualApp:
 *   - main process: "สั่ง" เท่านั้น — resolve ActivityInfo แล้วเปิด StubActivity
 *     (ไม่เตรียม lớp/ไม่แตะ ActivityThread ของ host)
 *   - :p0 process : "ทำงาน" — HookManager ติดตั้ง proxy, GuestLoader เตรียม sandbox,
 *     framework สร้าง Activity ของเกมผ่าน HCallback/Instrumentation
 *
 * ของเดิมที่ถอดออกและเหตุผล:
 *   launchInContainer → startActivity(VStubActivity) แล้ว VStub สร้าง Activity เองด้วย reflection
 *   = ข้าม ActivityThread → ไม่มี ActivityClientRecord/mToken → WindowManager.BadTokenException
 *     และ lifecycle ของเกมไม่ถูกเรียกจาก framework (ดู docs/AUDIT.md ข้อ A2)
 */
public final class ContainerCore {

    private static final String TAG = "AetherPort";

    private static volatile boolean sInit = false;
    private static Context sCtx;

    private static final String PREFS = "com.virtualapp.mini.container";
    private static final String KEY_REGISTRY = "installed";
    private static final String KEY_ENGINE = "engine_version";

    public static final String TARGET_8BP = "com.miniclip.eightballpool";
    public static final String TARGET_8BP_VERSION = "56.31.0";

    private ContainerCore() {}

    // ── lifecycle ────────────────────────────────────────────────

    public static void init(Context ctx) {
        if (sInit) return;
        synchronized (ContainerCore.class) {
            if (sInit) return;
            Context appCtx = ctx.getApplicationContext();
            sCtx = appCtx != null ? appCtx : ctx;
            sInit = true;
            android.util.Log.i(TAG, "ContainerCore.init done (process="
                    + GuestIdentity.currentProcessName(sCtx) + ")");
        }
    }

    public static void postInit(Context ctx) {
        final Context appCtx = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        Thread boot = new Thread(() -> {
            try {
                SandboxStore.ensureBaseLayout(appCtx);
                int n = 0;
                for (TargetRegistry.Target t : TargetRegistry.all()) {
                    if (SandboxStore.guestApk(appCtx, t.pkg).exists()) {
                        SandboxStore.ensureGuestLayout(appCtx, t.pkg);
                        n++;
                    }
                }
                android.util.Log.i(TAG, "sandbox ready, guests=" + n);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "sandbox ensure: " + t);
            }
        }, "aether-boot");
        boot.start();
    }

    public static Context coreContext() { return sCtx; }

    public static String engineVersion() {
        return "AetherPort 2.0.0 (stub-component redirection, VirtualApp-shaped)";
    }

    // ── install ──────────────────────────────────────────────────

    public static VPackageInstaller.Result installFromDevice(Context ctx, String pkg) {
        final VPackageInstaller.Result[] holder = new VPackageInstaller.Result[1];
        String r = RunOffMain.run(() -> {
            holder[0] = VPackageInstaller.installFromDevice(ctx, pkg);
            return holder[0].detail;
        }, 600000L, "ERR:timeout(10m)", "install-device");
        if (holder[0] == null) {
            return new VPackageInstaller.Result(false, pkg, "?", 0, "unknown", r, null);
        }
        return holder[0];
    }

    public static VPackageInstaller.Result installFromPath(Context ctx, String apkPath) {
        final VPackageInstaller.Result[] holder = new VPackageInstaller.Result[1];
        String r = RunOffMain.run(() -> {
            holder[0] = VPackageInstaller.installFromPath(ctx, apkPath);
            return holder[0].detail;
        }, 600000L, "ERR:timeout(10m)", "install-path");
        if (holder[0] == null) {
            return new VPackageInstaller.Result(false, null, "?", 0, "unknown", r, null);
        }
        return holder[0];
    }

    // ── launch (main process: สั่งเท่านั้น) ──────────────────────

    /**
     * เปิดเกสต์ — ทำงานได้เฉพาะใน main process
     * ขั้น: pin gate → resolve launcher ActivityInfo → เปิด StubActivity$C0 พร้อมซอง
     * @return status string
     */
    public static String launchInContainer(Context ctx, String targetPkg) {
        if (GuestIdentity.isStubProcess(ctx)) {
            return "refuse:launch-from-stub-process";
        }
        File apk = SandboxStore.guestApk(ctx, targetPkg);
        if (!apk.exists()) return "not-installed-in-sandbox";

        JSONObject st = SandboxStore.readPackageSetting(ctx, targetPkg);
        String ver = st != null ? st.optString("versionName", "?") : "?";
        TargetRegistry.Target t = TargetRegistry.byPkg(targetPkg);
        if (t != null) {
            VersionUtil.Result vr = VersionUtil.evaluate(ver, t.pinVersion,
                    t.allowedVersions, t.versionPolicy);
            if ("mismatch".equals(vr.state)) {
                return "blocked:version-mismatch have=" + ver
                        + " allowed=" + java.util.Arrays.toString(t.allowedVersions);
            }
        }

        ActivityInfo info = ComponentResolver.resolveLauncher(ctx, targetPkg);
        if (info == null) return "no-launcher-activity(in sandbox manifest)";

        Intent guest = new Intent(Intent.ACTION_MAIN)
                .setClassName(targetPkg, info.name)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        Intent stub = GuestIdentity.makeStubIntent(ctx, guest, info, 0);
        stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        SandboxStore.markLaunched(ctx, targetPkg, System.currentTimeMillis());
        SandboxStore.writeProcCmdline(ctx, targetPkg);

        ctx.startActivity(stub);
        MiniLog.engine(ctx, "launch → " + stub.getComponent().flattenToShortString()
                + " for " + info.name + "@" + ver);
        return "launched:" + (t != null ? t.name : targetPkg) + "@" + ver
                + " → " + stub.getComponent().getClassName();
    }

    // ── status ───────────────────────────────────────────────────

    public static JSONObject targetStatus(Context ctx, String pkg) {
        JSONObject o = new JSONObject();
        try {
            TargetRegistry.Target t = TargetRegistry.byPkg(pkg);
            o.put("pkg", pkg);
            o.put("pin", t != null ? t.pinVersion : "n/a");
            o.put("policy", t != null ? t.versionPolicy : "n/a");
            o.put("allowed", t != null
                    ? new JSONArray(java.util.Arrays.asList(t.allowedVersions)) : new JSONArray());
            o.put("deviceVersion", deviceVersion(ctx, pkg));
            JSONObject conf = SandboxStore.readPackageSetting(ctx, pkg);
            String sv = conf != null ? conf.optString("versionName", null) : null;
            o.put("sandboxVersion", sv == null ? JSONObject.NULL : sv);
            o.put("sandboxApk", SandboxStore.guestApk(ctx, pkg).exists());
            String status;
            if (sv == null) status = "not-installed";
            else if (t == null) status = "n/a";
            else status = VersionUtil.evaluate(sv, t.pinVersion, t.allowedVersions,
                    t.versionPolicy).state;
            o.put("status", status);
            o.put("launcher", launcherName(ctx, pkg));
        } catch (Exception e) {
            try { o.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
        return o;
    }

    public static String launcherName(Context ctx, String pkg) {
        try {
            ActivityInfo ai = ComponentResolver.resolveLauncher(ctx, pkg);
            return ai != null ? ai.name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String deviceVersion(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isInstalledOnDevice(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<JSONObject> listInstalled(Context ctx) {
        return listRegistry(ctx);
    }

    // ── registry ─────────────────────────────────────────────────

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void addToRegistry(Context ctx, JSONObject entry) {
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_REGISTRY, "[]"));
            arr.put(entry);
            prefs(ctx).edit().putString(KEY_REGISTRY, arr.toString()).apply();
        } catch (Exception e) {
            android.util.Log.w(TAG, "registry add: " + e.getMessage());
        }
    }

    static List<JSONObject> listRegistry(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_REGISTRY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    public static void persistEngineVersion() {
        if (sCtx != null) prefs(sCtx).edit().putString(KEY_ENGINE, engineVersion()).apply();
    }

    /** เรียกจาก MiniApp ใน :pN หลัง hook ติดตั้งแล้ว — เตรียมเกสต์ทันทีถ้ารู้เป้าหมาย */
    public static String prepareIfKnown(Context ctx, String pkg) {
        if (!GuestIdentity.isStubProcess(ctx)) return "not-a-stub-process";
        if (GuestIdentity.isPrepared()) return "already-prepared";
        return GuestLoader.prepare(ctx, pkg);
    }
}
