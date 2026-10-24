package com.virtualapp.mini.exec;

import android.content.Context;

import com.virtualapp.mini.hook.HCallbackProxy;
import com.virtualapp.mini.hook.IActivityManagerProxy;
import com.virtualapp.mini.hook.IPackageManagerProxy;
import com.virtualapp.mini.hook.InstrumentationProxy;

/**
 * HookManager — ติดตั้ง hook ตามลำดับที่ถูกต้อง (พอร์ตแนวคิด InvocationStubManager ของ VirtualApp)
 *
 * ลำดับสำคัญมาก:
 *   main process : ต้องการแค่ AMS proxy
 *                  (เพื่อให้ intent ที่ชี้ไป component ของเกสต์ถูกเปลี่ยนเป็น stub)
 *   :pN process  : ต้องการทั้งชุด — และต้องติดตั้ง "ก่อน" activity แรกของ process จะถูกสร้าง
 *                  จึงเรียกจาก MiniApp.attachBaseContext ไม่ใช่ onCreate
 *
 * ห้ามเขียนตัวตนเกสต์ใน main process — GuestIdentity.adoptProcessIdentity จะ throw ถ้าทำ
 */
public final class HookManager {

    private static final String TAG = "AetherPort";

    private static volatile boolean sMainInstalled;
    private static volatile boolean sGuestInstalled;
    private static volatile boolean sHiddenApiTried;

    private HookManager() {}

    /**
     * ปลดล็อก hidden API (Android 9+) — จำเป็นก่อน reflect field ของ framework
     * เทคนิคเดียวกับ GuestLauncher ของต้นทาง (double-reflection ผ่าน Class.forName/getDeclaredMethod)
     * ถ้าล้มไม่เป็นไร — hook จะรายงาน proxied=false แทนการ crash
     */
    private static synchronized void tryHiddenApiBypass() {
        if (sHiddenApiTried) return;
        sHiddenApiTried = true;
        try {
            java.lang.reflect.Method forName = Class.class.getDeclaredMethod("forName", String.class);
            java.lang.reflect.Method getDeclaredMethod =
                    Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            java.lang.reflect.Method getRuntime = (java.lang.reflect.Method)
                    getDeclaredMethod.invoke(vmRuntime, "getRuntime", (Object) null);
            Object runtime = getRuntime.invoke(null);
            java.lang.reflect.Method setExemptions = (java.lang.reflect.Method)
                    getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions",
                            (Object) new Class[]{String[].class});
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            android.util.Log.i(TAG, "hiddenapi exemptions set");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "hiddenapi bypass unavailable: " + t);
        }
    }

    /** เรียกในทุก process — ปลอดภัยที่จะเรียกซ้ำ */
    public static void installForProcess(Context ctx) {
        boolean stubProcess = GuestIdentity.isStubProcess(ctx);
        if (stubProcess) {
            installGuestHooks(ctx);
        } else {
            installMainHooks(ctx);
        }
    }

    private static void installMainHooks(Context ctx) {
        if (sMainInstalled) return;
        sMainInstalled = true;
        tryHiddenApiBypass();
        sAms = IActivityManagerProxy.install(ctx);
        android.util.Log.i(TAG, "main process hooks: ams=" + sAms);
    }

    private static void installGuestHooks(Context ctx) {
        if (sGuestInstalled) return;
        sGuestInstalled = true;
        tryHiddenApiBypass();

        // 1) ActivityManager — เปลี่ยน intent ของเกสต์เป็น stub
        sAms = IActivityManagerProxy.install(ctx);
        // 2) PackageManager — ตอบคำถามเรื่อง package ของเกสต์
        sPms = IPackageManagerProxy.install(ctx);
        // 3) Instrumentation — สร้าง Activity จาก guest loader
        sInstr = InstrumentationProxy.install(ctx);
        // 4) HCallback — สลับ activityInfo ก่อน LAUNCH_ACTIVITY ถูกประมวลผล
        sHcb = HCallbackProxy.install();
        boolean ams = sAms, pms = sPms, instr = sInstr, hcb = sHcb;
        // 5) ตัวตน process → เกสต์ (เฉพาะ :pN)
        try {
            if (GuestIdentity.isPrepared()) GuestIdentity.adoptProcessIdentity(ctx);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "adoptProcessIdentity: " + t);
        }

        android.util.Log.i(TAG, "guest process hooks: ams=" + ams + " pms=" + pms
                + " instr=" + instr + " hcb=" + hcb);
    }

    /** สถานะ hook ที่ติดตั้งจริง — ใช้พิสูจน์ว่า hook ติด ไม่ใช่สมมติ */
    public static String status() {
        return "process=" + (sGuestInstalled ? "guest" : (sMainInstalled ? "main" : "uninitialised"))
                + " hiddenApi=" + (sHiddenApiTried ? "tried" : "no")
                + " ams=" + sAms + " pms=" + sPms + " instr=" + sInstr + " hcb=" + sHcb;
    }

    private static volatile boolean sAms, sPms, sInstr, sHcb;

    /** เรียกหลังผูกเกสต์ใน process นี้แล้ว เพื่อเขียน Context ของ Application */
    public static void fixApplicationContext(Context ctx) {
        if (!GuestIdentity.isStubProcess(ctx)) return;
        ContextFixer.fixContext(ctx.getApplicationContext());
    }
}
