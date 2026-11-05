import frida, sys, os, time

dev = frida.get_usb_device(timeout=5)
print("device:", dev.name)

SCRIPT = open(r"A:\WorkSpace\ai\sp_ImeHook\scripts\hot_switch.js", encoding="utf-8").read()
PREFIX = 'FSWITCH_ARGS = {"mode":-1,"slot":2,"table":""};\n'

try:
    ses = dev.attach("com.bytedance.android.doubaoime")
    print("attached pid=", ses._impl.pid)
    script = ses.create_script(PREFIX + SCRIPT)
    def on_msg(msg, data): print("MSG:", msg)
    script.on("message", on_msg)
    script.load()
    time.sleep(5)
    ses.detach()
    print("=== done ===")
except Exception as e:
    print("ERR:", type(e).__name__, e)
    import traceback; traceback.print_exc()
