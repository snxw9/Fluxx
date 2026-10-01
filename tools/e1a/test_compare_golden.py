"""Exercise comparison decisions with captured-format tool output, without FFmpeg."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import compare_golden


class GoldenComparisonTest(unittest.TestCase):
    def compare(self, candidate_psnr="inf", candidate_samples=288000, frames=180):
        with tempfile.TemporaryDirectory(prefix="fluxx-compare-test-") as directory:
            root = Path(directory)
            runs = [root / "r26", root / "r28"]
            for index, run in enumerate(runs):
                run.mkdir()
                metadata = dict(fixture="e1a-mixed-v1", project_sha256="same-model", device_fingerprint="same-device",
                                page_size=4096, ndk=["26.1.10909125", "28.2.13676358"][index], mix_sample_frames=288000)
                for name, key, data in (("source.mp4", "source_sha256", b"media"),
                                        ("checker.png", "image_sha256", b"image"),
                                        ("export.mp4", "export_sha256", b"export"),
                                        ("mix.s16le", "mix_sha256", bytes(288000 * 4))):
                    (run / name).write_bytes(data)
                    metadata[key] = hashlib.sha256(data).hexdigest()
                (run / "run.json").write_text(json.dumps(metadata))

            def fake_run(command, **kwargs):
                if "-version" in command:
                    return "ffmpeg test fixture\n"
                if "-show_streams" in command:
                    return json.dumps(dict(streams=[dict(codec_type="video", width=640, height=360),
                        dict(codec_type="audio", sample_rate="48000", channels=2, duration="6.000000")]))
                if "framehash" in command:
                    count = frames if "r28" in str(command[command.index("-i") + 1]) else 180
                    # Force PSNR path when testing non-identical frames.
                    value = "a" if candidate_psnr == "inf" or count == 180 and "r26" in str(command) else "b"
                    return "#tb 0: 1/30\n" + "\n".join(f"0, {i}, {i}, 1, 345600, {value}" for i in range(count))
                if "-lavfi" in command:
                    (Path(kwargs["cwd"]) / "psnr.log").write_text("\n".join(
                        f"n:{i + 1} psnr_avg:{candidate_psnr}" for i in range(min(frames, 180))))
                    return ""
                if "pcm_s16le" in command:
                    count = candidate_samples if "r28" in str(command[command.index("-i") + 1]) else 288000
                    Path(command[-1]).write_bytes(bytes(count * 4))
                    return ""
                raise AssertionError(command)

            report = root / "result.json"
            args = ["compare", "--baseline", str(runs[0]), "--candidate", str(runs[1]), "--json", str(report)]
            with patch.object(sys, "argv", args), patch.object(compare_golden, "run", side_effect=fake_run), contextlib.redirect_stdout(io.StringIO()):
                code = compare_golden.main()
            return code, json.loads(report.read_text())

    def test_equal_frames_and_audio_pass(self):
        code, report = self.compare()
        self.assertEqual(0, code)
        self.assertEqual(180, len(report["frames"]))

    def test_visually_changed_frame_fails(self):
        code, report = self.compare(candidate_psnr="32.0")
        self.assertEqual(1, code)
        self.assertFalse(report["frames"][0]["passed"])

    def test_lost_audio_samples_fail(self):
        code, report = self.compare(candidate_samples=287000)
        self.assertEqual(1, code)
        self.assertIn("Decoded audio sample counts differ", report["errors"])

    def test_lost_video_frame_fails(self):
        code, report = self.compare(frames=179)
        self.assertEqual(1, code)
        self.assertEqual([180, 179], report["frame_counts"])


if __name__ == "__main__":
    unittest.main()
