"""One-time maintainer download; never invoked by Gradle/CMake. Hashes are recorded on vendoring."""
import concurrent.futures
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/text/results/downloads"
OUT.mkdir(parents=True, exist_ok=True)


def download(url, name):
    target = OUT / name
    if not target.exists():
        request = urllib.request.Request(url, headers={"User-Agent": "Fluxx-text-vendor-audit"})
        with urllib.request.urlopen(request, timeout=120) as source:
            data = source.read()
        target.write_bytes(data)
    print(name, target.stat().st_size, flush=True)


def release(repo, tag):
    url = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Fluxx-text-vendor-audit"}), timeout=60) as response:
        data = json.load(response)
    (OUT / (repo.split('/')[-1] + '.json')).write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(repo, [(a['name'], a['browser_download_url']) for a in data['assets']], flush=True)
    for asset in data['assets']:
        if asset['name'].endswith('.zip'):
            download(asset['browser_download_url'], asset['name'])


def commit(repo, tag):
    request = urllib.request.Request(f'https://api.github.com/repos/{repo}/git/ref/tags/{tag}', headers={'User-Agent': 'Fluxx-text-vendor-audit'})
    with urllib.request.urlopen(request, timeout=60) as response:
        obj = json.load(response)['object']
    while obj['type'] == 'tag':
        with urllib.request.urlopen(urllib.request.Request(obj['url'], headers={'User-Agent': 'Fluxx-text-vendor-audit'}), timeout=60) as response:
            obj = json.load(response)['object']
    (OUT / (repo.split('/')[-1] + '-commit.json')).write_text(json.dumps(dict(repo=repo, tag=tag, commit=obj['sha']), indent=2) + '\n')


if __name__ == "__main__":
    tasks = [(download, ("https://codeload.github.com/freetype/freetype/tar.gz/refs/tags/VER-2-14-1", "freetype-2.14.1.tar.gz")),
             (download, ("https://codeload.github.com/harfbuzz/harfbuzz/tar.gz/refs/tags/14.3.1", "harfbuzz-14.3.1.tar.gz")),
             (release, ("rsms/inter", "v4.1")),
             (release, ("JetBrains/JetBrainsMono", "v2.304")),
             (release, ("notofonts/latin-greek-cyrillic", "NotoSerif-v2.014"))]
    tasks += [(commit, pair) for pair in [('freetype/freetype', 'VER-2-14-1'), ('harfbuzz/harfbuzz', '14.3.1'),
              ('rsms/inter', 'v4.1'), ('JetBrains/JetBrainsMono', 'v2.304'), ('notofonts/latin-greek-cyrillic', 'NotoSerif-v2.014')]]
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        futures = [pool.submit(fn, *args) for fn, args in tasks]
        for future in futures:
            future.result()
