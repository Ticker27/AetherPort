package com.virtualapp.mini.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.text.TextUtils;

import com.virtualapp.mini.exec.ComponentResolver;
import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.Reflect;
import com.virtualapp.mini.model.StubActivityRecord;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * IActivityManagerProxy — IActivityManagerSingleton.mInstance → dynamic proxy
 * (พอร์ตแนวทาง VirtualApp `client/hook/proxies/am/MethodProxies$StartActivity`)
 *
 * เหตุผลเชิงกลไก:
 *   component ของเกสต์ไม่มีใน PMS ของเครื่อง → ถ้าส่ง intent ของเกสต์เข้า AMS ตรง ๆ
 *   จะได้ ActivityNotFoundException ทันที
 *   → proxy เปลี่ยน component เป็น StubActivity$C0 (ซึ่งประกาศใน manifest จริง)
 *     แล้วยัดอินเทนต์เดิมลง extras ตามคีย์ `_VA_|_intent_`
 *   → ใน :p0, HCallback แทน activityInfo เป็นของเกสต์ → framework สร้างคลาสเกมจริง
 *
 * ครอบ method ที่ขึ้นต้นด้วย "startActivity", "startService", "stopService",
 * "bindService", "getIntentSender" — signature ต่างกันตาม API จึงจับด้วยชื่อ + ชนิด arg
 */
public final class IActivityManagerProxy implements InvocationHandler {

    private static final String TAG = "AetherPort";

    private final Object mBase;
    private final Context mHost;

    public IActivityManagerProxy(Object base, Context host) {
        mBase = base;
        mHost = host;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (args != null && GuestIdentity.isPrepared()) {
            if (name.startsWith("startActivity")) {
                rewriteActivityIntent(method, args);
            } else if (name.equals("startService") || name.equals("bindService")
                    || name.equals("stopService")) {
                rewriteServiceIntent(args);
            }
        } else {
            if (args != null) pruneGuestPackagesOnLeave(args);
        }
        return method.invoke(mBase, args);
    }

    /** แก้ args[0] (Intent) ให้ชี้ stub พร้อม stash */
    private void rewriteActivityIntent(Method method, Object[] args) {
        Intent intent = null;
        int idx = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Intent) { intent = (Intent) args[i]; idx = i; break; }
            if (args[i] instanceof String && i + 1 < args.length && args[i + 1] instanceof Intent) {
                intent = (Intent) args[i + 1]; idx = i + 1; break;
            }
        }
        if (intent == null || idx < 0) return;

        // กันลูป: อินเทนต์ที่มี stash อยู่แล้ว = ยิงซ้ำจาก StubActivity → ปล่อยผ่าน
        if (intent.hasExtra(StubActivityRecord.KEY_INTENT)) return;

        String pkg = GuestIdentity.guestPkg();
        String className = null;
        if (intent.getComponent() != null) {
            className = intent.getComponent().getClassName();
        }
        if (className == null) return;      // implicit intent → ปล่อยให้ระบบจัดการ

        ActivityInfo info = ComponentResolver.resolveActivityInfo(mHost, pkg, className);
        if (info == null) {
            android.util.Log.w(TAG, "unresolved guest activity: " + className);
            return;
        }

        Intent stub = GuestIdentity.makeStubIntent(mHost, intent, info, 0);
        args[idx] = stub;
        android.util.Log.i(TAG, "redirect " + className + " → " + stub.getComponent().flattenToShortString());
    }

    private void rewriteServiceIntent(Object[] args) {
        Intent intent = null;
        int idx = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Intent) { intent = (Intent) args[i]; idx = i; break; }
        }
        if (intent == null || idx < 0) return;
        if (intent.getComponent() == null) return;
        String pkg = GuestIdentity.guestPkg();
        if (!TextUtils.equals(intent.getComponent().getPackageName(), pkg)) return;
        android.util.Log.i(TAG, "service intent for guest (passthrough, stub service not declared yet): "
                + intent.getComponent().getClassName());
    }

    /** ใน main process: ลบชื่อ package ของเกสต์ออกจาก arg ที่ส่งเข้า AMS (กัน NameNotFound) */
    private void pruneGuestPackagesOnLeave(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                String s = (String) args[i];
                if (s.contains("virtualapp.mini.virt")) args[i] = mHost.getPackageName();
            }
        }
    }

    /** ติดตั้ง singleton proxy */
    public static boolean install(Context host) {
        try {
            Class<?> iface = Class.forName("android.app.IActivityManager");
            Object singleton = Reflect.get(Class.forName("android.app.ActivityManager"),
                    "IActivityManagerSingleton");
            if (singleton == null) {
                android.util.Log.w(TAG, "IActivityManagerSingleton not found");
                return false;
            }
            Object base = Reflect.get(singleton, "mInstance");
            if (base == null) {
                android.util.Log.w(TAG, "mInstance null (setupApplication not called yet?)");
                return false;
            }
            if (java.lang.reflect.Proxy.isProxyClass(base.getClass())) {
                android.util.Log.i(TAG, "IActivityManager already proxied");
                return true;
            }
            IActivityManagerProxy handler = new IActivityManagerProxy(base, host);
            ClassLoader cl = iface.getClassLoader();
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{iface}, handler);
            boolean ok = Reflect.set(singleton, "mInstance", proxy);
            android.util.Log.i(TAG, "IActivityManager proxied=" + ok);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "install IActivityManagerProxy failed", t);
            return false;
        }
    }

    /** token ของ activity ปัจจุบัน — AMS proxy บาง signature ต้องใช้ */
    public static IBinder activityToken(android.app.Activity a) {
        Object v = Reflect.get(a, "mToken");
        return v instanceof IBinder ? (IBinder) v : null;
    }
}
