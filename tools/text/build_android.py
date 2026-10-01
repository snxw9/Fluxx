"""Requested compile checks only: no APK packaging, installation or device execution."""
import argparse
import os
import re
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true')
    parser.add_argument('--refresh-header-checks', action='store_true', help='Rerun only failed FreeType header probes after fixing compiler flags')
    args = parser.parse_args()
    env = {key.upper(): value for key, value in os.environ.items()}
    env['JAVA_HOME'] = 'C:/Program Files/Android/Android Studio/jbr'
    env['CMAKE_BUILD_PARALLEL_LEVEL'] = '2'
    if args.refresh_header_checks:
        cmake = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/cmake/3.22.1/bin/cmake.exe'
        for cache in (ROOT / 'core-engine/.cxx').glob('*/*/*/CMakeCache.txt'):
            if 'FT_DISABLE_HARFBUZZ:BOOL=ON' in cache.read_text():
                subprocess.run([str(cmake), '-S', str(ROOT / 'core-engine'), '-B', str(cache.parent),
                                '-U', 'HAVE_UNISTD_H', '-U', 'HAVE_FCNTL_H'], env=env, check=True)
    launcher = ROOT / 'gradlew.bat'
    if not launcher.is_file():
        properties = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
        version = re.search(r'gradle-([0-9.]+)-bin\.zip', properties).group(1)
        cached = Path(os.environ['USERPROFILE']) / '.gradle/wrapper/dists' / f'gradle-{version}-bin'
        launchers = list(cached.glob(f'*/gradle-{version}/bin/gradle.bat'))
        if len(launchers) != 1:
            raise RuntimeError('Install the configured Gradle distribution using Android Studio first')
        launcher = launchers[0]
    tasks = [':core-engine:externalNativeBuildRelease'] if args.release else [
        ':core-engine:externalNativeBuildDebug', ':core-engine:compileDebugKotlin',
        ':core-engine:compileDebugAndroidTestKotlin', ':app:compileDebugKotlin']
    log = ROOT / 'tools/text/results' / ('android-release.log' if args.release else 'android-debug.log')
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w', encoding='utf-8') as output:
        code = subprocess.call(['cmd', '/d', '/c', str(launcher), *tasks,
                                '--no-daemon', '--console=plain', '--max-workers=2'], cwd=ROOT,
                               env=env, stdout=output, stderr=subprocess.STDOUT)
    print(log.read_text(encoding='utf-8', errors='replace')[-16000:])
    raise SystemExit(code)
