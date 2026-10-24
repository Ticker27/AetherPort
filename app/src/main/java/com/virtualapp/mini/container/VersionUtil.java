package com.virtualapp.mini.container;

/**
 * VersionUtil — โครงเทียบเวอร์ชันของเกม + นโยบาย (ชั้น version subsystem ก่อนชั้น plugin)
 *
 * หลักฐานประกอบ:
 *  - ต้นแบบ (Snake) เก็บ entry: gameVersion 56.32.0 / moduleVersion 56.7.0 และมีเคส 56.31.1
 *  - KOS: ไม่ pin เวอร์ชันในฝั่งรัน (entitlement อยู่ server) — แต่ฝั่ง install มี versionName เก็บใน package.conf
 *
 * นโยบายที่รองรับ:
 *   exact   — ต้องเท่ากับ pin เท่านั้น
 *   listed  — เท่ากับ pin หรืออยู่ใน allowedVersions[] (เช่น 56.31.0 ของ reference + 56.32.0 ที่ติดตั้งบนเครื่อง)
 *   any     — ไม่จำกัด
 */
public final class VersionUtil {

    private VersionUtil() {}

    /** เทียบแบบ dotted-numeric: a<b → -1, เท่ากัน → 0, a>b → 1 */
    public static int compare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length && pa[i].length() > 0 ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length && pb[i].length() > 0 ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** ผลประเมิน: state ∈ {match, allowed, mismatch, unknown} + เหตุผล */
    public static final class Result {
        public final String state;
        public final String reason;

        Result(String state, String reason) {
            this.state = state;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return state + (reason == null || reason.isEmpty() ? "" : "(" + reason + ")");
        }
    }

    public static Result evaluate(String version, String pin, String[] allowed, String policy) {
        if (version == null || version.isEmpty() || "?".equals(version)) {
            return new Result("unknown", "no-version");
        }
        if (policy == null || "any".equals(policy)) {
            return new Result("allowed", "policy=any");
        }
        if (pin == null || pin.isEmpty()) {
            return new Result("unknown", "no-pin");
        }
        if (version.equals(pin)) {
            return new Result("match", "pin");
        }
        if (allowed != null) {
            for (String v : allowed) {
                if (version.equals(v)) {
                    return new Result("allowed", "listed");
                }
            }
        }
        if ("listed".equals(policy)) {
            return new Result("mismatch", "not-in-" + java.util.Arrays.toString(allowed));
        }
        return new Result("mismatch", "want=" + pin);
    }
}
