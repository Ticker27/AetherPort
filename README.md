# AetherPort — Android container engine (VirtualApp-shaped v2)

รันแอปเป้าหมายใน process ของเราเอง ด้วยกลไกเดียวกับ
[VirtualApp](https://github.com/asLody/VirtualApp): **stub component + AMS/PMS proxy + HCallback**

**เป้าหมาย:** `com.miniclip.eightballpool` @ `56.31.0` (ติดตั้งอยู่บนเครื่องแล้ว)

| | |
|---|---|
| package | `com.virtualapp.mini` |
| minSdk / target / compile | 28 / 34 / 34 |
| ABI | `arm64-v8a` |
| UI | framework `Activity` ล้วน (ไม่มี appcompat) |
| native ของเราเอง | ไม่มี (ใช้ `android.system.Os.setenv` แทน `libaether.so`) |
| process | `main` + `:p0` + `:p1` (1 process = 1 เกสต์) |

> **v1 → v2:** v1 สร้าง Activity เองด้วย `new + Activity.attach()` ใน main process
> ซึ่งข้าม ActivityThread และเขียนทับตัวตน host — ตรวจแล้วไม่ตรงกับหลักฐาน VirtualApp
> รายละเอียดทุกข้อ: [`docs/AUDIT.md`](docs/AUDIT.md)

---

## 1. ทำไมต้องมี stub component (หัวใจของกลไก)

```
component ของเกม (com.miniclip.eightballpool/.MainActivity)
        │  ไม่มีอยู่ใน PackageManager ของเครื่อง
        ▼
AMS proxy  ──►  เปลี่ยน component เป็น .stub.StubActivity$C0  (declared จริง, process=":p0")
                + ยัดอินเทนต์เดิมลง extras `_VA_|_intent_` และ ActivityInfo ลง `_VA_|_info_`
        ▼
framework เปิด StubActivity$C0 ใน :p0  →  process ใหม่ตื่น → MiniApp ติดตั้ง hook
        ▼
HCallbackProxy (mH)  ──►  ก่อนประมวลผล LAUNCH_ACTIVITY: สลับ record.activityInfo
                          เป็นของเกม (ชื่อคลาสจริง/theme/launchMode) + คืน intent ของเกม
        ▼
InstrumentationProxy.newActivity  ──►  เตรียมเกสต์ถ้ายังว่าง (natives → DexClassLoader
                                     → plugin → ApplicationInfo → HOME) แล้ว loadClass(คลาสเกม)
        ▼
framework เรียก onCreate  ──►  ContextFixer เขียน ContextImpl ให้เป็นของเกม → onCreate ของเกม
```

ไม่มีขั้นไหนที่เราสร้าง Activity เอง — framework เป็นคนสร้าง token/record/lifecycle ให้ครบ

---

## 2. Data flow

```
main: com.virtualapp.mini
  MiniApp.attachBaseContext → ContainerCore.init + HookManager (AMS proxy)
  MiniApp.onCreate          → postInit (sandbox root/)
  [Install from device]     → VPackageInstaller: applicationInfo.sourceDir + splitSourceDirs
                              → root/data/app/<pkg>/{base.apk, <split>.apk, package.conf, lib/}
                              → sha256 · pin gate · uid.conf · markReadOnly
  [Prepare plugin]          → PluginInstaller.ensurePlugin (cache → workspace → loader_)
  [Launch]                  → ContainerCore.launchInContainer
                                pin gate (56.31.0)
                                ComponentResolver.resolveLauncher → ActivityInfo
                                GuestIdentity.makeStubIntent → StubActivity$C0 + ซอง
                                startActivity

:p0
  MiniApp.attachBaseContext → ContainerCore.init + HookManager (AMS/PMS/Instrumentation/HCallback)
  HCallback → Instrumentation.newActivity → GuestLoader.prepare (ครั้งแรกเท่านั้น)
            → loadClass(คลาสเกม) → onCreate → ContextFixer
  เกม startActivity → IActivityManagerProxy → stub ใหม่ + ซอง → วนซ้ำ
```

---

## 3. โครงไฟล์ (26 ไฟล์ / ~3,460 บรรทัด)

```
com/virtualapp/mini/
├ MiniApp.java                  main = host · :pN = guest (hook ติดตั้งใน attachBaseContext)
├ MainActivity.java             console: device version / pin / launcher / plugin / process
├ stub/StubActivity.java        C0/:p0 · C1/:p1 — fallback re-launch (parity VirtualApp)
├ model/StubActivityRecord.java  คีย์ `_VA_|_intent_` `_VA_|_info_` `_VA_|_caller_` `_VA_|_user_id_`
├ hook/
│   ├ IActivityManagerProxy     IActivityManagerSingleton.mInstance → intent เกม → stub
│   ├ IPackageManagerProxy      ActivityThread.sPackageManager → PackageInfo/ActivityInfo จาก sandbox
│   ├ InstrumentationProxy      mInstrumentation → newActivity จาก guest loader
│   └ HCallbackProxy            mH.mCallback → สลับ activityInfo ก่อน LAUNCH_ACTIVITY
├ exec/
│   ├ GuestIdentity             ตัวตนเกสต์ระดับ process · makeStubIntent · adoptProcessIdentity (guard)
│   ├ GuestRuntime              Application + ContentProvider ของเกสต์ (attachBaseContext → providers → onCreate)
│   ├ GuestResources            Resources จาก APK (base+split) + theme ที่เลื่อนมาใช้ทีหลัง
│   ├ GuestLoader               prepare (natives/loader/plugin/appInfo/HOME) + launch
│   ├ ComponentResolver         ActivityInfo/ServiceInfo/ApplicationInfo จาก APK ใน sandbox
│   ├ ContextFixer              เขียน field ของ ContextImpl (ไม่ subclass)
│   ├ HookManager               ลำดับติดตั้งต่อ process + hidden API bypass + idempotent
│   └ Reflect                   helper reflection
└ container/
    ├ ContainerCore             coordinator: install / pin gate / launch stub / status
    ├ SandboxStore              layout root/ (cache · data/app/<pkg> · user[_de] · profiles · proc · system)
    ├ VPackageInstaller         device install + splits + sha256 + pin + package.conf + uid
    ├ PluginInstaller           cache/<sha>.bin → workspace/{apk,lib,oat} → loader_<pkg> → vm config
    ├ PluginLoader              DexClassLoader + System.load(*.so) + entry/bridge
    ├ GuestHooks                seam: plugin / fake framework / host command
    ├ TargetRegistry            pin 8BP 56.31.0 (allowed 56.31.0/56.31.1/56.32.0, policy listed)
    ├ VersionUtil               exact | listed | any
    ├ GuestManifest             AXML parser (fallback เมื่อ getPackageArchiveInfo คืน null)
    ├ MiniLog                   session + stage + rotate 3
    ├ NativeEnv                 Os.setenv (HOME สำหรับ Unity/IL2CPP)
    └ RunOffMain                worker + join timeout (generic)
```

---

## 4. Sandbox layout (`files/root/`)

```
root/
├ cache/{empty.apk, junit.apk, oat/<pkg>/}
├ data/app/<pkg>/{base.apk, <split>.apk, package.conf, lib/*.so}
├ data/app-staging/
├ data/user/0/<pkg>/{files,cache,databases,shared_prefs,no_backup,app_textures,
│                    app_plugin_workspace_p0,app_plugin_odex_p0,app_webview_V_0_P0_<pkg>}
├ data/user_de/0/<pkg>/
├ profiles/{cur,ref}/0/<pkg>/
├ proc/0/cmdline
└ system/{uid.conf,user.conf,shared-user.conf,xposed-module.conf,developer-module.conf}
```

`package.conf` = `{pkg, setting{apkPath, versionName, versionCode, sha256, pin, splits, states{...}, plugin{...}}}`

---

## 5. Plugin pipeline

```
ensurePlugin: TargetRegistry.pluginUrl → cache/<sha256>.bin (verify ทุกครั้งที่ reuse)
              → workspace/app_plugin_workspace_p0/plugin_<epoch>.apk (readOnly)
              → lib/*.so (แตกจาก lib/arm64-v8a) · oat/arm64/ · prefs loader_<pkg>
              → cache/aetherport_vm_config.properties {pathToApk, loader_sha256}
loadPlugin:   DexClassLoader(apk, oat, lib) → System.load(*.so) → entry.init(Context)
              ไม่มี entry = module-libs-only (ไม่ทำเกสต์ล้ม)
```

เรียกจาก `GuestLoader.prepare()` ซึ่งรันใน `:p0` **ก่อน** สร้าง Activity — ตรงกับลำดับ KOS

---

## 6. Build (GitHub Actions)

```bash
git init && git add . && git commit -m "AetherPort v2"
gh repo create AetherPort --public --source=. --push
```

workflow: JDK 17 → `gradle :app:assembleDebug` → `aapt2 dump xmltree` ตรวจว่า
`MiniApp`/`MainActivity`/`StubActivity$C0` (+`android:process=":p0"`) ถูก merge จริง → artifact

---

## 7. เอกสาร

| ไฟล์ | เนื้อหา |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | ความขัดแย้ง v1 ทั้ง 12 ข้อ + หลักฐาน VirtualApp + สิ่งที่ยังขาด |
| [`docs/REFERENCE-MODEL.md`](docs/REFERENCE-MODEL.md) | โครง 3 ชั้นของ KOS / Snake (ต้นแบบที่รัน 8BP ได้) |

---

## 8. สถานะ

| ส่วน | สถานะ |
|---|---|
| stub component + process แยก | ✅ `:p0`, `:p1` |
| AMS proxy (intent → stub + ซอง) | ✅ |
| PMS proxy (PackageInfo/ActivityInfo จาก sandbox) | ✅ |
| Instrumentation + HCallback (activityInfo swap) | ✅ |
| ContextFixer (ContextImpl fields) | ✅ |
| hidden API bypass | ✅ (`HookManager`) |
| device install (base+splits) · pin gate · sandbox layout | ✅ |
| plugin pipeline (cache→workspace→loader→entry) | ✅ |
| StubService / StubContentProvider / StubJob | ⬜ ยังไม่มี |
| helper process + keep-alive | ⬜ ยังไม่มี |
| mH ทุก what (service/broadcast) | ⬜ เฉพาะ LAUNCH_ACTIVITY |
| fake framework (IAMS/IPMS ระดับลึก + signature forging) | ⬜ seam |
| HCallback 2 ทาง (`what=100` + `ClientTransaction` 159) | ✅ v2.1 |
| Application + ContentProvider ของเกสต์ | ✅ `exec/GuestRuntime` |
| Resources ของเกสต์ (base+split) + theme deferral | ✅ `exec/GuestResources` |
| หน้าพิสูจน์ในแอป (7 gates + stage log) | ✅ ปุ่ม Verify |
| ยืนยันบนอุปกรณ์จริง | ⬜ **ยังไม่ได้รัน** — ใช้ปุ่ม Verify เพื่อเก็บหลักฐานรอบแรก |
