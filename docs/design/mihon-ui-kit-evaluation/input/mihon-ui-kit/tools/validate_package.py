#!/usr/bin/env python3
"""Structural checks only. Never presents these checks as Kotlin/Android compilation or pixel verification."""
from pathlib import Path
import json,re,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'docs/ui/catalog-manifest.json').read_text())
checks=[]
def record(name,ok):
    checks.append({'name':name,'status':'PASS' if ok else 'FAIL'})
    if not ok: print('FAIL:',name)
ds=manifest['categories']
record('32 ordered categories', [d['number'] for d in ds]==list(range(1,33)))
rules=[r['id'] for d in ds for r in d['rules']]; cases=[c['id'] for d in ds for c in d['cases']]
record('128 unique rule IDs',len(rules)==len(set(rules))==128)
record('128 unique declared cases',len(cases)==len(set(cases))==128)
record('native cases remain NOT_RUN',all(c['status']=='NOT_RUN' for d in ds for c in d['cases']))
record('every case has preconditions steps expected',all(c['setup'] and c['steps'] and c['expected'] for d in ds for c in d['cases']))
resources=[]
for locale in ('values','values-zh-rCN'):
    p=root/f'overlay/app/src/debug/res/{locale}/ui_catalog_strings.xml'
    names=[x.attrib['name'] for x in ET.parse(p).getroot()]
    record(f'{locale} has unique resource names',len(names)==len(set(names)))
    resources.append(set(names))
record('resource keys match across locales',resources[0]==resources[1])
kt=list((root/'overlay').rglob('*.kt'))
refs=set()
for f in kt:
    refs.update(re.findall(r'R\.string\.(\w+)',f.read_text()))
record('all debug R.string references defined',refs<=resources[0])
prod=[p for p in (root/'overlay').rglob('*') if p.is_file() and '/src/main/' in str(p)]
record('no production source-set modifications',not prod)
source='\n'.join(p.read_text() for p in (root/'overlay/app/src/debug/java').rglob('*.kt'))
# Specific known side-effect entry points only; this is not a full data-flow/security audit.
for forbidden in ('contentResolver.', 'DownloadManager.', 'WorkManager.', 'navigator.push(', 'startActivity(', 'FileOutputStream', 'OkHttpClient', 'appGraph', 'Injekt.get'):
    record('fixture has no direct '+forbidden, forbidden not in source)
record('all 32 entries registered',len(re.findall(r'CategorySpec\(\d+,',source))==32)
record('snapshot saver used by native screen','stateSaver = DemoStateSaver' in source)
record('native AppBar is reused','import eu.kanade.presentation.components.AppBar' in source)
record('native SearchToolbar is reused','import eu.kanade.presentation.components.SearchToolbar' in source)
record('native Scaffold is reused','import tachiyomi.presentation.core.components.material.Scaffold' in source)
record('preference adapter uses memory store','class MemoryPreference<T>' in source)
record('native tests declared',len(re.findall(r'@Test fun ',''.join(p.read_text() for p in kt)))==10)
ns={'a':'http://schemas.android.com/apk/res/android'}
activity=ET.parse(root/'overlay/app/src/debug/AndroidManifest.xml').find('application/activity')
record('only debug launcher declares activity',activity is not None and activity.attrib.get('{'+ns['a']+'}name')=='eu.kanade.tachiyomi.uicatalog.UiCatalogActivity')
result={'kind':'PACKAGE_STRUCTURE_ONLY','passed':sum(c['status']=='PASS' for c in checks),'failed':sum(c['status']=='FAIL' for c in checks),
'android_build':'NOT_RUN','native_render':'NOT_RUN','checks':checks}
(root/'verification/structure-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(f"Structure: {result['passed']} passed, {result['failed']} failed. No native compilation/rendering was performed.")
raise SystemExit(bool(result['failed']))
