package com.virtualapp.mini.hook;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Message;

import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.Reflect;
import com.virtualapp.mini.model.StubActivityRecord;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

/**
 * HCallbackProxy — ActivityThread.mH.mCallback → dynamic proxy
 * (พอร์ตแนวทาง VirtualApp `client/hook/proxies/am/HCallbackStub`)
 *
 * ต้องรองรับ 2 ทาง เพราะ Android เปลี่ยนกลไกที่ API 28:
 *
 *   1) legacy  : H.LAUNCH_ACTIVITY (what=100)  msg.obj = ActivityClientRecord
 *                เขียน record.activityInfo / record.intent
 *   2) modern  : H.EXECUTE_TRANSACTION (what=159) msg.obj = ClientTransaction
 *                ทำได้เฉพาะกับ ActivityClientRecord (API < 29) / ClientTransaction callbacks (API ≥ 29)
 *                → error: "If you are seeing this message, you are likely using a hidden API"
 *                แก้ที่ตัว callback ลูก: LaunchActivityItem.mActivityInfo / mIntent
 *
 * รายละเอียดของ modern path (ตรวจจาก AOSP android/app/ClientTransaction.java + LaunchActivityItem.java):
 *   ClientTransaction.getCallbacks() → List<ClientTransactionItem>
 *   LaunchActivityItem มี field  mActivityInfo (ActivityInfo), mIntent (Intent)
 *   ⇒ สลับสองฟิลด์นี้ก่อน TransactionExecutor.execute()  → framework สร้างคลาสเกมจริง
 */
public final class HCallbackProxy implements InvocationHandler {

    private static final String TAG = "AetherPort";

    private static final int LAUNCH_ACTIVITY = 100;
    private static final int EXECUTE_TRANSACTION = 159;

    private final Handler.Callback mBase;

    public HCallbackProxy(Handler.Callback base) {
        mBase = base;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("handleMessage".equals(method.getName()) && args != null && args[0] instanceof Message) {
            try {
                onHandleMessage((Message) args[0]);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "HCallback swap failed", t);
            }
        }
        return mBase != null ? method.invoke(mBase, args) : Boolean.FALSE;
    }

    private void onHandleMessage(Message msg) {
        if (!GuestIdentity.isPrepared()) return;
        if (msg.what == LAUNCH_ACTIVITY) {
            swapRecord(msg.obj);
        } else if (msg.what == EXECUTE_TRANSACTION) {
            swapTransaction(msg.obj);
        }
    }

    // ── path 1: ActivityClientRecord (what=100) ─────────────────

    private void swapRecord(Object record) {
        if (record == null) return;
        Intent intent = (Intent) Reflect.get(record, "intent");
        if (intent == null) return;
        StubActivityRecord r = new StubActivityRecord(intent);
        if (r.info == null) return;

        Reflect.set(record, "activityInfo", r.info);
        if (r.intent != null) {
            try {
                r.intent.setExtrasClassLoader(GuestIdentity.guestLoader());
            } catch (Throwable ignored) {}
            Reflect.set(record, "intent", r.intent);
        }
        android.util.Log.i(TAG, "HCallback[100]: activityInfo ← " + r.info.name);
    }

    // ── path 2: ClientTransaction (what=159) ────────────────────

    private void swapTransaction(Object tx) {
        if (tx == null) return;

        // 2a) บาง API ส่ง ActivityClientRecord ห่อมาใน obj
        if (Reflect.get(tx, "activityInfo") != null || Reflect.get(tx, "intent") != null) {
            swapRecord(tx);
            return;
        }

        // 2b) ClientTransaction: ไล่ callbacks หา LaunchActivityItem
        Object callbacksObj = null;
        try {
            Method m = tx.getClass().getMethod("getCallbacks");
            m.setAccessible(true);
            callbacksObj = m.invoke(tx);
        } catch (Throwable t) {
            callbacksObj = Reflect.get(tx, "mTransactionItems");
        }
        if (!(callbacksObj instanceof List)) {
            android.util.Log.w(TAG, "HCallback[159]: no callbacks on " + tx.getClass().getName());
            return;
        }
        for (Object item : (List<?>) callbacksObj) {
            if (item == null) continue;
            ActivityInfo ai = (ActivityInfo) Reflect.get(item, "mActivityInfo");
            Intent it = (Intent) Reflect.get(item, "mIntent");
            if (ai == null && it == null) continue;

            Intent source = it;
            StubActivityRecord r = source != null ? new StubActivityRecord(source) : null;
            if (r == null || r.info == null) continue;

            Reflect.set(item, "mActivityInfo", r.info);
            if (r.intent != null) {
                try {
                    r.intent.setExtrasClassLoader(GuestIdentity.guestLoader());
                } catch (Throwable ignored) {}
                Reflect.set(item, "mIntent", r.intent);
            }
            android.util.Log.i(TAG, "HCallback[159]: LaunchActivityItem ← " + r.info.name
                    + " (" + item.getClass().getSimpleName() + ")");
            return;
        }
    }

    /** ติดตั้ง: แทน mH.mCallback (เก็บของเดิมไว้เรียกต่อ) */
    public static boolean install() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("currentActivityThread").invoke(null);
            Object mH = Reflect.get(at, "mH");
            if (!(mH instanceof Handler)) {
                android.util.Log.w(TAG, "mH not a Handler");
                return false;
            }
            Handler handler = (Handler) mH;
            Object current = Reflect.get(handler, "mCallback");
            if (current instanceof java.lang.reflect.Proxy) {
                return true;
            }
            Handler.Callback base = current instanceof Handler.Callback
                    ? (Handler.Callback) current : null;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    Handler.Callback.class.getClassLoader(),
                    new Class[]{Handler.Callback.class},
                    new HCallbackProxy(base));
            boolean ok = Reflect.set(handler, "mCallback", proxy);
            android.util.Log.i(TAG, "mH.mCallback proxied=" + ok);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "install HCallbackProxy failed", t);
            return false;
        }
    }
}
