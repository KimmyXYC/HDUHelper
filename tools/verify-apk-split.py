#!/usr/bin/env python3
"""Verify that only the companion APK defines Xposed hooks and module metadata."""
import argparse
import struct
import zipfile

ENTRIES = {
    "moe.nepnep.hduhelper.data.island.xposed.IslandModule",
    "moe.nepnep.hduhelper.data.background.xposed.BackgroundModule",
}


def defined_classes(apk):
    result = set()
    for name in apk.namelist():
        if not (name.startswith("classes") and name.endswith(".dex")):
            continue
        data = apk.read(name)
        assert data.startswith(b"dex\n"), "Unsupported DEX format"
        def number(offset):
            return struct.unpack_from("<I", data, offset)[0]
        strings = []
        for i in range(number(56)):
            offset = number(number(60) + i * 4)
            while data[offset] & 0x80:
                offset += 1
            offset += 1
            strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
        types = [strings[number(number(68) + i * 4)] for i in range(number(64))]
        result.update(types[number(number(100) + i * 32)] for i in range(number(96)))
    return result


def verify(app_path, module_path):
    with zipfile.ZipFile(app_path) as app, zipfile.ZipFile(module_path) as module:
        assert not any(name.startswith("META-INF/xposed/") for name in app.namelist()), "Main APK still has module metadata"
        app_classes = defined_classes(app)
        module_classes = defined_classes(module)
        assert not any("/xposed/" in name or name.startswith("Lio/github/libxposed/") for name in app_classes), "Main APK still defines Xposed code"
        entries = set(module.read("META-INF/xposed/java_init.list").decode().splitlines())
        assert entries == ENTRIES, "Unexpected module entry points"
        for entry in entries:
            assert "L" + entry.replace(".", "/") + ";" in module_classes, f"Missing or renamed entry: {entry}"
        assert set(module.read("META-INF/xposed/scope.list").decode().splitlines()) == {"system", "com.android.systemui", "miui.systemui.plugin"}
        assert b"minApiVersion=101" in module.read("META-INF/xposed/module.prop")
        assert not any(name.startswith("Lmoe/nepnep/hduhelper/data/auth/") for name in module_classes), "Companion includes authentication code"
    print("APK split verified: main has no Xposed code; companion has both entry points and all scopes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app")
    parser.add_argument("module")
    args = parser.parse_args()
    verify(args.app, args.module)
