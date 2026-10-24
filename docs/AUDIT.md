# AUDIT — AetherPort เทียบหลักฐาน (VirtualApp / AetherEngine / KOS)

ตรวจเมื่อ: 2026-10-24 · ขอบเขต: ทั้งระบบ (26 ไฟล์ Java + manifest + build) ·
ฐานอ้างอิง: [asLody/VirtualApp](https://github.com/asLody/VirtualApp) ที่ดึงมาอ่านจริง (`StubActivity`,
`AppInstrumentation`, `StubActivityRecord`, `am/MethodProxies`) + `docs/DESIGN.md` ของ AetherEngine

> เอกสารนี้บันทึก **สิ่งที่ขัดกัน** ระหว่างสิ่งที่โปรเจกต์ *อ้างว่าเป็น* กับสิ่งที่โค้ด *ทำจริง*
> ทุกข้ออ้างอิงไฟล์:บรรทัด และกลไกของ VirtualApp ที่เป็น ground truth

---

## สรุปผู้บริหาร

โปรเจกต์ก่อนรอบนี้ (v1) **ไม่ใช่ VirtualApp** — เป็น in-process boot hack ที่:
1. `new GuestActivity()` แล้วเรียก `Activity.attach(...)` ด้วย reflection → ข้าม ActivityThread ทั้งเส้น
2. เขียนตัวตนเกสต์ลง `ActivityThread` ของ **main process**
3. ไม่มี stub component, ไม่มี process แยก, ไม่มี AMS/PMS proxy

ผลคือ: เปิดได้แค่ activity เดียวที่วาด view ขึ้น host, **ไม่สามารถ** ให้เกมเรียก
`startActivity`/`bindService`/`getPackageManager().getPackageInfo(ตัวเอง)` ได้เลย
และ **ไม่ตรงกับ process model ที่ DESIGN.md §4 ของต้นทางเขียนไว้เอง** (`:p0–:p3` proxy components)

รอบนี้จัดโครงใหม่เป็น 5 ชั้นตาม VirtualApp — ดู §5 ท้ายเอกสาร

---

## 1. ตารางความขัดแย้ง (v1 → แก้แล้วอย่างไร)

