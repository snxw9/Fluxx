"""Generate once, retain identical source bytes for both NDK exports. Requires FFmpeg."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    target = root / "app/src/androidTest/assets/e1a"
    target.mkdir(parents=True, exist_ok=True)
    media = target / "source.mp4"
    # Refuse overwrites: source bytes must not drift between baseline and candidate.
    command = [args.ffmpeg, "-nostdin", "-n", "-f", "lavfi", "-i",
               "testsrc2=size=640x360:rate=30:duration=6", "-f", "lavfi", "-i",
               "sine=frequency=440:sample_rate=48000:duration=6", "-t", "6",
               "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-g", "30",
               "-bf", "0", "-crf", "18", "-c:a", "aac", "-ar", "48000", "-ac", "2",
               "-b:a", "192k", "-movflags", "+faststart", str(media)]
    version = subprocess.check_output([args.ffmpeg, "-version"], text=True).splitlines()[0]
    subprocess.run(command, check=True)
    manifest = dict(schema=1, fixture="e1a-mixed-v1", ffmpeg=version, command=command,
                    sha256=hashlib.sha256(media.read_bytes()).hexdigest(),
                    duration_us=6000000, frames=180, width=640, height=360, sample_rate=48000)
    (target / "source.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Retain {media} and source.json unchanged through E1a verification.")


if __name__ == "__main__":
    main()
