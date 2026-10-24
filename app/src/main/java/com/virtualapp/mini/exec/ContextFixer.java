package com.virtualapp.mini.exec;

import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Field;

/**
 * ContextFixer — พอร์ตแนวคิดจาก VirtualApp `client/fixer/ContextFixer`
 *
 * VirtualApp ไม่ได้ subclass Context เพื่อเปลี่ยน package name (แบบที่ GuestContext เดิมทำ)
 * เพราะ ContextImpl ถูก framework ถืออยู่ภายใน Activity/Service/Application และ
 * framework เรียก method ของ ContextImpl ตรง ๆ (ไม่ผ่าน virtual dispatch ของ subclass)
 * การ subclass จึงไม่เปลี่ยนสิ่งที่ framework เห็น — ต้อง "เขียน field ของ ContextImpl" ทันที
 *
 * field ที่ต้องแก้ (ชื่อเดียวกับ AOSP):
 *   mBasePackageName / mPackageName / mOpPackageName   → ชื่อเกสต์
 *   mResources                                          → resources จาก APK เกสต์
 *   mPackageInfo (LoadedApk)                            → ของเกสต์ (ถ้ามี)
 *   mApplicationInfo.packageName                        → ชื่อเกสต์
 */
public final class ContextFixer {

    private static final String TAG = "AetherPort";

    private ContextFixer() {}

    public static void fixContext(Context context) {
        if (context == null || !GuestIdentity.isPrepared()) return;
        String pkg = GuestIdentity.guestPkg();
        try {
            Object impl = unwrap(context);
            if (impl == null) return;

            set(impl, "mBasePackageName", pkg);
            set(impl, "mPackageName", pkg);
            set(impl, "mOpPackageName", pkg);

            Object ai = getField(impl, "mApplicationInfo");
            if (ai instanceof android.content.pm.ApplicationInfo) {
                ((android.content.pm.ApplicationInfo) ai).packageName = pkg;
            }
            Object res = getField(impl, "mResources");
            if (res == null) {
                android.util.Log.w(TAG, "fixContext: no mResources to fix");
            }
            android.util.Log.i(TAG, "context fixed → " + pkg
                    + " impl=" + impl.getClass().getName());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "fixContext failed", t);
        }
    }

    /** เดินลงไปถึง ContextImpl ข้างในสุด */
    private static Object unwrap(Context ctx) {
        Context cur = ctx;
        for (int i = 0; i < 8 && cur instanceof ContextWrapper; i++) {
            Context base = ((ContextWrapper) cur).getBaseContext();
            if (base == null) break;
            cur = base;
        }
        // ถ้า cur ไม่ได้เป็น ContextWrapper แล้ว แสดงว่าเป็น ContextImpl
        if (cur instanceof ContextWrapper) return null;
        return cur;
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

    private static void set(Object o, String name, Object v) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, v);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "fix field " + name + ": " + t);
        }
    }
}
