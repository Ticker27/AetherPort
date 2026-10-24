package com.virtualapp.mini;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import com.virtualapp.mini.container.ContainerCore;
import com.virtualapp.mini.container.PluginInstaller;
import com.virtualapp.mini.container.SandboxStore;
import com.virtualapp.mini.container.TargetRegistry;
import com.virtualapp.mini.container.VPackageInstaller;
import com.virtualapp.mini.container.VersionUtil;
import com.virtualapp.mini.exec.GuestIdentity;
import com.virtualapp.mini.exec.HookManager;

import java.io.File;
import java.util.Arrays;

/**
 * MainActivity — console + หน้าพิสูจน์
 *
 * ปุ่ม:
 *   [Install from device]  คัดลอก base+splits ของแอปที่ติดตั้งบนเครื่องเข้า sandbox
 *   [Launch in container]   สั่งเปิดเกสต์ผ่าน stub component
 *   [Prepare plugin]        pipeline plugin (cache → workspace)
 *   [Verify gates]          ตรวจ 7 ข้อว่า "พร้อมและถูกตามหลักการ" แล้วแสดง stage log
 *
 * gates ตรวจจากของจริง ไม่ใช่สมมติ:
 *   G1 sandbox apk มี
 *   G2 pin ตรง (56.31.0)
 *   G3 launcher activity resolve ได้จาก AXML ใน sandbox
 *   G4 StubActivity$C0 ประกาศใน manifest และผูก process=":p0"   ← หลักฐานว่าเป็นโมเดล VirtualApp จริง
 *   G5 มี stub อย่างน้อย 1 ตัวต่อ process ที่ต้องใช้
 *   G6 AMS proxy ติดใน main process
 *   G7 mini-app ทำงานใน main (ไม่ใช่ :pN) — กันการสั่งจาก guest process
 */
public class MainActivity extends Activity {

    private static final String PKG = ContainerCore.TARGET_8BP;
    private static final String STUB0 = "com.virtualapp.mini.stub.StubActivity$C0";

    private TextView mStatus;
    private TextView mLog;
    private final Handler mUi = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatus = findViewById(R.id.tv_status);
        mLog = findViewById(R.id.tv_log);

        findViewById(R.id.btn_install_device).setOnClickListener(v -> new Thread(() -> {
            final VPackageInstaller.Result r = ContainerCore.installFromDevice(this, PKG);
            mUi.post(() -> { append("install: " + r.detail); refresh(); });
        }, "ui-install").start());

        findViewById(R.id.btn_launch).setOnClickListener(v -> {
            String s = ContainerCore.launchInContainer(MainActivity.this, PKG);
            append("launch: " + s);
        });

        findViewById(R.id.btn_plugin).setOnClickListener(v -> new Thread(() -> {
            String r = PluginInstaller.ensurePlugin(this, PKG, 45000L);
            mUi.post(() -> { append("plugin: " + r); refresh(); });
        }, "ui-plugin").start());

