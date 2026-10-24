package com.virtualapp.mini.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import com.virtualapp.mini.exec.ComponentResolver;
import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.Reflect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * IPackageManagerProxy — ActivityThread.sPackageManager → dynamic proxy
 * (พอร์ตแนวทาง VirtualApp `client/hook/proxies/pm/MethodProxies` + `BPackageManager`)
 *
 * เหตุผลเชิงกลไก:
 *   ชื่อ package ของเกสต์ (com.miniclip.eightballpool) ไม่มีใน PMS ของเครื่อง
 *   framework ถาม PMS ทุกครั้งที่ติดตั้ง/สร้าง activity/provider/ตรวจ permission
 *   → NameNotFoundException ทันทีที่เกมเรียก getPackageManager().getPackageInfo(ตัวเอง)
 *
 * proxy จึงตอบจาก sandbox แทน:
 *   getPackageInfo      → PackageInfo ที่สร้างจาก APK ใน sandbox
 *   getApplicationInfo  → ApplicationInfo ที่ path ชี้ sandbox
 *   getActivityInfo     → ActivityInfo เต็ม (theme/launchMode/orientation จาก AXML)
 *   getProviderInfo     → ProviderInfo ของเกสต์
 *   checkPermission     → GRANTED (เกมถือ permission ของตัวเองใน sandbox)
 */
public final class IPackageManagerProxy implements InvocationHandler {

    private static final String TAG = "AetherPort";

    private final Object mBase;
    private final Context mHost;

    public IPackageManagerProxy(Object base, Context host) {
        mBase = base;
        mHost = host;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        String pkg = GuestIdentity.guestPkg();

        if (GuestIdentity.isPrepared()) {
            if ("getPackageInfo".equals(name) || "getPackageInfoVersioned".equals(name)) {
                String asked = firstString(args);
                if (isGuest(asked, pkg)) {
                    PackageInfo forged = packageInfo(pkg);
                    if (forged != null) return forged;
                }
            }
            if ("getApplicationInfo".equals(name)) {
                String asked = firstString(args);
                if (isGuest(asked, pkg)) {
                    return ComponentResolver.guestApplicationInfo(mHost, pkg, packageInfo(pkg));
                }
            }
            if ("getActivityInfo".equals(name)) {
                ComponentName cn = firstComponent(args);
                if (cn != null && isGuest(cn.getPackageName(), pkg)) {
                    ActivityInfo ai = ComponentResolver.resolveActivityInfo(mHost, pkg, cn.getClassName());
                    if (ai != null) return ai;
                }
            }
            if ("getProviderInfo".equals(name)) {
                ComponentName cn = firstComponent(args);
                if (cn != null && isGuest(cn.getPackageName(), pkg)) {
                    ProviderInfo pi = ComponentResolver.resolveProviderInfo(mHost, pkg, null);
                    if (pi != null) return pi;
                }
            }
            if ("checkPermission".equals(name)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            if ("resolveIntent".equals(name) || "resolveService".equals(name)) {
                String asked = firstString(args);
                if (isGuest(asked, pkg)) {
                    ResolveInfo ri = new ResolveInfo();
                    android.util.Log.i(TAG, name + " for guest pkg " + asked + " → forged empty ResolveInfo");
                    return ri;
                }
            }
        }
        return method.invoke(mBase, args);
    }

    private PackageInfo packageInfo(String pkg) {
        try {
            String apk = com.virtualapp.mini.container.SandboxStore.guestApk(mHost, pkg)
                    .getAbsolutePath();
            PackageInfo pi = mHost.getPackageManager().getPackageArchiveInfo(apk,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                            | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS
                            | PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi == null) return null;
            pi.packageName = pkg;
            pi.applicationInfo = ComponentResolver.guestApplicationInfo(mHost, pkg, pi);
            return pi;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "packageInfo(" + pkg + "): " + t);
            return null;
        }
    }

    private static boolean isGuest(String asked, String pkg) {
        return asked != null && TextUtils.equals(asked, pkg);
    }

    private static String firstString(Object[] args) {
        if (args == null) return null;
        for (Object o : args) {
            if (o instanceof String) return (String) o;
        }
        return null;
    }

    private static ComponentName firstComponent(Object[] args) {
        if (args == null) return null;
        for (Object o : args) {
            if (o instanceof ComponentName) return (ComponentName) o;
        }
        return null;
    }

    /** ติดตั้ง: แทน ActivityThread.sPackageManager */
    public static boolean install(Context host) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object base = Reflect.get(atCls, "sPackageManager");
            if (base == null) {
                android.util.Log.w(TAG, "sPackageManager null");
                return false;
            }
            if (java.lang.reflect.Proxy.isProxyClass(base.getClass())) return true;
            Class<?> iface = Class.forName("android.content.pm.IPackageManager");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class[]{iface},
                    new IPackageManagerProxy(base, host));
            boolean ok = Reflect.set(atCls, "sPackageManager", proxy);
            android.util.Log.i(TAG, "IPackageManager proxied=" + ok);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "install IPackageManagerProxy failed", t);
            return false;
        }
    }
}
