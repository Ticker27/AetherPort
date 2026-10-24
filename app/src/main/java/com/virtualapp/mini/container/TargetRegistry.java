package com.virtualapp.mini.container;

import java.util.ArrayList;
import java.util.List;

/**
 * TargetRegistry — ทะเบียนเป้าหมายเกมพร้อม version pin
 *
 * โครงเดียวกับทะเบียนของแบบจำลอง: entry = {name, icon, gameVersion(pin), moduleVersion, pkg}
 * (หลักฐาน: entry[0] Soccer Stars 37.1.1/36.5.2 · entry[1] Carrom Pool 19.5.2/18.5.1 ·
 *            entry[2] 8 Ball Pool 56.31.0/56.7.0)
 * ต่างจากต้นแบบ: ไม่มี hash/module จาก C2 — hash จะคำนวณและบันทึกเองตอนติดตั้ง (self-record)
 */
public final class TargetRegistry {

    public static final class Target {
        public final String id;
        public final String name;
        public final String pkg;
        public final String pinVersion;     // gameVersion ที่ pin ไว้ (56.31.0 สำหรับ 8BP)
        public final String[] allowedVersions; // listed policy: เวอร์ชันที่ยอมรับเพิ่ม (หลักฐาน: 56.31.x + 56.32.0)
        public final String versionPolicy;  // exact | listed | any
        public final String moduleVersion;  // module ที่สอดคล้อง
        public final String icon;
        public final String pluginUrl;      // plugin/module .bin (ดาวน์โหลดตอนกดเล่น — parity KOS)

        Target(String id, String name, String pkg, String pinVersion, String[] allowedVersions,
               String versionPolicy, String moduleVersion, String icon, String pluginUrl) {
            this.id = id;
            this.name = name;
            this.pkg = pkg;
            this.pinVersion = pinVersion;
            this.allowedVersions = allowedVersions;
            this.versionPolicy = versionPolicy;
            this.moduleVersion = moduleVersion;
            this.icon = icon;
            this.pluginUrl = pluginUrl;
        }
    }

    private static final List<Target> TARGETS = new ArrayList<>();

    static {
        // 8BP: pin 56.31.0 (เป้าหมาย reference) + listed 56.32.0 (เวอร์ชันที่ติดตั้งบนเครื่อง/entry ฝั่งต้นแบบ)
        TARGETS.add(new Target("eightballpool", "8 Ball Pool", "com.miniclip.eightballpool",
                "56.31.0", new String[]{"56.31.0", "56.32.0", "56.31.1"}, "listed",
                "56.7.0", "eightballpool.png",
                "https://koscheat.xyz/apk/50003657687a67084f6d691678e9a422cfbb6b72.bin"));
        TARGETS.add(new Target("carrom", "Carrom Pool", "com.miniclip.carrom",
                "19.5.2", new String[]{"19.5.2"}, "listed", "18.5.1", "carrom.png", null));
        TARGETS.add(new Target("soccerstars", "Soccer Stars", "com.miniclip.soccerstars",
                "37.1.1", new String[]{"37.1.1"}, "listed", "36.5.2", "soccerstars.png", null));
    }

    private TargetRegistry() {}

    public static List<Target> all() {
        return TARGETS;
    }

    public static Target byPkg(String pkg) {
        for (Target t : TARGETS) if (t.pkg.equals(pkg)) return t;
        return null;
    }
}
