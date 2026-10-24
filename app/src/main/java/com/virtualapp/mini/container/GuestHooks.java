package com.virtualapp.mini.container;

import android.content.Context;

/**
 * GuestHooks — จุดต่อ (seam) ของสามส่วนที่ AetherEngine มีแยกจาก boot chain
 *
 *  1. ensurePlugin         → PluginInstaller.ensurePlugin      (cache → workspace → loader_ → vm config)
 *  2. loadPlugin           → PluginLoader.load                 (DexClassLoader + System.load + entry/bridge)
 *  3. installFakeFramework → fake/hook/HookManager.install     (IAMS/IPMS/Instrumentation/ mH)
 *
 * ค่าเริ่มต้น (หลังพอร์ต): 1 และ 2 = implementation จริง, 3 = ยังไม่พอร์ต (คืน false)
 * ทุกตัว override ได้ด้วย static field เดียว — ไม่ต้องแก้ GuestLauncher
 *
 *   GuestHooks.fakeFramework = (ctx, pkg, loader) -> { HookManager.install(ctx, pkg, loader); return true; };
 */
public final class GuestHooks {

    public interface PluginInstaller {
        String ensurePlugin(Context ctx, String pkg, long timeoutMs);
    }

    public interface PluginLoader {
        String load(Context ctx, String pkg, ClassLoader guestLoader);
    }

    public interface FakeFramework {
        boolean install(Context ctx, String pkg, ClassLoader guestLoader);
    }

    /** code table ของ host (แทน CommandRouter.dispatch ของต้นทาง) — ปลั๊กอินเรียกผ่าน Bridge.callHost */
    public interface HostCommand {
        Object dispatch(Context ctx, String code, Object arg);
    }

    /** ค่าเริ่มต้น = พอร์ตจริงจาก AetherEngine container/PluginInstaller */
    public static volatile PluginInstaller pluginInstaller =
            com.virtualapp.mini.container.PluginInstaller::ensurePlugin;

    /** ค่าเริ่มต้น = พอร์ตจริงจาก AetherEngine container/PluginLoader */
    public static volatile PluginLoader pluginLoader =
            (ctx, pkg, loader) ->
                    com.virtualapp.mini.container.PluginLoader.load(ctx, pkg, loader).toString();

    /** ยังไม่พอร์ตในโปรเจกต์นี้ — false = ไม่ติดตั้ง */
    public static volatile FakeFramework fakeFramework = null;

    /** ไม่มี host command ในโปรเจกต์นี้ (ไม่ได้พอร์ต CommandRouter/Dart channel) */
    public static volatile HostCommand hostCommand = null;

    private GuestHooks() {}

    public static String ensurePlugin(Context ctx, String pkg, long timeoutMs) {
        PluginInstaller pi = pluginInstaller;
        if (pi == null) return "skipped:no-plugin-installer";
        try {
            return pi.ensurePlugin(ctx, pkg, timeoutMs);
        } catch (Throwable t) {
            return "error:" + t;
        }
    }

    public static String loadPlugin(Context ctx, String pkg, ClassLoader guestLoader) {
        PluginLoader pl = pluginLoader;
        if (pl == null) return "skipped:no-plugin-loader";
        try {
            return pl.load(ctx, pkg, guestLoader);
        } catch (Throwable t) {
            return "error:" + t;
        }
    }

    public static boolean installFakeFramework(Context ctx, String pkg, ClassLoader guestLoader) {
        FakeFramework ff = fakeFramework;
        if (ff == null) return false;
        return ff.install(ctx, pkg, guestLoader);
    }

    public static Object dispatchHostCommand(Context ctx, String code, Object arg) {
        HostCommand hc = hostCommand;
        if (hc == null) return "";
        try {
            return hc.dispatch(ctx, code, arg);
        } catch (Throwable t) {
            return "error:" + t;
        }
    }
}
