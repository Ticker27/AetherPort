package com.virtualapp.mini.exec;

import android.app.Application;
import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;

import com.virtualapp.mini.container.GuestManifest;
import com.virtualapp.mini.container.MiniLog;
import com.virtualapp.mini.container.SandboxStore;

import java.lang.reflect.Method;
import java.util.List;

/**
 * GuestRuntime — สร้าง Application + ContentProvider ของเกสต์ "ใน process ของเกสต์"
 *
 * ช่องที่ปิดในรอบนี้ (v2.1):
 *   v2 เตรียมแค่ ClassLoader + ApplicationInfo แต่ไม่เคยสร้าง Application ของเกม
 *   → เกม Unity/IL2CPP ที่พึ่ง Application.onCreate (และ packer ที่ init ที่ provider)
 *     จะไม่ทำงานเลย แม้ activity จะถูกสร้างสำเร็จก็ตาม
 *
 * ลำดับตาม framework จริง (ActivityThread.handleBindApplication):
 *   1. ContextImpl ของ process  (ใน :p0 = ของเกสต์ → ContextFixer)
 *   2. Application.attachBaseContext(guestCtx)
 *   3. ContentProvider.attachInfo + onCreate  (packer หลายตัว init ที่ชั้นนี้ — ตามที่
 *      AetherEngine §"boot guest providers — packer (เช่น Deku*) init ที่ชั้น provider ก่อน Application เสมอ")
 *   4. Application.onCreate()
 *
 * ทุกขั้นเขียน MiniLog เพื่อให้พิสูจน์ได้ว่าติดที่ชั้นไหน (ไม่รายงานความสำเร็จเท็จ)
 */
public final class GuestRuntime {

    private static final String TAG = "AetherPort";

    private static volatile boolean sBooted;
    private static volatile Application sGuestApp;

    private GuestRuntime() {}

    public static boolean isBooted() { return sBooted; }
    public static Application guestApplication() { return sGuestApp; }

    /** @return null เมื่อสำเร็จ · ข้อความ error เมื่อล้ม */
    public static synchronized String boot(Context ctx) {
        if (sBooted) return null;
        if (!GuestIdentity.isStubProcess(ctx)) return "runtime:refuse-in-main-process";
        if (!GuestIdentity.isPrepared()) return "runtime:guest-not-prepared";

        String pkg = GuestIdentity.guestPkg();
        java.io.File session = MiniLog.lastSession();

        // 1) Context ของ process → ของเกสต์
        ContextFixer.fixContext(ctx.getApplicationContext());
        MiniLog.stage(session, "[runtime] context fixed → " + pkg);

        GuestManifest gm;
        try {
            gm = GuestManifest.parse(
                    SandboxStore.guestApk(ctx, pkg).getAbsolutePath());
        } catch (Throwable t) {
            MiniLog.stage(session, "[runtime] manifest parse failed: " + t);
            return "runtime:manifest-failed:" + t;
        }

        ApplicationInfo ai = GuestIdentity.guestAppInfo();
        Context guestCtx = ctx.getApplicationContext();

        // 2) Application.attachBaseContext
        Application app = null;
        if (gm.applicationName != null && !gm.applicationName.isEmpty()) {
            String full = gm.applicationName.startsWith(".")
                    ? pkg + gm.applicationName : gm.applicationName;
            try {
                Class<?> cls = GuestIdentity.guestLoader().loadClass(full);
                app = (Application) cls.getDeclaredConstructor().newInstance();
                // attachBaseContext เป็น protected ของ ContextWrapper (ไม่ใชmethod ของ Context)
                Method m = android.content.ContextWrapper.class
                        .getDeclaredMethod("attachBaseContext", Context.class);
                m.setAccessible(true);
                m.invoke(app, guestCtx);
                MiniLog.stage(session, "[runtime] application attached: " + full);
            } catch (Throwable t) {
                MiniLog.stage(session, "[runtime] application attach failed (" 
                        + gm.applicationName + "): " + t);
                app = null;
            }
        } else {
            MiniLog.stage(session, "[runtime] application class: (none declared)");
        }
        sGuestApp = app;

        // 3) providers (packer init ที่ชั้นนี้)
        bootProviders(gm, guestCtx, ai, pkg, session);

        // 4) Application.onCreate
        if (app != null) {
            try {
                app.onCreate();
                MiniLog.stage(session, "[runtime] application onCreate ✓");
            } catch (Throwable t) {
                MiniLog.stage(session, "[runtime] application onCreate failed: " + t);
                return "runtime:app-oncreate-failed:" + t;
            }
        }

        sBooted = true;
        MiniLog.stage(session, "[runtime] guest runtime booted (" + pkg + ")");
        return null;
    }

    private static void bootProviders(GuestManifest gm, Context ctx,
                                      ApplicationInfo ai, String pkg, java.io.File session) {
        List<String> names = gm.providerNames;
        if (names == null || names.isEmpty()) {
            MiniLog.stage(session, "[runtime] providers: none");
            return;
        }
        int ok = 0;
        for (int i = 0; i < names.size(); i++) {
            String raw = names.get(i);
            String full = raw.startsWith(".") ? pkg + raw : raw;
            String auth = i < gm.providerAuthorities.size()
                    ? gm.providerAuthorities.get(i) : null;
            try {
                Class<?> cls = GuestIdentity.guestLoader().loadClass(full);
                Object obj = cls.getDeclaredConstructor().newInstance();
                if (!(obj instanceof ContentProvider)) {
                    MiniLog.stage(session, "[runtime] provider skip (not a provider): " + full);
                    continue;
                }
                ProviderInfo info = new ProviderInfo();
                info.name = full;
                info.authority = auth;
                info.packageName = pkg;
                info.applicationInfo = ai;
                ContentProvider provider = (ContentProvider) obj;
                provider.attachInfo(ctx, info);
                provider.onCreate();
                ok++;
                MiniLog.stage(session, "[runtime] provider ✓ " + full);
            } catch (Throwable t) {
                MiniLog.stage(session, "[runtime] provider skip " + full + ": " + t);
            }
        }
        MiniLog.stage(session, "[runtime] providers booted: " + ok + "/" + names.size());
    }
}
