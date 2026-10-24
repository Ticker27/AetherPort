package com.virtualapp.mini.container;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PluginLoader — โหลดปลั๊กอินเข้า process ของเกสต์ (parity: ขั้นที่ KOS เอา plugin_<epoch>.apk ไปทำงานใน VM)
 *
 * ลำดับที่ทำ (เรียงตามหลักฐาน KOS):
 *   1) อ่าน metadata จาก assets/aether-plugin.json (ถ้ามี): {"entry":"com.x.Entry","abi":"arm64-v8a","minApi":28}
 *   2) DexClassLoader(plugin_<epoch>.apk, oatDir, libDir, hostLoader)  ← oatDir = workspace/oat/arm64
 *   3) System.load() ไลบรารีที่แตกไว้ใน workspace/lib (เรียงตามชื่อ, ข้ามที่โหลดไม่ได้)
 *   4) เรียก entry: static init(Context) ถ้ามี ไม่งั้น ctor(Context) ไม่งั้นข้าม (module แบบ .so ล้วน)
 *   5) คืนสถานะสั้น ๆ เพื่อ log ลง session (LogDump) — ไม่ throw ออกไปข้างนอก
 *
 * หมายเหตุ: ปลั๊กอินเป็น APK ทั่วไป (classes.dex + lib/arm64-v8a/*.so) — ไม่ผูกกับระบบ login key
 */
public final class PluginLoader {

    private static final String TAG = "AetherPort";
    public static final String DEFAULT_ENTRY = "com.aether.plugin.Entry";
    private static final String META_ASSET = "aether-plugin.json";

    private PluginLoader() {}

    /** bridge ที่ส่งให้ปลั๊กอิน (host services) */
    public static final class Bridge {
        public final Context ctx;
        public final String pkg;
        public final ClassLoader guestLoader;
        public final File workspace;

        Bridge(Context ctx, String pkg, ClassLoader guestLoader, File workspace) {
            this.ctx = ctx;
            this.pkg = pkg;
            this.guestLoader = guestLoader;
            this.workspace = workspace;
        }

        /** เรียก host command ตาม code table เดียวกับ UI ("0".."P")
         *  ต้นทาง: com.aether.CommandRouter.dispatch — ในโปรเจกต์นี้ไม่มี Flutter/Dart channel
         *  จึงเป็น hook ที่เสียบได้: GuestHooks.hostCommand (ค่าเริ่มต้น = ไม่มีคำสั่ง, คืน "") */
        public String callHost(String code, Object arg) {
            Object r = GuestHooks.dispatchHostCommand(ctx, code, arg);
            return r == null ? "" : r.toString();
        }

        public void log(String msg) {
            android.util.Log.i(TAG, "[plugin] " + msg);
            MiniLog.engine(ctx, "[plugin] " + msg);
        }
    }

    /** ผลลัพธ์: loaded=false + reason เมื่อโหลดไม่ได้ (ไม่ทำให้เกสต์ล้ม) */
    public static final class Result {
        public final boolean loaded;
        public final String entry;
        public final int libs;
        public final String reason;

        Result(boolean loaded, String entry, int libs, String reason) {
            this.loaded = loaded;
            this.entry = entry;
            this.libs = libs;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (loaded ? "loaded" : "skipped")
                    + " entry=" + (entry == null ? "-" : entry)
                    + " libs=" + libs
                    + (reason == null ? "" : " (" + reason + ")");
        }
    }

    public static Result load(Context ctx, String pkg, ClassLoader guestLoader) {
        return RunOffMain.run(() -> loadInner(ctx, pkg, guestLoader),
                120000L, new Result(false, null, 0, "load-timeout"), "plugin-load");
    }

    private static Result loadInner(Context ctx, String pkg, ClassLoader guestLoader) {
        File ws = PluginInstaller.workspace(ctx, pkg);
        File apk = newestPluginApk(ws);
        if (apk == null) return new Result(false, null, 0, "no-plugin-apk");

        File libDir = new File(ws, "lib");
        File oatDir = new File(new File(ws, "oat"), "arm64");
        if (!oatDir.exists()) oatDir.mkdirs();

        Bridge bridge = new Bridge(ctx, pkg, guestLoader, ws);
        String entryName = DEFAULT_ENTRY;
        int minApi = 28;
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry(META_ASSET);
            if (e != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (InputStream in = zf.getInputStream(e)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                }
                JSONObject meta = new JSONObject(bos.toString("UTF-8"));
                entryName = meta.optString("entry", DEFAULT_ENTRY);
                minApi = meta.optInt("minApi", 28);
                bridge.log("meta entry=" + entryName + " minApi=" + minApi);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "plugin meta: " + t);
        }

        // 2) class loader ของปลั๊กอิน (oat ไปลง workspace/oat/arm64)
        ClassLoader pluginLoader;
        try {
            pluginLoader = new dalvik.system.DexClassLoader(
                    apk.getAbsolutePath(), oatDir.getAbsolutePath(), libDir.getAbsolutePath(),
                    PluginLoader.class.getClassLoader());
        } catch (Throwable t) {
            return new Result(false, entryName, 0, "dexloader-fail:" + t);
        }

        // 3) โหลด native ที่แตกไว้ (เรียงชื่อเพื่อให้ผลคงที่)
        int libs = 0;
        String[] names = libDir.list();
        if (names != null) {
            java.util.Arrays.sort(names);
            for (String n : names) {
                if (!n.endsWith(".so")) continue;
                try {
                    System.load(new File(libDir, n).getAbsolutePath());
                    libs++;
                    bridge.log("native loaded: " + n);
                } catch (Throwable t) {
                    bridge.log("native skip: " + n + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }

        // 4) entry point (ถ้ามี)
        try {
            Class<?> c = pluginLoader.loadClass(entryName);
            boolean invoked = false;
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("init", Context.class);
                m.setAccessible(true);
                m.invoke(null, ctx);
                invoked = true;
            } catch (NoSuchMethodException ignored) {
            }
            if (!invoked) {
                try {
                    java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(Context.class);
                    ctor.setAccessible(true);
                    Object inst = ctor.newInstance(ctx);
                    try {
                        java.lang.reflect.Method b = c.getDeclaredMethod("bind", Object.class);
                        b.setAccessible(true);
                        b.invoke(inst, bridge);
                    } catch (NoSuchMethodException ignored) {
                    }
                    invoked = true;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (invoked) {
                bridge.log("entry invoked: " + entryName);
                return new Result(true, entryName, libs, null);
            }
            return new Result(false, entryName, libs, "no-entry-api(module-libs-only)");
        } catch (ClassNotFoundException e) {
            return new Result(false, entryName, libs, "entry-not-found(module-libs-only)");
        } catch (Throwable t) {
            return new Result(false, entryName, libs, "entry-fail:" + t);
        }
    }

    /** เอา plugin_<epoch>.apk ใหม่สุดใน workspace */
    public static File newestPluginApk(File ws) {
        File[] all = ws.listFiles((d, n) -> n.startsWith("plugin_") && n.endsWith(".apk"));
        if (all == null || all.length == 0) return null;
        List<File> l = new ArrayList<>();
        java.util.Collections.addAll(l, all);
        File best = l.get(0);
        for (File f : l) {
            String a = f.getName().substring(7, f.getName().length() - 4);
            String b = best.getName().substring(7, best.getName().length() - 4);
            long av = parseLongSafe(a);
            long bv = parseLongSafe(b);
            if (av > bv) best = f;
        }
        return best;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }
}
