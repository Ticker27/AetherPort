# reflection hook ต้องไม่ถูก obfuscate/rename
-keep class com.virtualapp.mini.container.** { *; }
-keepclassmembers class com.virtualapp.mini.container.** { *; }

# framework classes ที่ถูกอ้างด้วยชื่อ string ใน GuestLauncher/ProcessAdoption
-keep class android.app.ActivityThread { *; }
-keep class android.app.LoadedApk { *; }
-keep class android.app.Application { *; }
-keep class dalvik.system.DexClassLoader { *; }

# json
-keep class org.json.** { *; }