| # | อาการใน v1 | หลักฐาน | ผลจริง | แก้ใน v2 |
|---|---|---|---|---|
| A1 | ไม่มี stub component ใน manifest | VirtualApp `StubActivity` + manifest มี `StubActivity$C0..C99` ผูก `android:process` | intent ที่ชี้ component ของเกสต์ → `ActivityNotFoundException` ทันที เพราะ component ไม่มีใน PMS | `.stub.StubActivity$C0/:p0`, `$C1/:p1` ใน manifest |
| A2 | `new GuestActivity()` + `Activity.attach()` reflection | VirtualApp ไม่เคยสร้าง Activity เอง — ใช้ `Instrumentation.newActivity` + `ActivityThread` | ไม่มี `ActivityClientRecord`, ไม่มี `mToken` ที่ AMS รู้จัก → `WindowManager.BadTokenException` เมื่อเกมสร้าง dialog/toast/surface; lifecycle ไม่ถูกเรียกจาก framework | `InstrumentationProxy.newActivity` + `HCallbackProxy` สลับ `activityInfo` |
| A3 | เขียน `mBoundApplication`/`LoadedApk` ใน main process (`ProcessAdoption`) | VirtualApp แยก `:p0` ต่อเกสต์ | หลัง adopt: **host เอง** ตอบ `getPackageName()` เป็นชื่อเกม → ActivityManager/PackageManager ของ host พังทั้ง process | `GuestIdentity.adoptProcessIdentity()` **throw** ถ้าเรียกใน main process |
| A4 | `ComponentName` ของเกสต์ถูกส่งเข้า AMS ตรง ๆ | VirtualApp `MethodProxies$StartActivity` เปลี่ยนเป็น stub + ยัด `_VA_|_intent_` | `ActivityNotFoundException` / `SecurityException` | `IActivityManagerProxy` |
| A5 | ไม่มี proxy ของ PMS | VirtualApp `IPackageManagerProxy` + `BPackageManager` | เกมเรียก `getPackageManager().getPackageInfo("com.miniclip.eightballpool")` → `NameNotFoundException` | `IPackageManagerProxy` ตอบจาก sandbox APK |
| A6 | `GuestContext extends ContextWrapper` | VirtualApp `ContextFixer` **เขียน field ของ ContextImpl** ไม่ subclass | framework ถือ `ContextImpl` ตรง ๆ → การ override ของ subclass ไม่ถูกเรียกจากฝั่ง framework | `ContextFixer.fixContext()` เขียน `mBasePackageName/mPackageName/mOpPackageName` |
| A7 | `GuestManifest` เป็น parser AXML มือเอง ดึงได้แค่ชื่อ | VirtualApp ใช้ `PackageParser` ของ framework | ไม่มี `theme`/`launchMode`/`configChanges`/`screenOrientation` → activity เกสต์แสดงผิด/จอหมุนพัง | `ComponentResolver` ใช้ `getPackageArchiveInfo(GET_ACTIVITIES…)` |
| A8 | ActivityInfo ไม่ถูกส่งผ่าน intent | `StubActivityRecord` ของ VirtualApp มี `_VA_|_info_` | ฝั่ง `:p0` ไม่รู้ว่าจะสร้างคลาสไหน/theme อะไร | `model/StubActivityRecord` คีย์ตรงกับต้นฉบับ |
| A9 | 1 process ทำทุกอย่าง | DESIGN.md §4 ของต้นทาง: `:p0–:p3` + helper | เกสต์ 2 ตัวพร้อมกันไม่ได้; plugin ที่ inline-hook กระทบ host | process ต่อเกสต์ (`:p0`, `:p1`) |
| A10 | `VStubActivity` เป็นแค่หน้าจอ debug | `VStubActivity` ของต้นทางเป็น **fallback** ที่ `finish()` แล้วยิงซ้ำ | ไม่มีทางที่ lifecycle จะไหลเข้ากลไกจริง | `StubActivity` ทำตามต้นฉบับ: `super.onCreate(null)` → `finish()` → re-launch |
| A11 | plugin โหลดใน main process | KOS: plugin ต้องอยู่ใน process เดียวกับเกสต์ ก่อน native init | `libmkos/libbytehook/libshadowhook` inline-hook ทั้ง host | `GuestLoader.prepare()` เรียกใน `:p0` ก่อนสร้าง Activity |
| A12 | `launchInContainer` เรียกจาก `:pN` ได้ | VirtualApp: การสั่งเปิดเป็นหน้าที่ main process | เรียกซ้ำใน `:p0` → recursion/สับสน process | `ContainerCore.launchInContainer` คืน `refuse:launch-from-stub-process` |

---

## 2. สิ่งที่ v1 ทำถูกและเก็บไว้

| ส่วน | เหตุผลที่เก็บ |
|---|---|
| `SandboxStore` (layout `root/`) | ตรงกับ layout ที่ KOS ใช้ (DESIGN.md §8) — ตรวจซ้ำได้จากโค้ด |
| `VPackageInstaller` (base + splits + sha256 + pin) | ตรงกับ `installFromDevice` ของต้นทาง และเป็นทางเดียวที่ได้ APK จริงของ 8BP |
| `TargetRegistry` + `VersionUtil` | pin 56.31.0 / listed — ลำดับ version gate ถูกและอยู่ก่อน boot |
| `PluginInstaller` + `PluginLoader` | พอร์ตตรง 1:1 (3,000+ บรรทัดต้นทาง → 508) |
| `MiniLog` (session + stage) | จำเป็นต่อการวินิจฉัยว่าไปถึง stage ไหน — และเป็นวิธีเดียวที่จะรู้ว่า hook ตัวไหนไม่ติด |
| `GameManifest` (AXML parser) | ยังใช้เป็น fallback หา launcher เมื่อ `getPackageArchiveInfo` คืน null (APK เสีย/split-only) |
| `NativeEnv` (Os.setenv) | Unity/IL2CPP ctor chain ต้องการ `HOME` — ต้นทางใช้ `libaether.so` เราใช้ reflection |

---

## 3. สิ่งที่ *ยังไม่มี* และเป็นเหตุผลเชิงกลไก (ไม่ใช่การเดา)

