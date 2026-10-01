"""Host-only verifier regressions; no Gradle, NDK compilation or Android runtime."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

from verify_native import inspect_apk, inspect_elf

GOOD = """
Type           Offset   VirtAddr           PhysAddr           FileSiz MemSiz  Flg Align
LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x01000 0x01000 R E 0x4000
LOAD           0x003000 0x0000000000007000 0x0000000000007000 0x01000 0x01000 RW  0x4000
GNU_RELRO      0x003000 0x0000000000007000 0x0000000000007000 0x01000 0x01000 R   0x1
"""


class NativeVerifierTest(unittest.TestCase):
    def test_aligned_load_and_relro_pass(self):
        self.assertTrue(inspect_elf(GOOD, "No TEXT tags here")["passed"])

    def test_4k_load_is_rejected(self):
        self.assertFalse(inspect_elf(GOOD.replace("0x4000", "0x1000"), "")["passed"])

    def test_incongruent_load_is_rejected(self):
        changed = GOOD.replace("LOAD           0x003000", "LOAD           0x002000")
        self.assertTrue(any("congruent" in x for x in inspect_elf(changed, "")["errors"]))

    def test_unaligned_relro_endpoint_is_not_a_load_alignment_failure(self):
        headers = GOOD + "GNU_RELRO 0x04dd20 0x0000000000051d20 0x0000000000051d20 0x3108 0x32e0 R 0x1\n"
        result = inspect_elf(headers, "")
        self.assertTrue(result["passed"])
        self.assertEqual("GNU_RELRO", result["segments"][-1]["type"])

    def test_both_textrel_encodings_fail(self):
        for dynamic in ("0x00000016 (TEXTREL) 0x0", "0x0000001e (FLAGS) TEXTREL BIND_NOW"):
            self.assertFalse(inspect_elf(GOOD, dynamic)["passed"])

    def test_no_load_does_not_false_pass(self):
        self.assertFalse(inspect_elf("Not an ELF", "")["passed"])

    def test_zip_manifest_and_every_library(self):
        def fake_run(command):
            if "-lW" in command:
                return GOOD
            if "-dW" in command:
                return ""
            if "xmltree" in command:
                return "A: android:extractNativeLibs(0x010104ea)=(type 0x12)0x0"
            return "Verification successful"

        with tempfile.TemporaryDirectory(prefix="fluxx-verifier-test-") as directory:
            apk = Path(directory) / "input.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libengine.so", b"fixture")
                archive.writestr("lib/x86_64/libengine.so", b"fixture")
                archive.writestr("lib/x86_64/libdependency.so", b"fixture", compress_type=zipfile.ZIP_DEFLATED)
            tools = dict(readelf="readelf", zipalign="zipalign", aapt2="aapt2")
            with patch("verify_native.run", side_effect=fake_run):
                result = inspect_apk(apk, tools, ("arm64-v8a", "x86_64"))
            self.assertFalse(result["passed"])
            self.assertEqual(3, len(result["libraries"]))
            self.assertIn("compressed", result["libraries"][2]["errors"][0])
            with patch("verify_native.run", side_effect=lambda cmd: "" if "xmltree" in cmd else fake_run(cmd)):
                missing_manifest = inspect_apk(apk, tools, ("armeabi-v7a",))
            self.assertTrue(any("Missing ABI" in x for x in missing_manifest["errors"]))
            self.assertTrue(any("extractNativeLibs" in x for x in missing_manifest["errors"]))


if __name__ == "__main__":
    unittest.main()
