"""Read-only APK/native-input audit. Python 3 stdlib + Android SDK tools; no builds.

Exit 0: artifact checks pass; 1: incompatible artifact; 2: tooling/input error.
Runtime/device acceptance is deliberately independent of artifact compatibility.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

PAGE = 16384
ABIS = ("arm64-v8a", "x86_64")


def sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def inspect_elf(program_headers, dynamic):
    segments, errors = [], []
    for line in program_headers.splitlines():
        fields = line.split()
        if not fields or fields[0] not in ("LOAD", "GNU_RELRO"):
            continue
        if len(fields) < 8:
            raise ValueError(f"Unrecognized readelf segment: {line}")
        kind = fields[0]
        offset, address, _, file_size, memory_size = (int(x, 16) for x in fields[1:6])
        alignment = int(fields[-1], 16)
        segments.append(dict(type=kind, offset=offset, address=address,
                             file_size=file_size, memory_size=memory_size, alignment=alignment))
        if kind == "LOAD":
            if alignment < PAGE or alignment & (alignment - 1):
                errors.append(f"LOAD alignment {alignment} is not a power of two >= {PAGE}")
            if (address - offset) % PAGE:
                errors.append("LOAD virtual address/file offset not congruent modulo 16384")
        # PT_GNU_RELRO endpoints need not themselves be page-aligned. Bionic
        # rounds them with page_start/page_end before mprotect. Keep the segment
        # in the inventory; do not misclassify r28 libc++ as incompatible.
    if not any(x["type"] == "LOAD" for x in segments):
        errors.append("No LOAD segments found")
    if re.search(r"\bTEXTREL\b", dynamic):
        errors.append("Text relocations (DT_TEXTREL or DF_TEXTREL)")
    return dict(segments=segments, errors=errors, passed=not errors)


def inspect_file(path, readelf):
    result = inspect_elf(run([readelf, "-lW", path]), run([readelf, "-dW", path]))
    result["sha256"] = sha256(path)
    return result


def sdk_tools(sdk, ndk_version, build_tools):
    if tuple(int(x) for x in build_tools.split(".")) < (35, 0, 0):
        raise ValueError("build-tools >= 35.0.0 is required for zipalign -P 16")
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    suffix = ".exe" if os.name == "nt" else ""
    paths = dict(readelf=sdk / "ndk" / ndk_version / "toolchains/llvm/prebuilt" / host / "bin" / ("llvm-readelf" + suffix),
                 zipalign=sdk / "build-tools" / build_tools / ("zipalign" + suffix),
                 aapt2=sdk / "build-tools" / build_tools / ("aapt2" + suffix))
    for path in paths.values():
        if not path.is_file():
            raise FileNotFoundError(path)
    return paths


def inspect_apk(apk, tools, expected_abis):
    report = dict(path=str(apk.resolve()), sha256=sha256(apk), libraries=[], errors=[])
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="fluxx-elf-") as temp:
        names = set()
        for index, entry in enumerate(archive.infolist()):
            if not entry.filename.endswith(".so"):
                continue
            if entry.filename in names:
                report["errors"].append(f"Duplicate library entry: {entry.filename}")
            names.add(entry.filename)
            # Never extract ZIP paths directly: names may be untrusted.
            binary = Path(temp) / f"{index}.so"
            binary.write_bytes(archive.read(entry))
            item = inspect_file(binary, tools["readelf"])
            item.update(entry=entry.filename, compression=entry.compress_type)
            if entry.compress_type != zipfile.ZIP_STORED:
                item["errors"].append("Native library is compressed; expected uncompressed packaging")
            item["passed"] = not item["errors"]
            report["libraries"].append(item)
        for abi in expected_abis:
            if not any(name.startswith(f"lib/{abi}/") for name in names):
                report["errors"].append(f"Missing ABI: {abi}")
        if not names:
            report["errors"].append("No packaged .so files")
    try:
        report["zipalign"] = run([tools["zipalign"], "-c", "-P", "16", "-v", "4", apk])
    except RuntimeError as error:
        report["errors"].append(str(error))
    manifest = run([tools["aapt2"], "dump", "xmltree", apk, "--file", "AndroidManifest.xml"])
    attribute = next((line.strip() for line in manifest.splitlines() if "extractNativeLibs" in line), None)
    report["extractNativeLibs"] = attribute
    if attribute is None or not re.search(r"(?:\)\s*0x0\b|=\s*false\b)", attribute):
        report["errors"].append("Merged manifest does not explicitly set extractNativeLibs=false")
    report["passed"] = not report["errors"] and all(x["passed"] for x in report["libraries"])
    return report


def inspect_inputs(roots, readelf):
    """Explicit roots only: AARs, loose shared libraries and static archive inventory.

    AAR compression is irrelevant; ZIP alignment is checked only on final APKs.
    Static archives are inventoried, not claimed to be 16 KB aligned ELFs.
    """
    results = []
    for root in roots:
        if not root.exists():
            raise FileNotFoundError(root)
        files = sorted(root.rglob("*")) if root.is_dir() else [root]
        for path in files:
            if path.suffix == ".a":
                results.append(dict(path=str(path), kind="static_archive", sha256=sha256(path),
                                    note="Final shared-object link determines load alignment; inspect packaged .so"))
            elif path.suffix == ".so":
                results.append(dict(path=str(path), kind="shared_library", **inspect_file(path, readelf)))
            elif path.suffix == ".aar":
                item = dict(path=str(path), kind="aar", sha256=sha256(path), libraries=[])
                with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory(prefix="fluxx-aar-") as temp:
                    for index, entry in enumerate(archive.infolist()):
                        if entry.filename.endswith(".a"):
                            item["libraries"].append(dict(entry=entry.filename, kind="static_archive",
                                sha256=hashlib.sha256(archive.read(entry)).hexdigest()))
                        elif entry.filename.endswith(".so"):
                            binary = Path(temp) / f"{index}.so"
                            binary.write_bytes(archive.read(entry))
                            item["libraries"].append(dict(entry=entry.filename, kind="shared_library",
                                                         **inspect_file(binary, readelf)))
                results.append(item)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--ndk", default="28.2.13676358", help="SDK readelf version; not proof of APK compiler provenance")
    parser.add_argument("--build-tools", default="37.0.0")
    parser.add_argument("--abi", choices=ABIS, action="append", help="Expected ABI; defaults to both")
    parser.add_argument("--native-input", type=Path, action="append", default=[])
    parser.add_argument("--json", type=Path, required=True)
    args = parser.parse_args()
    report = dict(schema=1, gate="E1a", runtime="unverified", hardware_buffer_16k="unverified",
                  tool_ndk=args.ndk, build_tools=args.build_tools, apks=[], native_inputs=[], errors=[])
    code = 2
    try:
        if not args.apk and not args.native_input:
            raise ValueError("Supply --apk and/or --native-input")
        tools = sdk_tools(args.sdk, args.ndk, args.build_tools)
        report["tools"] = {name: str(path) for name, path in tools.items()}
        report["apks"] = [inspect_apk(apk, tools, args.abi or ABIS) for apk in args.apk]
        report["native_inputs"] = inspect_inputs(args.native_input, tools["readelf"])
        # Other AAR ABIs are inventory only: Fluxx packages arm64-v8a and x86_64.
        failures = [x for x in report["native_inputs"] if x.get("passed") is False]
        failures += [lib for item in report["native_inputs"] for lib in item.get("libraries", [])
                     if lib.get("passed") is False and any(abi in lib["entry"] for abi in ABIS)]
        report["passed"] = all(x["passed"] for x in report["apks"]) and not failures
        code = 0 if report["passed"] else 1
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        report["errors"].append(str(error))
        report["passed"] = False
    report["exit_code"] = code
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"E1a artifact audit: {'PASS' if code == 0 else 'FAIL' if code == 1 else 'ERROR'}; "
          f"{len(report['apks'])} APK(s), {len(report['native_inputs'])} native input(s).")
    for error in report["errors"]:
        print(error)
    for apk in report["apks"]:
        for error in apk["errors"]:
            print(f"{apk['path']}: {error}")
        for lib in apk["libraries"]:
            print(f"  {'PASS' if lib['passed'] else 'FAIL'} {lib['entry']}: {'; '.join(lib['errors'])}")
    for item in report["native_inputs"]:
        for lib in item.get("libraries", [item]):
            if "passed" in lib:
                print(f"  {'PASS' if lib['passed'] else 'FAIL'} {lib.get('entry', item['path'])}: {'; '.join(lib['errors'])}")
    print(f"JSON: {args.json.resolve()}\nRuntime and physical-device zero-copy verification remain separate.")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
