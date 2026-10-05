"""Generate the checked-in Kotlin schema with legacy call defaults and the pinned runtime check."""
from pathlib import Path
import re
import subprocess
ROOT=Path(__file__).resolve().parents[2]
subprocess.run([str(ROOT/'flatc_dir/flatc.exe'), '--kotlin', '-o', str(ROOT/'app/src/main/kotlin'), str(ROOT/'app/src/main/fbs/project.fbs')],check=True)
for p in (ROOT/'app/src/main/kotlin/fluxx/schema').glob('*.kt'):
    s=p.read_text(encoding='utf-8')
    s=re.sub(r'FLATBUFFERS_\d+_\d+_\d+', 'FLATBUFFERS_25_2_10',s)
    if p.stem in ('StringKeyframe','AnimatableString'):
        s=s.replace('documentOffset: Int)', 'documentOffset: Int = 0)')
        # Missing legacy string fields legitimately decode to empty text.
        s=re.sub(r'(val (?:staticValue|value)AsByteBuffer : ByteBuffer)( get\(\))',r'\1?\2',s)
    if p.stem=='TextPayload':
        s=s.replace('ligaturesOffset: Int)', 'ligaturesOffset: Int = 0)')
    if p.stem=='TextFontReference':
        s=s.replace('styleNameOffset: Int)', 'styleNameOffset: Int = 0)')
    if p.stem=='TextRangeSelector':
        for field in ('kindOffset','wigglesPerSecondOffset','correlationOffset','temporalPhaseOffset','spatialPhaseOffset','lockDimensionsOffset','seedKeyframesOffset'):
            s=s.replace(field+': Int',field+': Int = 0')
    p.write_text(s,encoding='utf-8')
