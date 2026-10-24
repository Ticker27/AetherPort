package com.virtualapp.mini.container;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RunOffMain — port ของ AetherEngine util/RunOffMain.java (generic ตามต้นทาง)
 *
 * การ copy APK ขนาด 100-200MB หรือ DexClassLoader หลายสิบ MB บน main thread ทำให้ ANR —
 * รันบน worker แล้ว join พร้อม timeout; หมดเวลา → คืนค่า fallback ที่ผู้เรียกกำหนด
 */
public final class RunOffMain {

    private RunOffMain() {}

    public static <T> T run(Callable<T> task, long timeoutMs, T timeoutValue, String threadName) {
        final AtomicReference<T> result = new AtomicReference<>(null);
        final AtomicReference<Throwable> error = new AtomicReference<>(null);
        Thread t = new Thread(() -> {
            try {
                result.set(task.call());
            } catch (Throwable e) {
                error.set(e);
            }
        }, threadName != null ? threadName : "off-main");
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) return timeoutValue;
        T r = result.get();
        if (r == null && error.get() != null) {
            android.util.Log.w("AetherPort", "RunOffMain(" + threadName + ") failed: " + error.get());
        }
        return r != null ? r : timeoutValue;
    }

    /** overload สำหรับงานที่คืน String (ใช้ใน ContainerCore) */
    public static String runString(Callable<String> task, long timeoutMs, String timeoutMsg, String threadName) {
        return run(task, timeoutMs, timeoutMsg, threadName);
    }
}
