package com.virtualapp.mini.model;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;

/**
 * StubActivityRecord — พอร์ตตรงจาก VirtualApp `com.lody.virtual.remote.StubActivityRecord`
 *
 * สัญญาเดียวกับต้นฉบับ (คีย์ `_VA_|_` ใช้ค่าตาม VirtualApp จริง เพื่อให้ debug/inspect ตรงกัน):
 *   _VA_|_intent_   อินเทนต์ของเกสต์ตัวจริง
 *   _VA_|_info_     ActivityInfo ของเกสต์ (theme/launchMode/configChanges)
 *   _VA_|_caller_   component ของผู้เรียก
 *   _VA_|_user_id_  user id (โปรเจกต์นี้ใช้ 0 คงที่)
 *
 * ใช้เป็น "ซอง" ที่ AMS proxy ยัดเข้าไปแทนอินเทนต์เดิม เพื่อให้ framework เปิด StubActivity$C<n>
 * แล้วค่อยถอดซองกลับมาเป็น component ของเกสต์ที่ HCallback/Instrumentation
 */
public class StubActivityRecord {

    public static final String KEY_INTENT = "_VA_|_intent_";
    public static final String KEY_INFO = "_VA_|_info_";
    public static final String KEY_CALLER = "_VA_|_caller_";
    public static final String KEY_USER_ID = "_VA_|_user_id_";
    /** กันลูป stub → guest → stub เมื่อ hook ไม่ติด: พยายามได้ไม่เกิน 2 ครั้ง */
    public static final String KEY_RETRY = "_VA_|_retry_";

    public Intent intent;
    public ActivityInfo info;
    public ComponentName caller;
    public int userId;
    public int retry;

    public StubActivityRecord(Intent intent, ActivityInfo info, ComponentName caller, int userId) {
        this.intent = intent;
        this.info = info;
        this.caller = caller;
        this.userId = userId;
    }

    public StubActivityRecord(Intent stub) {
        this.intent = stub.getParcelableExtra(KEY_INTENT);
        this.info = stub.getParcelableExtra(KEY_INFO);
        this.caller = stub.getParcelableExtra(KEY_CALLER);
        this.userId = stub.getIntExtra(KEY_USER_ID, 0);
        this.retry = intent != null ? intent.getIntExtra(KEY_RETRY, 0) : 0;
    }

    public void saveToIntent(Intent stub) {
        stub.putExtra(KEY_INTENT, intent);
        stub.putExtra(KEY_INFO, info);
        stub.putExtra(KEY_CALLER, caller);
        stub.putExtra(KEY_USER_ID, userId);
    }
}
