#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check the exact APKs and declared DEX contracts used by the coexistence build.

This is a static check, not an Android runtime or Bluetooth test. No APK is modified.
"""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


def declared_methods(apk, wanted):
    found = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = archive.read(name)

            def u32(off):
                return struct.unpack_from("<I", data, off)[0]

            def uleb(off):
                value = shift = 0
                while True:
                    part = data[off]
                    off += 1
                    value |= (part & 127) << shift
                    if part < 128:
                        return value, off
                    shift += 7

            cache = {}

            def string(index):
                if index not in cache:
                    _, start = uleb(u32(u32(60) + 4 * index))
                    cache[index] = data[start:data.index(0, start)].decode("utf-8", "replace")
                return cache[index]

            def typ(index):
                return string(u32(u32(68) + 4 * index))

            for i in range(u32(96)):
                off = u32(100) + 32 * i
                owner = typ(u32(off))[1:-1].replace("/", ".")
                if owner not in wanted:
                    continue
                parent = u32(off + 8)
                entry = {"dex": name, "methods": [], "fields": [], "superclass": "" if parent == 0xffffffff
                         else typ(parent)[1:-1].replace("/", ".")}
                found[owner] = entry
                body = u32(off + 24)
                if not body:
                    continue
                sizes = []
                for _ in range(4):
                    size, body = uleb(body)
                    sizes.append(size)
                for size in sizes[:2]:
                    idx = 0
                    for _ in range(size):
                        delta, body = uleb(body)
                        _, body = uleb(body)
                        idx += delta
                        field = u32(84) + 8 * idx
                        entry["fields"].append(string(u32(field + 4)) + ":" + typ(struct.unpack_from("<H", data, field + 2)[0]))
                for size in sizes[2:]:
                    idx = 0
                    for _ in range(size):
                        delta, body = uleb(body)
                        _, body = uleb(body)
                        _, body = uleb(body)
                        idx += delta
                        _, proto, label = struct.unpack_from("<HHI", data, u32(92) + 8 * idx)
                        proto_off = u32(76) + 12 * proto
                        params = u32(proto_off + 8)
                        args = "" if not params else "".join(
                            typ(struct.unpack_from("<H", data, params + 4 + j * 2)[0])
                            for j in range(u32(params)))
                        entry["methods"].append(string(label) + "(" + args + ")" + typ(u32(proto_off + 4)))
    return found


def has_method(classes, owner, expected):
    seen = set()
    while owner in classes and owner not in seen:
        seen.add(owner)
        entry = classes[owner]
        if any(actual.startswith(expected) if expected.endswith(")") else actual == expected
               for actual in entry["methods"]):
            return True
        if expected.startswith("<init>"):
            return False
        owner = entry["superclass"]
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oppo", required=True, type=Path)
    parser.add_argument("--mi", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--spec", type=Path, default=Path(__file__).resolve().parents[1] / "compat/coexist-hosts.json")
    args = parser.parse_args()
    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    if "extends" in spec:
        base = json.loads((args.spec.parent / spec["extends"]).read_text(encoding="utf-8"))
        for key in ("oppo", "mi"):
            changes = spec.get(key, {})
            target = base[key]
            for name in changes.get("removeClasses", []):
                target["classes"].pop(name, None)
            target["classes"].update(changes.get("classes", {}))
            target.update({name: value for name, value in changes.items() if name not in ("classes", "removeClasses")})
        spec = base
    reports = []
    for key, path in (("oppo", args.oppo), ("mi", args.mi)):
        target = spec[key]
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        wanted = set(target["classes"]) | set(target.get("fields", {}))
        classes = declared_methods(path, wanted)
        missing = []
        count = 0
        for name, methods in target["classes"].items():
            if name not in classes:
                missing.append(name)
                continue
            for method in methods:
                count += 1
                if not has_method(classes, name, method):
                    missing.append(name + "." + method)
        fields_checked = 0
        for name, fields in target.get("fields", {}).items():
            for field in fields:
                fields_checked += 1
                if field not in classes.get(name, {}).get("fields", []):
                    missing.append(name + "." + field)
        report = {"package": target["package"], "version": target["version"], "sha256": digest,
                  "hashMatches": digest == target["sha256"], "classesChecked": len(target["classes"]),
                  "methodsChecked": count, "missing": missing, "runtimeVerified": False,
                  "dexLocations": {name: item["dex"] for name, item in classes.items()}}
        if fields_checked:
            report["fieldsChecked"] = fields_checked
        report["passed"] = report["hashMatches"] and not missing
        reports.append(report)
        print(f'{target["package"]}: {"PASS" if report["passed"] else "FAIL"}; '
              f'{len(classes)}/{len(target["classes"])} classes; {count} method contracts')
        for item in missing:
            print("  MISSING " + item)
        if not report["hashMatches"]:
            print("  APK hash differs from the supplied build")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(reports, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    raise SystemExit(0 if all(report["passed"] for report in reports) else 1)


if __name__ == "__main__":
    main()