| ช่อง | ทำไมยังไม่ทำ | ผลต่อ 8BP |
|---|---|---|
| `:p0–:p3` ครบ 4 process + `ProxyService/Provider/JobService` | ต้องมี stub component ครบทุกชนิด (Service/Provider/Job) + redirection ของแต่ละชนิด | เกมที่ bind service ของตัวเอง (Unity IAP, Play Core) จะได้ `SecurityException` |
| helper process + keep-alive (`DaemonService`, `SystemCallProvider`) | ไม่จำเป็นต่อ boot; จำเป็นต่อการไม่ถูกฆ่า | process เกมอาจถูกระบบฆ่าเมื่ออยู่เบื้องหลัง |
| `ILEntryPoint`/`IBinder` redirect สำหรับ Service | ต้องมี `StubService` + `IServiceConnection` proxy | service ของเกมไม่ start |
| `ContentProvider` ของเกสต์ | ต้องมี `StubContentProvider` + authority mapping | provider init ของเกม (ถ้ามี) ไม่ทำงาน → บางเกม Unity crash ตอน boot |
| `mH` ทุก what (ไม่ใช่แค่ LAUNCH_ACTIVITY) | ต้อง map `CREATE_SERVICE`/`BIND_SERVICE`/`RECEIVER` ด้วย | broadcast receiver ของเกมไม่ทำงาน |
| signature/integrity forging | ต้องแก้ `PackageInfo.signatures` ให้ตรงกับที่เกมคาด (ค่าอยู่ใน APK จริง — ยังไม่ยืนยันว่าเกมเทียบกับอะไร) | ถ้าเกมเทียบ signature ตัวเอง → ตรวจไม่ผ่าน |
| multi-ABI | manifest ตั้ง `arm64-v8a` เท่านั้น (ตรงกับต้นทาง) | เครื่อง 32-bit รันไม่ได้ |

---

## 4. ลำดับ boot ของ v2 (ตรวจสอบได้จาก log)

```
main: com.virtualapp.mini
  MiniApp.attachBaseContext → ContainerCore.init + HookManager(AMS only)
  MiniApp.onCreate          → postInit (sandbox tree)
  [Install from device]     → VPackageInstaller: base.apk + splits → root/data/app/<pkg>/
  [Launch]                  → ContainerCore.launchInContainer
                                pin gate (56.31.0 = match)
                                ComponentResolver.resolveLauncher → ActivityInfo (theme/launchMode)
                                GuestIdentity.makeStubIntent → StubActivity$C0 + _VA_|_intent_/_info_
                                startActivity

:p0  com.virtualapp.mini:p0        ← process ใหม่
  MiniApp.attachBaseContext → ContainerCore.init + HookManager(AMS/PMS/Instrumentation/HCallback)
  ActivityThread.handleMessage(LAUNCH_ACTIVITY)
      → HCallbackProxy: record.activityInfo ← ActivityInfo ของเกม (ชื่อคลาสจริง)
      → InstrumentationProxy.newActivity: GuestIdentity ยังว่าง → GuestLoader.prepare()
            natives → DexClassLoader → plugin → ApplicationInfo → HOME → bind
          → loadClass(ชื่อคลาสเกม) → Activity ของเกม
      → InstrumentationProxy.callActivityOnCreate → ContextFixer → onCreate ของเกม
  เกมเรียก startActivity → IActivityManagerProxy → stub ใหม่ + ซอง → วนซ้ำข้อบน
```

จุดที่ต่างจาก VirtualApp อย่างตั้งใจ: **การเตรียมเกสต์เกิดใน `Instrumentation.newActivity`**
(ครั้งแรกที่รู้ชื่อ package) ไม่ใช่ตอน process start แบบ `VClientImpl.bindApplication`
เพราะโปรเจกต์นี้ไม่มี server IPC ที่จะสั่ง process ได้ ข้อดี: ไม่ต้องมี `StubContentProvider`
ข้อเสีย: หน้าแรกของเกมจะช้า ~100–400 ms (แตก native + DexClassLoader) — แลกกับการไม่มี IPC

---

## 5. โครงใหม่ (v2)

