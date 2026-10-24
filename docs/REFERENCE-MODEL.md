# Reference model — โครงสร้าง KOS / Snake (ตรวจจาก repo AetherEngine)

ที่มา: `docs/DESIGN.md` §7–§14, `docs/PLUGIN-LOADER.md`, และ comment ในไฟล์ `container/*.java`
ของ [Ticker27/AetherEngine](https://github.com/Ticker27/AetherEngine)

**สถานะหลักฐาน แยกสองชั้นให้ชัด:**

| ชั้น | ความหมาย |
|---|---|
| **[code]** | ยืนยันได้จาก source code ในรีโปนี้เอง (อ่านได้ ไม่ต้องเชื่อใคร) |
| **[doc]** | คำอธิบายของผู้เขียนรีโป อ้างการวัดสดจากเครื่อง (`live-2026-10-20/evidence/`) — ตรวจซ้ำเองไม่ได้จากรีโป |

---

## 1. สองชื่อที่ต้องแยก

| ชื่อ | คืออะไร | หลักฐาน |
|---|---|---|
| **KOS** | container engine ที่รันอยู่จริงบนเครื่องทดสอบ — `com.kos` / `com.kos.engine`, v3.7 | [doc] §8, §11, §12 |
| **Snake** | ชั้น registry/C2 ฝั่งเซิร์ฟเวอร์ — entry table `{gameVersion, moduleVersion}` | [doc] comment ใน `VersionUtil.java` — *"ต้นแบบ (Snake) เก็บ entry: gameVersion 56.32.0 / moduleVersion 56.7.0 และมีเคส 56.31.1"* |

Snake ไม่ใช่ container — เป็นแค่ทะเบียนเวอร์ชันต่อเกม. ตัวที่ "ทำงานเดียวกัน" กับโปรเจกต์นี้คือ **KOS**

---

## 2. โครงสร้าง KOS — 3 ชั้น

```
┌─ L3  Plugin / module pipeline ──────────────────────────────── [doc] §11, §13
│   GET  koscheat.xyz/apk/<id>.bin                  ← APK จริง (classes.dex + *.so)
│   cache/<serverId>.bin                            ← /data/user/0/com.kos/cache/
│   sha256(file) == loader_<pkg>  (KOS_App_Settings.xml)   ← "ตัวตน loader"
│   app_plugin_workspace_p0/{plugin_<epoch>.apk, lib/*.so, oat/}
│   blackbox_vm_config.properties { pathToApk=cache/<id> }
│   → VM โหลด plugin เข้า process ของเกสต์
│
├─ L2  Fake framework ────────────────────────────────────────── [doc] §7
│   com.kos.engine.fake.FakeCore                       init
│   com.kos.engine.fake.hook.HookManager               ติดตั้งทุก hook
│   com.kos.engine.fake.delegate.AppInstrumentation    newActivity ผ่าน guest loader
│   com.kos.engine.fake.service.IActivityManagerProxy  + IBActivityManagerService$Stub$Proxy
│   com.kos.engine.fake.service.IPackageManagerProxy   + BPackageManager
│   ติดตั้งที่: mInstrumentation · IActivityManagerSingleton · sPackageManager · mH
│
└─ L1  Sandbox root (kos_engine/) ────────────────────────────── [doc] §8
    cache/{empty.apk, junit.apk}          ← stub APK สำหรับ PMS ปลอม
    data/app-staging/                     ← staging ของ PMS
    data/app/<pkg>/{base.apk, lib, package.conf}
    data/user/0/<pkg>/…                   files cache databases shared_prefs no_backup
                                          app_textures app_plugin_workspace_p0 app_plugin_odex_p0
                                          app_webview_V_0_P0_<pkg>
    data/user_de/0/<pkg>/
    profiles/{cur,ref}/0/<pkg>/           ← ART profile redirection
    proc/0/cmdline                        ← fake /proc
    system/{uid,user,shared-user,xposed-module,developer-module}.conf
```

### Process model [doc] §4

```
main (com.kos)                    :helper
├ App / Entry                     ├ SystemCallProvider (exported, call("VM",…))
├ VStubActivity                   │ + ProxyBroadcastReceiver (goAsync → core)
├ TransparentProxyActivity        └ DaemonService
├ ProxyPendingActivity               ├ startForeground(10000)
└ ProxyVpnService                    ├ startService(DaemonInnerService)
                                     └ AlarmManager.setExactAndAllowWhileIdle(+60s → self)

:p0  ProxyActivity$P0/P0L · ProxyService$P0 · ProxyContentProvider$P0 · ProxyJobService$P0
:p1  … P1   :p2 … P2   :p3 … P3
```

**อ่านจากชื่อคลาส** (inference จาก naming ไม่ใช่คำยืนยันของผู้เขียน):
`TransparentProxyActivity` · `Proxy<Component>$P<N>` · `IPackageManagerProxy` · `BPackageManager`
· `IBActivityManagerService$Stub$Proxy` · `blackbox_vm_config.properties`
→ ชุดนี้คือ idiom ของ app-virtualization framework ตระกูล **VirtualApp / BlackBox**
(`$P0..$P3` = stub component ใน process ย่อย, `BPackageManager` = Binder proxy ของ PMS, `pathToApk` = BlackBox config)
KOS จึงไม่ใช่ engine ที่เขียน Binder/AMS hook ใหม่จากศูนย์ แต่ประกอบบน framework ตระกูลนี้ + เพิ่มชั้น L3

---

## 3. สิ่งที่โปรเจกต์นี้มีแล้ว vs ที่ยังขาด

| ชั้น | กลไกของ KOS | AetherPort (โปรเจกต์นี้) | สถานะ |
|---|---|---|---|
| L1 | sandbox tree | `SandboxStore` — ครบทุก path (§8 ตรงกัน) | ✅ |
| L1 | `package.conf` | JSON `{pkg, setting{…, states}}` | ✅ (ต้นแบบเป็น binary ของ `BPackage`) |
| L1 | fake `/proc/0/cmdline` | `writeProcCmdline()` | ✅ |
| L1 | ART profile redirect | `profiles/{cur,ref}/0/<pkg>` | ✅ |
| L1 | conf 6 ไฟล์ | `system/*.conf` | ✅ (JSON แทน binary) |
| — | `loader_<pkg>` identity | prefs `com.virtualapp.mini.plugin` → `loader_<pkg>` = sha | ✅ |
| — | cache `<id>.bin` | `cache/<sha256>.bin` (self-verify) | ✅ (ตั้งใจต่าง — KOS ใช้ serverId) |
| L3 | workspace `plugin_<epoch>.apk` | `PluginInstaller.materialize()` | ✅ |
| L3 | `lib/*.so` จาก plugin | `extractLibs()` → `workspace/lib` | ✅ |
| L3 | `oat/arm64` | `PluginLoader` ใช้เป็น `optimizedDirectory` | ✅ |
| L3 | vm config `pathToApk` | `cache/aetherport_vm_config.properties` | ✅ |
| L3 | plugin เข้า guest process | `GuestHooks.loadPlugin` ← `PluginLoader.load` | ✅ |
| L2 | `AppInstrumentation` | `GuestHooks.fakeFramework` | ⬜ seam ว่าง |
| L2 | `IActivityManagerProxy` | ” | ⬜ |
| L2 | `IPackageManagerProxy` + `BPackageManager` | ” | ⬜ |
| L2 | `mH` callback | ” | ⬜ |
| P | `:p0–:p3` proxy components | — | ⬜ ไม่มี |
| P | helper process + keep-alive (FGS + exact alarm) | — | ⬜ ไม่มี |
| P | `SystemCallProvider` IPC bridge | — | ⬜ ไม่มี |

**สรุป:** โครงที่ port มาครบคือ **L1 + L3**. ที่ยังว่างคือ **L2 fake framework** และ **process model**
— สองชั้นนี้คือสิ่งที่ตัดสินว่าเกม Unity ที่มี packer/anti-tamper จะผ่านหรือไม่

---

## 4. ทำไม L2 ถึงสำคัญกับ 8BP (เหตุผลเชิงกลไก ไม่ใช่การเดาเนื้อเกม)

จาก log ที่ AetherEngine บันทึกไว้เอง [doc] §6:

```
[✓] attach ผ่าน reflection (19-param signature)
[✓] แตก native libs ของ guest: 20 libs / 102 MB
[✓] guest onCreate ถึง native init ของตัวเกม
[✗] native init ของเกมเอง (packer/anti-tamper) — ต้อง replicate framework-level hooks:
    LoadedApk/Application forgery, PMS/AMS proxy, ลำดับ preload ของ module
```

คือ: boot chain เดินถึง `onCreate` ของเกมได้ แต่ **native init ไม่ผ่าน** เพราะขาด L2
ของที่ต้องมีเพื่อปิดช่องนี้ เรียงตามสิ่งที่ KOS มี:

1. `sPackageManager` proxy → `getPackageInfo(<pkg>)` ของเกมต้องไม่ `NameNotFound`
   และ `signatures` ต้องคืนของจริง (L1 มี `conf` แล้ว แต่ยังไม่มี proxy ที่ตอบคำถามนี้)
2. `IActivityManager` proxy → เกมที่เรียก `startActivity`/`bindService` ต้องไม่หลุดออกนอก process
3. `AppInstrumentation.newActivity` → activity ที่เกมสร้างเอง (ไม่ใช่ launcher) ต้องโหลดจาก guest loader
4. `mH` callback → ดัก `LAUNCH_ACTIVITY`/`CREATE_SERVICE` ที่ framework ส่งเข้ามา
5. `LoadedApk`/`Application` forgery → `getApplicationInfo()`, `getDataDir()`, `getNativeLibraryDir()`
   ต้องชี้ sandbox (ตอนนี้ `ProcessAdoption` ทำ 4 ฟิลด์ — ยังไม่พอสำหรับ packer ที่อ่าน `LoadedApk` เอง)

---

## 5. ลำดับที่ควรพอร์ตต่อ (ถ้าจะไปให้ถึง L2)

```
1. fake/FakeCore.java               → ContainerCore.init() เรียกติดตั้ง
2. fake/hook/HookManager.java       → GuestHooks.fakeFramework = HookManager::install
3. fake/delegate/AppInstrumentation.java
4. fake/service/IPackageManagerProxy.java   (forge PackageInfo จาก SandboxStore.readPackageSetting)
5. fake/service/IActivityManagerProxy.java  (passthrough + log component)
6. fake/hook/HCallback.java                 (mH → consume=false)
```

ทั้ง 6 ไฟล์อยู่ใน `android/app/src/main/java/com/aether/fake/` ของรีโปต้นทาง
ดึงมาด้วย `curl` แล้วแปลง package name ชุดเดียวกับที่ทำมาแล้ว (sed) ได้ทันที
— โครง `GuestHooks` เตรียมช่องไว้แล้ว ไม่ต้องแก้ `GuestLauncher` อีก

---

## 6. ข้อสังเกตเรื่องความน่าเชื่อถือของหลักฐาน

- §7–§14 ของ DESIGN.md อ้างการวัดสด (`live-2026-10-20/evidence/INDEX v2/v3`) แต่ **โฟลเดอร์ evidence ไม่มีในรีโป**
  ตัวเลขที่อ้าง (ไฟล์ plugin 9,533,457 B, sha `0553f818…`, 20 libs/102 MB, libmkos.so 13.7MB)
  จึงเป็น **คำกล่าวอ้าง ไม่ใช่สิ่งที่ตรวจซ้ำได้จากรีโป**
- สิ่งที่ตรวจซ้ำได้จริง = โค้ดที่พอร์ตมาแล้ว (`container/*` 15 ไฟล์) — และมันสอดคล้องกับคำอธิบาย
  (ชื่อ method, ลำดับ step, ชื่อไฟล์ conf, รูปแบบ log) จึงสรุปได้ว่า **โครงที่ port ตรงกับที่ docs อธิบาย**
  แต่ *ผลรันจริงบนเครื่อง* ยังไม่มีใครยืนยันในรอบนี้
- `pluginUrl` ใน `TargetRegistry` เป็น URL ตรงไปยัง `koscheat.xyz` — โปรเจกต์นี้เก็บไว้ตามต้นทาง
  (ไม่ได้เรียกในเส้นทาง boot ถ้าไม่กด "Prepare plugin")
