#!/usr/bin/env python3
"""Refresh fixture integrity manifest after an intentional fixture change."""
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
out=root/'data/attachments'
files=[]
for p in sorted(out.rglob('*')):
    if not p.is_file() or p.suffix.lower() not in ['.xls','.xlsx','.doc','.docx','.pdf','.jpg','.jpeg']:
        continue
    expected=('REJECT' if 'invalid' in p.parts else 'NEEDS_REVIEW' if p.stem in ['product-only','blurred','rotated','scan','mixed','label'] else 'EXTRACT_ROWS')
    # Expected content is independent of recognition confidence. Clear scan/label
    # has known text; product-only and blur must not create confident SKU matches.
    has_rows=p.stem not in ['product-only','blurred','corrupt','renamed-executable']
    files.append({'file':str(p.relative_to(root)),'family':p.suffix[1:],'expected':expected,
                  'expectedRows':'data/expected/attachment-rows.json' if has_rows else None,
                  'expectedRowOccurrences':2 if p.stem=='mixed' else 1 if has_rows else 0,
                  'synthetic':True,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(out/'manifest.json').write_text(json.dumps({'synthetic':True,'version':'attachments-v1','files':files},ensure_ascii=False,indent=2)+'\n')
print(f'Updated {len(files)} fixture checksums')
