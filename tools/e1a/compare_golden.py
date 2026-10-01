"""Compare user-produced E1a r26/r28 result directories. No device/build operations.

Requires FFmpeg and FFprobe. JSON contains every frame's hashes and PSNR.
Exit 0 pass, 1 regression, 2 missing inputs/tool failure. Keep reports with both APKs.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import tempfile


def run(command, **kwargs):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed: {result.stderr}")
    return result.stdout


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def frame_hashes(ffmpeg, video):
    output = run([ffmpeg, "-nostdin", "-v", "error", "-i", video, "-map", "0:v:0", "-an",
                  "-pix_fmt", "yuv420p", "-fps_mode", "passthrough", "-f", "framehash", "-hash", "sha256", "-"])
    rows = []
    timebase = next((line.split(":", 1)[1].strip() for line in output.splitlines() if line.startswith("#tb 0:")), None)
    if not timebase:
        raise ValueError("FFmpeg framehash has no timebase")
    for line in output.splitlines():
        if line and not line.startswith("#"):
            fields = [x.strip() for x in line.split(",")]
            rows.append(dict(dts=int(fields[1]), pts=int(fields[2]), duration=int(fields[3]),
                             bytes=int(fields[4]), sha256=fields[5]))
    return dict(timebase=timebase, frames=rows)


def audio_metrics(ffmpeg, video, temp):
    target = temp / (video.parent.name + "-audio.s16le")
    # No resampling or channel conversion: format changes must be caught by probe.
    run([ffmpeg, "-nostdin", "-v", "error", "-i", video, "-map", "0:a:0", "-vn",
         "-c:a", "pcm_s16le", "-f", "s16le", target])
    size = target.stat().st_size
    if size % 4:
        raise ValueError("Decoded stereo PCM byte count is not divisible by four")
    return dict(sample_frames=size // 4, sha256=digest(target), seconds=(size // 4) / 48000)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--min-psnr", type=float, default=50.0)
    args = parser.parse_args()
    report = dict(schema=1, fixture="e1a-mixed-v1", frames=[], errors=[], min_psnr=args.min_psnr)
    code = 2
    try:
        if not math.isfinite(args.min_psnr) or args.min_psnr <= 0:
            raise ValueError("PSNR threshold must be finite and positive")
        directories = [args.baseline.resolve(), args.candidate.resolve()]
        if directories[0] == directories[1]:
            raise ValueError("Baseline and candidate directories must differ")
        metadata = [json.loads((directory / "run.json").read_text()) for directory in directories]
        report["runs"] = metadata
        report["ffmpeg"] = run([args.ffmpeg, "-version"]).splitlines()[0]
        for key in ("fixture", "project_sha256", "device_fingerprint", "page_size", "source_sha256", "image_sha256"):
            if metadata[0][key] != metadata[1][key]:
                report["errors"].append(f"Incomparable runs: {key} differs")
        if metadata[0]["fixture"] != "e1a-mixed-v1":
            report["errors"].append("Unknown fixture revision")
        if [x["ndk"] for x in metadata] != ["26.1.10909125", "28.2.13676358"]:
            report["errors"].append("Expected r26 baseline and r28c candidate")
        for directory, info in zip(directories, metadata):
            for filename, key in (("source.mp4", "source_sha256"), ("checker.png", "image_sha256"),
                                  ("export.mp4", "export_sha256"), ("mix.s16le", "mix_sha256")):
                if digest(directory / filename) != info[key]:
                    report["errors"].append(f"{directory.name}/{filename}: run manifest hash mismatch")
            if (directory / "mix.s16le").stat().st_size != 288000 * 4 or info["mix_sample_frames"] != 288000:
                report["errors"].append(f"{directory.name}: raw mix is not exactly 288000 stereo sample frames")
        if metadata[0]["mix_sha256"] != metadata[1]["mix_sha256"]:
            report["errors"].append("Pre-encode audio mix changed")
        videos = [directory / "export.mp4" for directory in directories]
        probes = [json.loads(run([args.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", video]))
                  for video in videos]
        report["probes"] = probes
        audio_streams = []
        for probe in probes:
            video_stream = next(x for x in probe["streams"] if x["codec_type"] == "video")
            audio_stream = next(x for x in probe["streams"] if x["codec_type"] == "audio")
            audio_streams.append(audio_stream)
            if (video_stream["width"], video_stream["height"]) != (640, 360):
                report["errors"].append("Unexpected export dimensions")
            if audio_stream["sample_rate"] != "48000" or audio_stream["channels"] != 2:
                report["errors"].append("Unexpected export audio format")
        if abs(float(audio_streams[0]["duration"]) - float(audio_streams[1]["duration"])) > 1 / 48000:
            report["errors"].append("Encoded audio duration differs by more than one sample")
        hashes = [frame_hashes(args.ffmpeg, video) for video in videos]
        report["frame_counts"] = [len(x["frames"]) for x in hashes]
        if report["frame_counts"] != [180, 180]:
            report["errors"].append("Expected exactly 180 frames in each export")
        if hashes[0]["timebase"] != hashes[1]["timebase"]:
            report["errors"].append("Video timebases differ")
        report["timebases"] = [x["timebase"] for x in hashes]
        with tempfile.TemporaryDirectory(prefix="fluxx-golden-") as workspace:
            temp = Path(workspace)
            graph = "[0:v]format=yuv420p,setpts=PTS-STARTPTS[a];[1:v]format=yuv420p,setpts=PTS-STARTPTS[b];[a][b]psnr=stats_file=psnr.log:shortest=1"
            run([args.ffmpeg, "-nostdin", "-v", "error", "-i", videos[0], "-i", videos[1],
                 "-lavfi", graph, "-an", "-f", "null", "-"], cwd=temp)
            stats = [dict(field.split(":", 1) for field in line.split())
                     for line in (temp / "psnr.log").read_text().splitlines()]
            if len(stats) != 180:
                report["errors"].append("PSNR comparison did not cover 180 frames")
            for index, (left, right, stat) in enumerate(zip(hashes[0]["frames"], hashes[1]["frames"], stats)):
                psnr = float(stat["psnr_avg"])
                timing_matches = all(left[key] == right[key] for key in ("pts", "dts", "duration", "bytes"))
                exact = left["sha256"] == right["sha256"]
                passed = timing_matches and (exact or psnr >= args.min_psnr)
                report["frames"].append(dict(index=index, baseline=left, candidate=right, exact=exact,
                                              psnr="inf" if math.isinf(psnr) else psnr, passed=passed))
                if not passed:
                    report["errors"].append(f"Frame {index}: timing mismatch or PSNR below threshold")
            # Separate filenames even if two result directories happen to share a basename.
            audio = []
            for index, video in enumerate(videos):
                audio_dir = temp / str(index)
                audio_dir.mkdir()
                audio.append(audio_metrics(args.ffmpeg, video, audio_dir))
            report["decoded_audio"] = audio
            if audio[0]["sample_frames"] != audio[1]["sample_frames"]:
                report["errors"].append("Decoded audio sample counts differ")
            if any(abs(item["sample_frames"] - 288000) > 1024 for item in audio):
                report["errors"].append("Decoded audio outside six seconds plus/minus one AAC frame")
        report["passed"] = not report["errors"]
        code = 0 if report["passed"] else 1
    except (OSError, ValueError, KeyError, StopIteration, RuntimeError) as error:
        report["errors"].append(str(error))
        report["passed"] = False
    report["exit_code"] = code
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"E1a golden comparison: {'PASS' if code == 0 else 'FAIL' if code == 1 else 'ERROR'}")
    for error in report["errors"]:
        print(error)
    print(f"Report: {args.json.resolve()}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
