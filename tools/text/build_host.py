"""Build the standalone CPU proof using installed MSVC/CMake; no Android/device operation."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cmake', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/cmake/3.22.1/bin/cmake.exe'))
    args = parser.parse_args()
    # Some Windows launchers inject both PATH and Path; MSBuild rejects that environment.
    # Normalize the child environment only, without changing the user's system settings.
    env = {key.upper(): value for key, value in os.environ.items()}
    build = ROOT / 'tools/text/results/host-build'
    build.mkdir(parents=True, exist_ok=True)
    commands = [[args.cmake, '-S', str(ROOT / 'tools/text'), '-B', str(build), '-G', 'Visual Studio 17 2022', '-A', 'x64'],
                [args.cmake, '--build', str(build), '--config', 'Release', '--parallel', '2']]
    for index, command in enumerate(commands):
        log = build.parent / f'host-build-{index}.log'
        with log.open('w', encoding='utf-8') as output:
            code = subprocess.call(command, stdout=output, stderr=subprocess.STDOUT, env=env)
        print(log.read_text(encoding='utf-8', errors='replace')[-10000:], flush=True)
        if code:
            return code
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
