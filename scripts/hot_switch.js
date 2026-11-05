// scripts/hot_switch.js
// Called by hot-switch.ps1 which injects FSWITCH_ARGS via -e.

function readArgs() {
    try { if (typeof FSWITCH_ARGS !== "undefined" && FSWITCH_ARGS) return JSON.parse(FSWITCH_ARGS); }
    catch (e) {}
    return { mode: 0, slot: 2, table: "" };
}

function findClass(name) {
    var cls = null;
    try { cls = Java.use(name); } catch (e) {}
    if (cls) return cls;
    // Xposed/LSPosed modules are loaded through BridgeClassLoader, not the app ClassLoader.
    var loaders = Java.enumerateClassLoadersSync();
    for (var i = 0; i < loaders.length; i++) {
        try {
            cls = Java.ClassFactory.get(loaders[i]).use(name);
            send("found " + name + " via loader " + loaders[i].toString());
            return cls;
        } catch (e) { cls = null; }
    }
    return null;
}

Java.perform(function () {
    var a = readArgs();
    send("args: mode=" + a.mode + " slot=" + a.slot + " tableLen=" + (a.table ? a.table.length : 0));

    var NativeBridge = findClass("lpt.imehook.NativeBridge");
    if (!NativeBridge) {
        send("ERROR: lpt.imehook.NativeBridge not found in any ClassLoader. "
             + "Module is NOT loaded into this process. Check LSPosed manager: enable module + "
             + "scope check for com.bytedance.android.doubaoime. Then force-stop the IME and respawn.");
        // diagnostic: list all loaded Java classes matching imehook
        try {
            var allCls = Java.enumerateLoadedClassesSync();
            var hits = allCls.filter(function (c) { return c.indexOf("imehook") !== -1; });
            send("loaded imehook classes: " + (hits.length ? hits.join(",") : "(none)"));
        } catch (e) {}
        return;
    }

    // mode < 0 = probe only, skip nativeConfigure
    if (a.mode < 0) { send("probe OK, module loaded correctly"); return; }

    NativeBridge.nativeConfigure(a.mode | 0, a.slot | 0, a.table ? a.table : "");
    send("OK: NativeBridge.nativeConfigure(" + a.mode + "," + a.slot + ",len="
         + (a.table ? a.table.length : 0) + ") called.");
});