        findViewById(R.id.btn_verify).setOnClickListener(v -> {
            append("──── gates ────");
            for (String line : verifyGates()) append(line);
            append("──── stage log (" + PKG + ") ────");
            for (String line : lastSessionLog(40)) append(line);
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ── gates ───────────────────────────────────────────────────

    private String[] verifyGates() {
        String[] out = new String[7];

        boolean apk = SandboxStore.guestApk(this, PKG).exists();
        out[0] = (apk ? "✓" : "✗") + " G1 sandbox apk: "
                + (apk ? SandboxStore.guestApk(this, PKG).getAbsolutePath() : "missing");

        TargetRegistry.Target t = TargetRegistry.byPkg(PKG);
        String ver = ContainerCore.deviceVersion(this, PKG);
        String pinState = "n/a";
        if (t != null && ver != null) {
            pinState = VersionUtil.evaluate(ver, t.pinVersion, t.allowedVersions, t.versionPolicy).state;
        }
        out[1] = (("match".equals(pinState) || "allowed".equals(pinState)) ? "✓" : "✗")
                + " G2 pin: device=" + ver + " pin=" + (t != null ? t.pinVersion : "?")
                + " → " + pinState;

        String launcher = ContainerCore.launcherName(this, PKG);
        out[2] = (launcher != null ? "✓" : "✗") + " G3 launcher resolved: " + launcher;

        boolean stubOk = false;
        String stubDetail = "not declared";
        try {
            ActivityInfo ai = getPackageManager().getActivityInfo(
                    new ComponentName(this, STUB0), 0);
            stubOk = ai.processName != null && ai.processName.endsWith(":p0");
            stubDetail = ai.processName;
        } catch (Exception e) {
            stubDetail = e.getClass().getSimpleName();
        }
        out[3] = (stubOk ? "✓" : "✗") + " G4 StubActivity$C0 → process=" + stubDetail;

        boolean stub1 = true;
        try {
            getPackageManager().getActivityInfo(
                    new ComponentName(this, "com.virtualapp.mini.stub.StubActivity$C1"), 0);
        } catch (Exception e) {
            stub1 = false;
        }
        out[4] = (stub1 ? "✓" : "✗") + " G5 stub ต่อ process (C1/:p1) declared";

        String hookStatus = HookManager.status();
        out[5] = (hookStatus.contains("ams=true") ? "✓" : "✗") + " G6 hooks: " + hookStatus;

        boolean mainProc = !GuestIdentity.isStubProcess(this);
        out[6] = (mainProc ? "✓" : "✗") + " G7 running in main process: "
                + GuestIdentity.currentProcessName(this);

        return out;
    }

    /** อ่าน session log ล่าสุดของเกสต์ (ไฟล์ที่ MiniLog เขียนจาก :p0) */
    private String[] lastSessionLog(int maxLines) {
        try {
            File dir = new File(getFilesDir(), "logs");
            File[] all = dir.listFiles((d, n) -> n.startsWith(PKG) && n.endsWith(".txt"));
            if (all == null || all.length == 0) {
                return new String[]{"(no session log yet — กด Launch แล้วกลับมาดู)"};
            }
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            byte[] buf = new byte[(int) Math.min(all[0].length(), 64 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(all[0])) {
                int n = in.read(buf);
                String text = new String(buf, 0, Math.max(n, 0), "UTF-8");
                String[] lines = text.split("\n");
                int from = Math.max(0, lines.length - maxLines);
                return Arrays.copyOfRange(lines, from, lines.length);
            }
        } catch (Throwable e) {
            return new String[]{"log read error: " + e};
        }
    }

    // ── status ──────────────────────────────────────────────────

    private void refresh() {
        TargetRegistry.Target t = TargetRegistry.byPkg(PKG);
        String devVer = ContainerCore.deviceVersion(this, PKG);
        boolean onDevice = ContainerCore.isInstalledOnDevice(this, PKG);

        StringBuilder sb = new StringBuilder();
        sb.append(t != null ? t.name : PKG).append('\n');
        sb.append(PKG).append('\n');
        sb.append("pin ").append(t != null ? t.pinVersion : "n/a")
                .append(" · ").append(t != null ? t.versionPolicy : "n/a").append('\n');
        sb.append("device: ").append(onDevice ? devVer : "not installed").append('\n');

        String pinState = "n/a";
        if (t != null && onDevice) {
            pinState = VersionUtil.evaluate(devVer, t.pinVersion,
                    t.allowedVersions, t.versionPolicy).toString();
        }
        sb.append("pin check: ").append(pinState).append('\n');

        String launcher = ContainerCore.launcherName(this, PKG);
        sb.append("launcher: ").append(launcher != null ? launcher : "—").append('\n');
        sb.append("process: ").append(GuestIdentity.currentProcessName(this)).append('\n');
        sb.append("hooks: ").append(HookManager.status()).append('\n');

        try {
            org.json.JSONObject conf = SandboxStore.readPackageSetting(this, PKG);
            org.json.JSONObject pl = conf != null ? conf.optJSONObject("plugin") : null;
            String psha = pl != null ? pl.optString("sha256", "") : "";
            sb.append("plugin: ").append(psha.length() >= 12
                    ? psha.substring(0, 12) + "… (" + pl.optInt("libs", 0) + " libs)" : "none")
                    .append('\n');
        } catch (Exception ignored) {}

        sb.append("sandbox: ").append(SandboxStore.guestApk(this, PKG).exists()
                ? "apk present" : "empty");

        mStatus.setText(sb.toString());

        findViewById(R.id.btn_install_device).setEnabled(onDevice);
        findViewById(R.id.btn_launch).setEnabled(SandboxStore.guestApk(this, PKG).exists());
    }

    private void append(String line) {
        CharSequence cur = mLog.getText();
        String next = (cur == null ? "" : cur.toString()) + line + "\n";
        String[] parts = next.split("\n");
        if (parts.length > 80) {
            StringBuilder sb = new StringBuilder();
            for (int i = parts.length - 80; i < parts.length; i++) sb.append(parts[i]).append('\n');
            next = sb.toString();
        }
        mLog.setText(next);
    }
}
