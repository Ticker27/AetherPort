package com.virtualapp.mini.container;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * NativeEnv — port ของ AetherEngine helper.Native.setEnv สำหรับ setenv() เท่านั้น
 *
 * ต้นทางใช้ libaether.so (JNI) setenv; ที่นี่ไม่พึ่ง native lib ของ AetherEngine
 * เพราะแอปเราต้อง build .so เอง — ใช้ android.system.Os.setenv (@hide, public signature
 * ใน AOSP) ผ่าน reflection แทน, fallback libcore.io.Libcore.os
 *
 * ใช้กับ: เกสต์ Unity/IL2CPP ที่ ctor chain อ่าน getenv("HOME")
 */
public final class NativeEnv {

    private static final String TAG = "AetherPort";

    private NativeEnv() {}

    public static boolean setEnv(String name, String value) {
        // path 1: android.system.Os.setenv(String,String,boolean) — @hide but public static
        try {
            Class<?> os = Class.forName("android.system.Os");
            Method m = os.getDeclaredMethod("setenv", String.class, String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(null, name, value, true);
            Log.i(TAG, "setenv(" + name + ") via Os");
            return true;
        } catch (Throwable ignored) {}

        // path 2: libcore.io.Libcore.os (ILibcore.setenv)
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Object os = libcore.getField("os").get(null);
            Method m = os.getClass().getMethod("setenv", String.class, String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(os, name, value, true);
            Log.i(TAG, "setenv(" + name + ") via Libcore.os");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setenv(" + name + ") unavailable: " + t);
            return false;
        }
    }
}
