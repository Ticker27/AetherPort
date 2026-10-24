package com.virtualapp.mini;

import android.app.Application;
import android.content.Context;

import com.virtualapp.mini.container.ContainerCore;
import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.HookManager;

/**
 * MiniApp — Application เดียว ทำงาน 2 บทบาทตาม process (แบบ VirtualApp)
 *
 *   main process (com.virtualapp.mini)
 *     attachBaseContext → ContainerCore.init + HookManager (เฉพาะ AMS proxy)
 *     onCreate          → postInit (bootstrap sandbox)
 *
 *   :p0 process (android:process=":p0")
 *     attachBaseContext → ContainerCore.init + HookManager (AMS/PMS/Instrumentation/HCallback)
 *     onCreate          → ไม่ bootstrap (process นี้เป็นของเกสต์)
 *
 * กฎ: hook ต้องติดตั้งใน attachBaseContext ไม่ใช่ onCreate
 * เพราะ ActivityThread จะส่ง LAUNCH_ACTIVITY ให้ mH หลัง Application.onCreate เท่านั้น
 * ถ้าติดตั้งช้าไป 1 จังหวะ message แรกจะหลุด hook
 */
public class MiniApp extends Application {

    private static final String TAG = "AetherPort";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ContainerCore.init(this);
        HookManager.installForProcess(this);
        android.util.Log.i(TAG, "attachBaseContext process="
                + GuestIdentity.currentProcessName(this)
                + " stub=" + GuestIdentity.isStubProcess(this));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!GuestIdentity.isStubProcess(this)) {
            ContainerCore.postInit(this);
            ContainerCore.persistEngineVersion();
            android.util.Log.i(TAG, ContainerCore.engineVersion() + " ready");
        } else {
            android.util.Log.i(TAG, "guest process boot (waiting for stub launch)");
        }
    }
}
