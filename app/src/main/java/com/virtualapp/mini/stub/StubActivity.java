package com.virtualapp.mini.stub;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;

import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.model.StubActivityRecord;

/**
 * StubActivity — พอร์ตตรงจาก VirtualApp `com.lody.virtual.client.stub.StubActivity`
 *
 * บทบาท: เป็น "ที่จอด" ที่ framework เปิดได้จริง (declared ใน manifest, มี android:process=":p0..")
 * เพราะ component ของเกสต์ไม่ได้อยู่ใน PMS ของเครื่อง
 *
 * ลำดับ (ตามต้นฉบับ):
 *   onCreate(savedInstanceState) → super.onCreate(null) → finish()
 *   → ถอด StubActivityRecord ออกจาก intent
 *   → ถ้า processName ตรงกับ process ปัจจุบัน → startActivity(intent ของเกสต์) อีกรอบ
 *     (รอบนี้ HCallback/Instrumentation ที่ติดตั้งแล้วจะสลับ activityInfo เป็นของเกสต์
 *      และสร้าง Activity ด้วย guest ClassLoader)
 *   → ถ้าไม่ตรง → ส่งต่อไปยัง process ที่ถูกต้อง
 *
 * หมายเหตุ: ต้นฉบับเรียก VActivityManager.get() ซึ่งเป็น IPC ไป server
 * โปรเจกต์นี้ไม่มี server process — ใช้ GuestIdentity.requestCrossProcessLaunch แทน (single-guest-per-process)
 */
public abstract class StubActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // savedInstanceState ถูก unmarshal ด้วย classLoader ของ host — ใช้ไม่ได้กับเกสต์
        super.onCreate(null);
        finish();

        StubActivityRecord r = new StubActivityRecord(getIntent());
        if (r.intent == null) {
            android.util.Log.w("AetherPort", "StubActivity: no stashed intent");
            return;
        }

        String targetProcess = r.info != null && r.info.processName != null
                ? r.info.processName : GuestIdentity.currentProcessName(this);

        // กันลูป: ถ้า HCallback ไม่ติด การยิงซ้ำจะวน stub→guest→stub ไม่จบ
        if (r.retry >= 2) {
            android.util.Log.e("AetherPort", "StubActivity: retry limit — HCallback/Instrumentation"
                    + " ไม่ทำงาน (hook ไม่ติดใน process นี้)");
            return;
        }
        r.intent.putExtra(StubActivityRecord.KEY_RETRY, r.retry + 1);

        if (TextUtils.equals(targetProcess, GuestIdentity.currentProcessName(this))) {
            // อยู่ process ถูกแล้ว — ยิงซ้ำ แล้ว HCallback จะเปลี่ยนคลาสเป็นของเกสต์
            r.intent.setExtrasClassLoader(getClassLoader());
            startActivity(r.intent);
        } else {
            // ต้องข้าม process — เปิด stub ตัวใหม่ที่ผูกกับ process ของเกสต์
            GuestIdentity.requestCrossProcessLaunch(this, r);
        }
    }

    public static class C0 extends StubActivity {}
    public static class C1 extends StubActivity {}
    public static class C2 extends StubActivity {}
    public static class C3 extends StubActivity {}
    public static class C4 extends StubActivity {}
    public static class C5 extends StubActivity {}
    public static class C6 extends StubActivity {}
    public static class C7 extends StubActivity {}
    public static class C8 extends StubActivity {}
    public static class C9 extends StubActivity {}
}
