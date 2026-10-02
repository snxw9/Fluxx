"""Summarize host spread-spike masks (differences, not ground-truth quality scores)."""
import argparse
import csv
import json
from pathlib import Path
import statistics
import struct
import zlib

def gray_png(path):
    raw = path.read_bytes()
    assert raw[:8] == b'\x89PNG\r\n\x1a\n'
    offset, data, width, height = 8, bytearray(), 0, 0
    while offset < len(raw):
        size = struct.unpack('>I', raw[offset:offset+4])[0]
        kind = raw[offset+4:offset+8]
        chunk = raw[offset+8:offset+8+size]
        if kind == b'IHDR':
            width, height, depth, colour, compression, filtering, interlace = struct.unpack('>IIBBBBB', chunk)
            assert (depth, colour, compression, filtering, interlace) == (8, 0, 0, 0, 0)
        elif kind == b'IDAT':
            data.extend(chunk)
        offset += size + 12
    scan = zlib.decompress(data)
    pixels = bytearray()
    for y in range(height):
        row = y*(width+1)
        assert scan[row] == 0, 'Expected unfiltered diagnostics PNG'
        pixels.extend(scan[row+1:row+1+width])
    return width, height, pixels

def summarize(root):
    samples = list(csv.DictReader((root/'samples.csv').open(encoding='utf-8')))
    differences = {size: [] for size in ('8','72','500')}
    for sample in samples:
        if sample['ppem'] != '48':
            continue
        stem = f"{sample['font']}-{sample['glyph']}-{{ppem}}-{sample['size']}-half{float(sample['half_em']):.6f}-soft{float(sample['requested_soft_em']):.6f}.png"
        w, h, a = gray_png(root/stem.format(ppem=48))
        w2, h2, b = gray_png(root/stem.format(ppem=64))
        assert (w,h) == (w2,h2)
        active = sum(x != 0 or y != 0 for x,y in zip(a,b))
        error = sum(abs(x-y) for x,y in zip(a,b))
        differences[sample['size']].append(error/max(active,1)/255)
    capacity = list(csv.DictReader((root/'capacity.csv').open(encoding='utf-8')))
    return {
        'kind': 'CPU-reference mask comparison; not Vulkan/A16 or ground-truth quality',
        'sample_count': len(samples),
        'capacity': capacity,
        'bitmap_area_ratio': int(capacity[1]['total_bitmap_area'])/int(capacity[0]['total_bitmap_area']),
        'maximum_canvas_edge_coverage_byte': max(int(s['edge_max']) for s in samples),
        'mask_differences': {size: {
            'pairs': len(values),
            'mean_absolute_difference_on_union_nonzero_pixels': statistics.mean(values),
            'median_absolute_difference_on_union_nonzero_pixels': statistics.median(values),
            'max_absolute_difference_on_union_nonzero_pixels': max(values),
        } for size,values in differences.items()},
    }

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    args = parser.parse_args()
    result = summarize(args.directory)
    (args.directory/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