```
com.virtualapp.mini
├── MiniApp                     process-aware: main = host, :pN = guest
├── MainActivity                console (device version / pin / launcher / plugin / process)
├── stub/
│   └── StubActivity            C0/:p0, C1/:p1 — fallback re-launch ตาม VirtualApp
├── model/
│   └── StubActivityRecord      คีย์ `_VA_|_intent_` / `_VA_|_info_` / `_VA_|_caller_` / `_VA_|_user_id_`
├── hook/
│   ├── IActivityManagerProxy   IActivityManagerSingleton.mInstance → intent ของเกสต์ → stub
│   ├── IPackageManagerProxy    ActivityThread.sPackageManager → PackageInfo/ActivityInfo จาก sandbox
│   ├── InstrumentationProxy    mInstrumentation → newActivity จาก guest loader + ContextFixer
│   └── HCallbackProxy          mH.mCallback → สลับ activityInfo ก่อน LAUNCH_ACTIVITY
├── exec/
│   ├── GuestIdentity           สถานะเกสต์ระดับ process + makeStubIntent + adoptProcessIdentity (guard)
│   ├── GuestLoader             prepare (natives/loader/plugin/appinfo/HOME) + launch
│   ├── ComponentResolver       ActivityInfo/ServiceInfo/ApplicationInfo จาก APK ใน sandbox
│   ├── ContextFixer            เขียน field ของ ContextImpl (ไม่ subclass)
│   ├── HookManager             ลำดับติดตั้งต่อ process (+ idempotent)
│   └── Reflect                 helper reflection
└── container/                  (คงของเดิมที่หลักฐานรองรับ)
    ├── ContainerCore           coordinator: install / pin gate / launch stub / status
    ├── SandboxStore            layout root/ ตาม KOS
    ├── VPackageInstaller       device install + splits + sha256 + pin
    ├── PluginInstaller         cache → workspace → loader_ → vm config
    ├── PluginLoader            DexClassLoader + System.load + entry
    ├── GuestHooks              seam: plugin / fake framework / host command
    ├── TargetRegistry          pin 56.31.0
    ├── VersionUtil             exact | listed | any
    ├── GuestManifest           AXML parser (fallback)
    ├── MiniLog                 session + stage
    ├── NativeEnv               Os.setenv
    └── RunOffMain              worker + join timeout
```

---

## 6. วิธีตรวจว่าโครงตรงหลักฐาน (ทำได้โดยไม่ต้องรัน)

1. **manifest มี stub component + process** — `aapt2 dump xmltree` ต้องเห็น
   `.stub.StubActivity$C0` ที่ `android:process=":p0"` (CI gate มีอยู่แล้วใน workflow)
2. **ไม่มีโค้ดที่สร้าง Activity เอง** — `rg "newInstance\(\)" | rg Activity` ต้องไม่เจอ
3. **ไม่มี adopt ใน main process** — `rg "adoptProcessIdentity"` เจอเฉพาะใน `HookManager`
   ซึ่งมี guard `isStubProcess`
4. **คีย์ extras ตรงกับ VirtualApp** — `rg "_VA_\|_"` ต้องได้ 4 คีย์เท่านั้น
5. **hook ครบ 4 ตัวใน :pN** — log `guest process hooks: ams=true pms=true instr=true hcb=true`

---

## 7. ความเสี่ยงที่ยังเปิดอยู่ (พูดตรง)

- `IActivityManagerSingleton` / `sPackageManager` / `mH.mCallback` / `Activity.attach` signature
  **เปลี่ยนทุก Android release** — โค้ดนี้อ่าน/เขียนด้วยชื่อ field ตรง ๆ ถ้า OEM หรือ API ใหม่เปลี่ยนชื่อ
  hook จะไม่ติด (log จะบอกว่า `proxied=false`) ไม่ใช่ crash — แต่เกมก็จะไม่ทำงาน
- `HiddenApi` restriction: Android 9+ บล็อกการ reflect field ที่ไม่อยู่ใน greylist
  → v2 เรียก `VMRuntime.setHiddenApiExemptions(["L"])` จาก `HookManager.tryHiddenApiBypass()`
  ก่อนติดตั้ง proxy ทุกตัว (ย้ายมาจาก GuestLauncher v1) — ถ้า OEM ปิดทางนี้ hook จะรายงาน false
- ยังไม่มี `StubService`/`StubContentProvider` → เกมที่พึ่งสองอย่างนี้จะไม่ผ่าน
- ไม่มีการยืนยันบนอุปกรณ์จริงในรอบนี้ — ทุกข้อใน §3 เป็นการวิเคราะห์จากโค้ด ไม่ใช่ผลวัด


