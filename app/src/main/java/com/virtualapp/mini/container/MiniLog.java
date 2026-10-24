package com.virtualapp.mini.container;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MiniLog — port ของ AetherEngine util.LogDump (ตัดส่วน export/share ออก)
 *
 * ต้นทาง: session ต่อเกสต์ <pkg>-<ts>-0.txt · rotate 3 · stage lines
 * ที่นี่: เก็บ session file เดียวต่อการ launch + stage() เขียน timestamped line
 * (คงพฤติกรรม "รายงานขั้นที่ไปถึงจริง — ไม่เดาความสำเร็จ")
 */
public final class MiniLog {

    private static final String TAG = "AetherPort";
    private static final int ROTATE = 3;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static File sSession;

    private MiniLog() {}

    public static synchronized File startSession(Context ctx, String pkg) {
        try {
            Context app = ctx.getApplicationContext();
            File dir = new File(app.getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            rotate(dir, pkg);
            String name = pkg + "-" + System.currentTimeMillis() + "-0.txt";
            sSession = new File(dir, name);
            stage(sSession, "[session] start " + pkg);
            Log.i(TAG, "session: " + sSession.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "startSession: " + t);
        }
        return sSession;
    }

    public static File lastSession() { return sSession; }

    public static void stage(File f, String line) {
        Log.i(TAG, line);
        if (f == null) return;
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(TS.format(new Date()) + " " + line + "\n");
        } catch (Throwable ignored) {}
    }

    public static void engine(Context ctx, String line) { Log.i(TAG, line); }

    public static void endSession(File f, String line) {
        stage(f, "[session] end " + line);
    }

    private static void rotate(File dir, String pkg) {
        try {
            File[] all = dir.listFiles((d, n) -> n.startsWith(pkg + "-") && n.endsWith(".txt"));
            if (all == null || all.length < ROTATE) return;
            java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i <= all.length - ROTATE; i++) all[i].delete();
        } catch (Throwable ignored) {}
    }
}
