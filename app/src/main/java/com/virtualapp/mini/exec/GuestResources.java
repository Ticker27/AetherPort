package com.virtualapp.mini.exec;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import com.virtualapp.mini.container.SandboxStore;

import java.io.File;
import java.lang.reflect.Method;

/**
 * GuestResources — Resources ที่อ่านจาก APK ใน sandbox (base + splits)
 *
 * ทำไมจำเป็น (ช่องที่ทำให้เกม crash แม้ activity ถูกสร้างแล้ว):
 *   Activity ที่ framework สร้างให้ถูก attach ด้วย ContextImpl ของ host
 *   → getResources() คืน Resources ของ host
 *   → onCreate ของเกมเรียก setContentView(R.layout.unity_shell) ด้วย resource id ของเกม
 *     แต่ Resources เป็นของ host → Resources$NotFoundException ทันที
 *
 * วิธีแก้แบบ VirtualApp: สร้าง Resources จาก AssetManager ที่ addAssetPath(apk ทุกไฟล์)
 * แล้วเขียนทับ Activity.mResources ใน callActivityOnCreate ก่อน onCreate
 */
public final class GuestResources {

    private static final String TAG = "AetherPort";

    private static volatile Resources sCache;
    private static volatile String sCachePkg;

    private GuestResources() {}

    public static synchronized Resources get(Context ctx, String pkg) {
        if (sCache != null && pkg.equals(sCachePkg)) return sCache;
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);

            // base + splits ทั้งหมด (ทรัพยากรของเกมอาจอยู่ใน split config)
            int cookies = 0;
            Object c = addAssetPath.invoke(am, SandboxStore.guestApk(ctx, pkg).getAbsolutePath());
            if (c instanceof Integer && (Integer) c != 0) cookies++;

            File[] apks = SandboxStore.guestDir(ctx, pkg)
                    .listFiles((d, n) -> n.endsWith(".apk"));
            if (apks != null) {
                for (File f : apks) {
                    if (f.getName().equals("base.apk")) continue;
                    Object r = addAssetPath.invoke(am, f.getAbsolutePath());
                    if (r instanceof Integer && (Integer) r != 0) cookies++;
                }
            }
            if (cookies == 0) {
                android.util.Log.w(TAG, "addAssetPath returned 0 for " + pkg);
                return null;
            }

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            Resources res = new Resources(am, dm, ctx.getResources().getConfiguration());
            sCache = res;
            sCachePkg = pkg;
            android.util.Log.i(TAG, "guest resources ready: cookies=" + cookies + " pkg=" + pkg);
            return res;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "guest resources failed", t);
            return null;
        }
    }

    /** เขียน Resources ของเกสต์ลง Activity/Context ที่ framework เพิ่งสร้าง (ก่อน onCreate) */
    public static void applyTo(android.app.Activity activity, Context ctx, String pkg) {
        Resources res = get(ctx, pkg);
        if (res == null) return;
        try {
            java.lang.reflect.Field f = android.content.ContextThemeWrapper.class
                    .getDeclaredField("mResources");
            f.setAccessible(true);
            f.set(activity, res);

            // theme ต้องสร้างใหม่จาก resources ของเกม ไม่งั้น theme id ของเกมจะไม่รู้จัก
            java.lang.reflect.Field t = android.content.ContextThemeWrapper.class
                    .getDeclaredField("mTheme");
            t.setAccessible(true);
            t.set(activity, null);

            Integer themeId = ComponentResolver.deferredTheme(
                    activity.getClass().getName());
            if (themeId != null && themeId != 0) {
                activity.setTheme(themeId);
            }
            android.util.Log.i(TAG, "resources applied to " + activity.getClass().getName()
                    + (themeId != null && themeId != 0 ? " theme=0x" + Integer.toHexString(themeId) : ""));
        } catch (Throwable e) {
            android.util.Log.w(TAG, "applyTo failed", e);
        }
    }
}
