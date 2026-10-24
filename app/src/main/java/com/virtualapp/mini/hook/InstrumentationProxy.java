package com.virtualapp.mini.hook;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.TextUtils;

import com.virtualapp.mini.exec.ContextFixer;
import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.Reflect;
import com.virtualapp.mini.model.StubActivityRecord;

/**
 * InstrumentationProxy — แทน ActivityThread.mInstrumentation
 * (พอร์ตแนวทาง VirtualApp `client/hook/delegate/AppInstrumentation` + InstrumentationDelegate)
 *
 * เหตุผลเชิงกลไก:
 *   framework สร้าง Activity ผ่าน `Instrumentation.newActivity(cl, className, intent)`
 *   โดย `cl` = LoadedApk.getClassLoader() ของ *host* → โหลดคลาสของเกมไม่ได้
 *   proxy จึงเปลี่ยนไปใช้ GuestIdentity.guestLoader() และแก้ Context ของ activity
 *   ให้เห็น package/resources ของเกสต์ทันทีหลัง attach ก่อน onCreate
 *
 * สำคัญ: ทุก method ต้อง delegate ไป base เพราะ ActivityThread เรียก mInstrumentation
 * เป็นจุดเดียวสำหรับ lifecycle ทั้งหมด (execStartActivity, callActivityOnCreate, …)
 */
public final class InstrumentationProxy extends Instrumentation {

    private static final String TAG = "AetherPort";

    private final Instrumentation mBase;
    /** Context ของ process (ส่งเข้ามาตอนติดตั้ง) — Instrumentation.getTargetContext() เป็น @hide */
    private final android.content.Context mCtx;

    public InstrumentationProxy(Instrumentation base, android.content.Context ctx) {
        this.mBase = base;
        this.mCtx = ctx;
    }

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent) {
        // เกสต์อาจยังไม่ถูกเตรียมใน process นี้ (process เพิ่งเกิดจาก stub intent)
        // → เตรียมตอนนี้ ก่อนสร้าง Activity (เป็นจุดแรกที่รู้ชื่อ package ของเกสต์)
        if (!GuestIdentity.isPrepared() && intent != null) {
            StubActivityRecord r = new StubActivityRecord(intent);
            if (r.info != null && r.info.packageName != null) {
                android.content.Context ctx = mCtx;
                if (ctx != null) {
                    String err = com.virtualapp.mini.exec.GuestLoader.prepare(ctx, r.info.packageName, false);
                    android.util.Log.i(TAG, "lazy prepare(" + r.info.packageName + ") → "
                            + (err == null ? "ok" : err));
                }
            }
        }
        if (GuestIdentity.isPrepared()) {
            String real = className;
            // ถ้า framework ยังถือชื่อ stub อยู่ → ถอดชื่อจริงจากซอง
            if (className != null && className.contains("StubActivity") && intent != null) {
                StubActivityRecord r = new StubActivityRecord(intent);
                if (r.info != null && r.info.name != null) real = r.info.name;
            }
            if (real != null && !real.contains("StubActivity")) {
                try {
                    Activity a = (Activity) GuestIdentity.guestLoader()
                            .loadClass(real).newInstance();
                    android.util.Log.i(TAG, "newActivity from guest loader: " + real);
                    return a;
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "guest newActivity failed for " + real, t);
                }
            }
        }
        return mBase.newActivity(cl, className, intent);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        if (GuestIdentity.isPrepared() && isGuestActivity(activity)) {
            // ลำดับสำคัญ: Resources ของเกมต้องพร้อมก่อน onCreate
            com.virtualapp.mini.exec.GuestResources.applyTo(
                    activity, mCtx, GuestIdentity.guestPkg());
            ContextFixer.fixContext(activity);
        }
        mBase.callActivityOnCreate(activity, icicle);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle,
                                     PersistableBundle persistentState) {
        if (GuestIdentity.isPrepared() && isGuestActivity(activity)) {
            com.virtualapp.mini.exec.GuestResources.applyTo(
                    activity, mCtx, GuestIdentity.guestPkg());
            ContextFixer.fixContext(activity);
        }
        mBase.callActivityOnCreate(activity, icicle, persistentState);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        if (GuestIdentity.isPrepared() && isGuestActivity(activity)) {
            ContextFixer.fixContext(activity);
        }
        mBase.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnStart(Activity activity) { mBase.callActivityOnStart(activity); }
    @Override
    public void callActivityOnRestart(Activity activity) { mBase.callActivityOnRestart(activity); }
    @Override
    public void callActivityOnPause(Activity activity) { mBase.callActivityOnPause(activity); }
    @Override
    public void callActivityOnStop(Activity activity) { mBase.callActivityOnStop(activity); }
    @Override
    public void callActivityOnDestroy(Activity activity) { mBase.callActivityOnDestroy(activity); }
    @Override
    public void callActivityOnSaveInstanceState(Activity activity, Bundle outState) {
        mBase.callActivityOnSaveInstanceState(activity, outState);
    }
    @Override
    public void callActivityOnRestoreInstanceState(Activity activity, Bundle savedState) {
        mBase.callActivityOnRestoreInstanceState(activity, savedState);
    }
    @Override
    public void callActivityOnNewIntent(Activity activity, Intent intent) {
        mBase.callActivityOnNewIntent(activity, intent);
    }
    @Override
    public void callApplicationOnCreate(Application app) { mBase.callApplicationOnCreate(app); }
    @Override
    public Context getContext() { return mBase.getContext(); }
    @Override
    public void setInTouchMode(boolean inTouch) { mBase.setInTouchMode(inTouch); }
    @Override
    public void waitForIdleSync() { mBase.waitForIdleSync(); }
    @Override
    public void runOnMainSync(Runnable runner) { mBase.runOnMainSync(runner); }

    private boolean isGuestActivity(Activity a) {
        return a != null && a.getClass().getClassLoader() == GuestIdentity.guestLoader();
    }

    /** ติดตั้ง: แทน ActivityThread.mInstrumentation */
    public static boolean install(android.content.Context ctx) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("currentActivityThread").invoke(null);
            Object base = Reflect.get(at, "mInstrumentation");
            if (base == null) return false;
            if (base instanceof InstrumentationProxy) return true;
            boolean ok = Reflect.set(at, "mInstrumentation",
                    new InstrumentationProxy((Instrumentation) base, ctx));
            android.util.Log.i(TAG, "mInstrumentation proxied=" + ok);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "install InstrumentationProxy failed", t);
            return false;
        }
    }
}