---

## 8. v2.1 — ปิดช่องที่ยังทำให้ "รันไม่ขึ้น" (รอบพิสูจน์)

v2 วางโครงถูกหลักการแล้ว แต่ยังมี 4 ช่องที่ทำให้ **แอปจริงตายแม้ activity ถูกสร้าง** —
รอบนี้ปิดครบทั้ง 4 และเพิ่มหน้าพิสูจน์ในแอป

| # | ช่อง | อาการถ้าไม่แก้ | ที่ปิด |
|---|---|---|---|
| B1 | `HCallback` ดักแค่ `what=100` | Android 10+ ส่ง `EXECUTE_TRANSACTION (159)` + `ClientTransaction` → hook ไม่ทำงาน → `StubActivity` ถูกสร้างแทนคลาสเกม | `HCallbackProxy.swapTransaction()` ไล่ `getCallbacks()` → `LaunchActivityItem.mActivityInfo/mIntent` |
| B2 | ไม่เคยสร้าง `Application` ของเกสต์ | เกม Unity/IL2CPP ที่พึ่ง `Application.onCreate` ไม่ init; packer ที่ init ที่ provider ไม่ทำงาน | `exec/GuestRuntime.boot()` — ContextFixer → attachBaseContext → providers → onCreate |
| B3 | Activity ใช้ `Resources` ของ host | `setContentView(R.layout.*)` ของเกมด้วย resource id ของเกม → `Resources$NotFoundException` | `exec/GuestResources` — `AssetManager.addAssetPath(base+split)` → เขียน `ContextThemeWrapper.mResources` ก่อน `onCreate` |
| B4 | framework เรียก `setTheme(theme ของเกม)` บน Resources ของ host | `Resources$NotFoundException` ก่อน `onCreate` ด้วยซ้ำ | `ComponentResolver` zero `theme` ในสำเนา + เก็บ id ไว้ → `GuestResources.applyTo()` เรียก `setTheme` เองหลังติดตั้ง Resources |

### หลักฐานในแอป (หน้าพิสูจน์)

ปุ่ม **Verify gates + stage log** ตรวจ 7 ข้อจากของจริง:

```
G1 sandbox apk มี
G2 pin: device=56.31.0 pin=56.31.0 → match
G3 launcher resolved: <คลาสจาก AXML ใน sandbox>
G4 StubActivity$C0 → process=com.virtualapp.mini:p0      ← ยืนยันว่า manifest ผูก process จริง
G5 stub ต่อ process (C1/:p1) declared
G6 hooks: process=main ams=true ...                     ← ยืนยัน proxy ติดจริง ไม่ใช่สมมติ
G7 running in main process: com.virtualapp.mini
```

แล้วอ่าน `files/logs/<pkg>-<ts>-0.txt` (ไฟล์ที่ `MiniLog` เขียนจาก `:p0`) มาแสดง —
stage ที่ต้องเห็นถ้าสำเร็จ:

```
[session] prepare com.miniclip.eightballpool apk=…B
[session] natives N libs → …/lib
[session] classloader ready (base+splits)
[plugin] …
[session] guest bound in com.virtualapp.mini:p0
[session] adopt skipped: …            (ถ้า hidden API จำกัด)
[runtime] context fixed → com.miniclip.eightballpool
[runtime] application attached: <คลาส Application ของเกม>
[runtime] providers booted: n/m
[runtime] application onCreate ✓
[runtime] guest runtime booted
```

ถ้า stage หยุดที่บรรทัดไหน = ช่องถัดไปอยู่ตรงนั้น (ไม่มีการรายงานความสำเร็จเท็จ)

### ที่ยังไม่ปิด (และจะทำให้บางเกมตาย)

- `StubService` / `StubContentProvider` / `StubJob` — เกมที่ `bindService` ของตัวเอง (Unity IAP, Play Core) จะได้ `SecurityException`
- `mH` เฉพาะ transaction ของ activity — broadcast/service ยังไม่ถูกดัก
- `getApplicationContext()` ของเกมยังคืน host Application (VirtualApp สร้าง `LoadedApk` ของเกมจริง — ยังไม่ได้ทำ)
- hidden API: `VMRuntime.setHiddenApiExemptions(["L"])` เป็น best-effort; OEM ที่ปิดทางนี้จะ proxied=false
