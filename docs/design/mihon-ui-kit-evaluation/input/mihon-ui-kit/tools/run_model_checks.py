#!/usr/bin/env python3
"""Compile and exercise the same pure Kotlin model used by the debug catalog. Not an Android test."""
from pathlib import Path
import json,shutil,subprocess,sys,tempfile,datetime
root=Path(__file__).resolve().parents[1]
report=root/'verification/model-results.json'
compiler=shutil.which('kotlinc'); java=shutil.which('java')
if not compiler or not java:
    print('BLOCKED: kotlinc and java are required. No dependency is downloaded automatically.',file=sys.stderr);sys.exit(2)
with tempfile.TemporaryDirectory(prefix='mihon-ui-model-') as td:
    jar=Path(td)/'checks.jar'
    model=root/'overlay/app/src/debug/java/eu/kanade/tachiyomi/uicatalog/CatalogModel.kt'
    command=[compiler,str(model),str(root/'verification/ContractChecks.kt'),'-include-runtime','-d',str(jar)]
    compiled=subprocess.run(command,text=True,capture_output=True,timeout=120)
    (root/'verification/model-compile.log').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:
        print(compiled.stderr,file=sys.stderr);sys.exit(compiled.returncode)
    ran=subprocess.run([java,'-jar',str(jar)],text=True,capture_output=True,timeout=30)
    (root/'verification/model-run.log').write_text(ran.stdout+ran.stderr)
    rows=[]
    for line in ran.stdout.splitlines():
        parts=line.split('\t')
        if parts[0] in ('PASS','FAIL'): rows.append(dict(status=parts[0],name=parts[1],detail=parts[2:]))
    result={'kind':'JVM_FIXTURE_CONTRACTS_ONLY','android_ui_status':'NOT_RUN','production_integration_status':'NOT_RUN',
        'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'passed':sum(x['status']=='PASS' for x in rows),'failed':sum(x['status']=='FAIL' for x in rows),'checks':rows,
        'compiler':subprocess.run([compiler,'-version'],capture_output=True,text=True).stderr.strip()}
    report.write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(f"JVM fixture checks: {result['passed']} passed, {result['failed']} failed. Android UI: NOT_RUN.")
    sys.exit(ran.returncode)
