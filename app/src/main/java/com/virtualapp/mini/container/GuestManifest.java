package com.virtualapp.mini.container;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GuestManifest — อ่าน AndroidManifest.xml (binary AXML) จาก APK ใน sandbox
 * ดึง: package name + activity ที่มี MAIN/LAUNCHER (ไม่พึ่ง PackageParser/hidden API)
 *
 * อ้างอิง: path mapping ของแบบจำลองใช้ PathClassLoader โหลด base.apk ของ guest —
 * ที่นี่คือส่วน "อ่าน manifest ของ guest" ที่ทำให้รู้ class ของ launcher โดยไม่ต้องพึ่งแอปที่ติดตั้งนอก
 */
public final class GuestManifest {

    public final String packageName;
    public final String applicationName;
    public final String launcherActivity;
    public final List<String> activities;
    public final List<String> providerNames = new ArrayList<>();
    public final List<String> providerAuthorities = new ArrayList<>();

    private GuestManifest(String pkg, String appName, String launcher, List<String> acts) {
        this.packageName = pkg;
        this.applicationName = appName;
        this.launcherActivity = launcher;
        this.activities = acts;
    }

    public static GuestManifest parse(String apkPath) throws Exception {
        byte[] axml;
        try (ZipFile zf = new ZipFile(apkPath)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) throw new IllegalStateException("no manifest");
            try (InputStream in = zf.getInputStream(e)) {
                axml = readAll(in);
            }
        }
        return parseBinary(axml);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ── AXML ─────────────────────────────────────────────────────
    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int RES_XML_END_ELEMENT = 0x0103;

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static GuestManifest parseBinary(byte[] b) throws Exception {
        if (b.length < 8 || u16(b, 0) != 0x0003) throw new IllegalStateException("not axml");
        String[] pool = null;
        String pkg = null;
        String appName = null;
        String launcher = null;
        List<String> acts = new ArrayList<>();
        List<String> acts0 = new ArrayList<>();
        List<String> auths0 = new ArrayList<>();
        String curActivity = null;
        boolean inFilter = false;
        boolean seenMain = false;
        boolean seenLauncher = false;

        int off = 8; // skip XML header
        while (off + 8 <= b.length) {
            int type = u16(b, off);
            int headerSize = u16(b, off + 2);
            int size = u32(b, off + 4);
            if (size <= 0 || off + size > b.length) break;

            if (type == RES_STRING_POOL) {
                int stringCount = u32(b, off + 8);
                int styleOffset = u32(b, off + 12);
                int flags = u32(b, off + 16);
                int stringsStart = u32(b, off + 20);
                boolean utf8 = (flags & (1 << 8)) != 0;
                int[] offsets = new int[stringCount];
                for (int i = 0; i < stringCount; i++) offsets[i] = u32(b, off + 28 + i * 4);
                pool = new String[stringCount];
                int base = off + stringsStart;
                for (int i = 0; i < stringCount; i++) {
                    int p = base + offsets[i];
                    if (p >= off + size) { pool[i] = ""; continue; }
                    if (utf8) {
                        int len = b[p] & 0xFF;
                        int s = p + 1;
                        if ((len & 0x80) != 0) { len = ((len & 0x7F) << 8) | (b[p + 1] & 0xFF); s = p + 2; }
                        pool[i] = new String(b, s, len, StandardCharsets.UTF_8);
                    } else {
                        int len = u16(b, p);
                        int s = p + 2;
                        if ((len & 0x8000) != 0) { len = ((len & 0x7FFF) << 16) | u16(b, p + 2); s = p + 4; }
                        pool[i] = new String(b, s, len * 2, StandardCharsets.UTF_16LE);
                    }
                }
                if (styleOffset != 0) { /* ไม่ใช้ style */ }
            } else if (type == RES_XML_START_ELEMENT && pool != null) {
                int nameIdx = u32(b, off + 20);           // node header 16B → name at +20
                String name = nameIdx < pool.length ? pool[nameIdx] : null;
                int attrCount = u16(b, off + 28);
                int attrOff = off + 36;                    // attributes start (node hdr 16 + ext hdr 20)
                String aName = null, aValue = null, aAuth = null;
                for (int i = 0; i < attrCount; i++) {
                    int ao = attrOff + i * 20;
                    int nIdx = u32(b, ao + 4);
                    int rawIdx = u32(b, ao + 8);
                    String an = nIdx < pool.length ? pool[nIdx] : "";
                    String av = (rawIdx != 0xFFFFFFFF && rawIdx < pool.length) ? pool[rawIdx] : null;
                    if ("name".equals(an) && av != null) { aName = av; }
                    if ("package".equals(an) && av != null && pkg == null) { pkg = av; }
                    if ("authorities".equals(an) && av != null) { aAuth = av; }
                }
                if ("application".equals(name) && aName != null && appName == null) {
                    appName = aName;
                }
                if ("provider".equals(name) && aName != null) {
                    acts0.add(aName);
                    auths0.add(aAuth);
                }
                if ("activity".equals(name) && aName != null) {
                    acts.add(aName);
                    curActivity = aName;
                    seenMain = false;
                    seenLauncher = false;
                } else if ("intent-filter".equals(name)) {
                    inFilter = true;
                } else if ("action".equals(name) && inFilter && aName != null
                        && aName.equals("android.intent.action.MAIN")) {
                    seenMain = true;
                } else if ("category".equals(name) && inFilter && aName != null
                        && aName.equals("android.intent.category.LAUNCHER")) {
                    seenLauncher = true;
                }
            } else if (type == RES_XML_END_ELEMENT && pool != null) {
                int nameIdx = u32(b, off + 20);
                String name = nameIdx < pool.length ? pool[nameIdx] : null;
                if ("intent-filter".equals(name) && curActivity != null && seenMain && seenLauncher
                        && launcher == null) {
                    launcher = curActivity;
                }
                if ("activity".equals(name)) curActivity = null;
            }
            off += size;
        }
        if (pkg == null) throw new IllegalStateException("no package name");
        GuestManifest gm = new GuestManifest(pkg, appName, launcher, acts);
        gm.providerNames.addAll(acts0);
        gm.providerAuthorities.addAll(auths0);
        return gm;
    }
}
