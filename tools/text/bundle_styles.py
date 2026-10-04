"""Vendor real static style faces from the already pinned OFL release archives."""
import json
from pathlib import Path
from vendor_inputs import font
ROOT=Path(__file__).resolve().parents[2]
p=ROOT/'core-engine/third_party/TEXT_INPUTS.json'
manifest=json.loads(p.read_text())
extra=[]
for family,base,archive,folder,license in [
    ('fluxx.sans','Inter','Inter-4.1.zip','extras/ttf','LICENSE.txt'),
    ('fluxx.serif','NotoSerif','NotoSerif-v2.014.zip','NotoSerif/unhinted/ttf','OFL.txt'),
    ('fluxx.mono','JetBrainsMono','JetBrainsMono-2.304.zip','fonts/ttf','OFL.txt')]:
    for style,suffix in [('Bold','bold'),('Italic','italic'),('BoldItalic','bold-italic')]:
        name=base+'-'+style
        meta=font(archive,folder+'/'+name+'.ttf',license,name)
        for key in ('repo','tag','commit'): meta[key]=manifest['fonts'][family][key]
        manifest['fonts'][family+'.'+suffix]=meta
        extra.extend([ROOT/'core-engine/src/main/assets/fonts'/ (name+'.ttf'),ROOT/'core-engine/src/main/assets/licenses'/(name+'-OFL.txt')])
p.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
import io,zipfile
compressed=io.BytesIO()
with zipfile.ZipFile(compressed,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for asset in extra: z.write(asset,'assets/'+asset.parent.name+'/'+asset.name)
report={'added_faces':9,'uncompressed_asset_delta_bytes':sum(p.stat().st_size for p in extra),'deflate6_zip_asset_delta_bytes':len(compressed.getvalue()),'actual_apk_delta':'unmeasured: APK packaging is outside compile-only authorization'}
(ROOT/'tools/text/results/t1-font-size.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
