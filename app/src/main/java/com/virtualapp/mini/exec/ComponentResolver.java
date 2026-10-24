package com.virtualapp.mini.exec;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.text.TextUtils;

import com.virtualapp.mini.container.SandboxStore;

/**
 * ComponentResolver — แทน `VirtualCore.resolveActivityInfo()` ของ VirtualApp
 *
 * ทำไมต้องมี: component ของเกสต์ (Activity/Service/Provider) ไม่มีอยู่ใน PMS ของเครื่อง
 * ทุกครั้งที่ framework หรือ proxy ต้องรู้ "activity นี้มี theme/orientation/launchMode อะไร"
 * ต้องตอบจาก APK ใน sandbox ไม่ใช่จากระบบ
 *
 * วิธีได้ข้อมูล: PackageManager.getPackageArchiveInfo(apk, GET_ACTIVITIES|GET_SERVICES|GET_PROVIDERS)
 * — parser ของ framework อ่าน binary AXML ให้เอง (ไม่ต้องเขียน parser ใหม่ และได้ field ครบ
 *   ทุกตัวที่ ActivityInfo ต้องการ ซึ่ง GuestManifest แบบ manual ของเดิมให้ไม่ได้)
 *
 * จุดสำคัญ: `processName` ของกิจกรรมทุกตัวถูกบังคับให้ชี้ :p0
 * เพราะ stub process มีจำกัด — เกมที่ประกาศ process หลายตัวจะถูกยุบเข้า stub เดียว
 * (VirtualApp ทำแบบเดียวกัน: map process → stub index)
 */
public final class ComponentResolver {

    private static final String TAG = "AetherPort";

    /** theme id ของเกสต์ — framework จะ setTheme ก่อนเราจะติดตั้ง guest Resources
     *  จึงต้อง zero ค่าใน ActivityInfo ที่ส่งให้ framework แล้วเอาไปใช้ทีหลัง */
    private static final java.util.Map<String, Integer> sThemes = new java.util.HashMap<>();

    private ComponentResolver() {}

    public static Integer deferredTheme(String className) {
        return sThemes.get(className);
    }

    private static PackageInfo archive(Context ctx, String pkg) {
        try {
            String apk = SandboxStore.guestApk(ctx, pkg).getAbsolutePath();
            return ctx.getPackageManager().getPackageArchiveInfo(apk,
                    PackageManager.GET_ACTIVITIES
                            | PackageManager.GET_SERVICES
                            | PackageManager.GET_PROVIDERS
                            | PackageManager.GET_RECEIVERS
                            | PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "archive(" + pkg + "): " + t);
            return null;
        }
    }

    /** ActivityInfo ของเกสต์ — packageName/processName ถูกเขียนให้เป็นของ container */
    public static ActivityInfo resolveActivityInfo(Context ctx, String pkg, String className) {
        if (TextUtils.isEmpty(className)) return null;
        PackageInfo pi = archive(ctx, pkg);
        if (pi == null || pi.activities == null) return null;

        String full = className.startsWith(".") ? pkg + className : className;
        for (ActivityInfo ai : pi.activities) {
            String n = ai.name.startsWith(".") ? pkg + ai.name : ai.name;
            if (!n.equals(full)) continue;
            ActivityInfo copy = new ActivityInfo(ai);
            copy.name = full;
            copy.packageName = pkg;
            copy.processName = ctx.getPackageName() + GuestIdentity.PROC_0;
            copy.applicationInfo = guestApplicationInfo(ctx, pkg, pi);
            // เก็บ theme ของเกมไว้ แล้ว zero ในสำเนา — กัน framework setTheme ด้วย id ของเกม
            // บน Resources ของ host (Resources$NotFoundException)
            if (ai.theme != 0) {
                sThemes.put(full, ai.theme);
                copy.theme = 0;
            }
            return copy;
        }
        return null;
    }

    /** launcher activity ตัวแรก (ตาม GuestManifest) → ActivityInfo เต็ม */
    public static ActivityInfo resolveLauncher(Context ctx, String pkg) {
        String launcher = null;
        try {
            launcher = com.virtualapp.mini.container.GuestManifest
                    .parse(SandboxStore.guestApk(ctx, pkg).getAbsolutePath()).launcherActivity;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "GuestManifest.parse: " + t);
        }
        if (launcher == null) {
            PackageInfo pi = archive(ctx, pkg);
            if (pi != null && pi.activities != null && pi.activities.length > 0) {
                launcher = pi.activities[0].name;
            }
        }
        if (launcher == null) return null;
        return resolveActivityInfo(ctx, pkg, launcher);
    }

    public static ServiceInfo resolveServiceInfo(Context ctx, String pkg, String className) {
        PackageInfo pi = archive(ctx, pkg);
        if (pi == null || pi.services == null) return null;
        for (ServiceInfo si : pi.services) {
            String n = si.name.startsWith(".") ? pkg + si.name : si.name;
            if (n.equals(className)) {
                ServiceInfo copy = new ServiceInfo(si);
                copy.packageName = pkg;
                copy.processName = ctx.getPackageName() + GuestIdentity.PROC_0;
                copy.applicationInfo = guestApplicationInfo(ctx, pkg, pi);
                return copy;
            }
        }
        return null;
    }

    public static ProviderInfo resolveProviderInfo(Context ctx, String pkg, String authority) {
        PackageInfo pi = archive(ctx, pkg);
        if (pi == null || pi.providers == null) return null;
        for (ProviderInfo p : pi.providers) {
            if (authority != null && authority.equals(p.authority)) {
                ProviderInfo copy = new ProviderInfo(p);
                copy.packageName = pkg;
                copy.applicationInfo = guestApplicationInfo(ctx, pkg, pi);
                return copy;
            }
        }
        return null;
    }

    /** ApplicationInfo ของเกสต์ที่ชี้ทุก path เข้า sandbox */
    public static ApplicationInfo guestApplicationInfo(Context ctx, String pkg, PackageInfo pi) {
        ApplicationInfo src = pi != null && pi.applicationInfo != null
                ? pi.applicationInfo : new ApplicationInfo();
        ApplicationInfo ai = new ApplicationInfo(src);
        String apk = SandboxStore.guestApk(ctx, pkg).getAbsolutePath();
        ai.packageName = pkg;
        ai.sourceDir = apk;
        ai.publicSourceDir = apk;
        ai.nativeLibraryDir = SandboxStore.guestLibDir(ctx, pkg).getAbsolutePath();
        ai.dataDir = SandboxStore.guestUserDataDir(ctx, pkg).getAbsolutePath();
        ai.processName = ctx.getPackageName() + GuestIdentity.PROC_0;
        ai.uid = ctx.getApplicationInfo().uid;
        return ai;
    }
}
