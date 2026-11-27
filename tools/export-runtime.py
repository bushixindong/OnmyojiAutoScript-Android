"""Copy the complete OAS task engine into an isolated, hash-audited APK asset.

Only source/resources and selected installed libraries are copied. Personal config,
logs, account profiles, Git files and interpreter caches are never included.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import sys
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--source',required=True,type=Path)
args=parser.parse_args()
source=args.source.resolve()
root=Path(__file__).resolve().parents[1]
destination=root/'app/src/main/assets/runtime'
destination.mkdir(parents=True,exist_ok=True)
allowed={'.py','.json','.png','.webp','.jpg','.jpeg','.gif','.yaml','.yml','.txt','.ttf','.otf','.onnx','.npy','.npz','.csv'}
excluded={'__pycache__','.git','log','logs','real_matches','records','captures'}
excluded_paths={'tasks/Hyakkiyakou/model/tracker.onnx'}
entries={}
for folder in ('module','tasks','assets','deploy'):
    for path in sorted((source/folder).rglob('*')):
        rel=path.relative_to(source).as_posix()
        if path.is_file() and path.suffix.lower() in allowed and rel not in excluded_paths and not excluded.intersection(path.relative_to(source).parts):
            entries[rel]=path
for filename in ('LICENSE','LICENSE.txt','script.py','config/deploy.template.yaml'):
    if (source/filename).is_file():entries[filename]=source/filename
entries['dev_tools/assets_test.py']=source/'dev_tools/assets_test.py'
site=source/'toolkit/Lib/site-packages'
for package in ('ppocronnx','paho','onepush'):
    for path in sorted((site/package).rglob('*')):
        if path.is_file() and path.suffix.lower() in allowed and '__pycache__' not in path.parts:
            entries[path.relative_to(site).as_posix()]=path
manifest=[]
imports=set()
with zipfile.ZipFile(destination/'oas-runtime.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name,path in entries.items():
        data=path.read_bytes()
        # These two upstream imports are unused by the actual task classes and
        # otherwise initialize PC-only backends. Record each source edit below.
        removed_imports={
            'tasks/Component/SwitchAccount/switch_account.py':b'from adbutils import device',
            'tasks/Quiz/script_task.py':b'from module.device.screenshot import Screenshot',
        }
        if name in removed_imports:data=data.replace(removed_imports[name],b'# Android: unused desktop-only import removed')
        archive.writestr(name,data)
        manifest.append(dict(path=name,size=len(data),sha256=hashlib.sha256(data).hexdigest()))
        if path.suffix=='.py':
            for node in ast.walk(ast.parse(data.decode('utf-8-sig'))):
                if isinstance(node,ast.Import):imports.update(item.name.split('.')[0] for item in node.names)
                elif isinstance(node,ast.ImportFrom) and node.level==0 and node.module:imports.add(node.module.split('.')[0])
tasks=sorted(path.parent.name for path in (source/'tasks').glob('*/script_task.py'))
report=dict(tasks=tasks,taskCount=len(tasks),fileCount=len(manifest),unpackedBytes=sum(item['size'] for item in manifest),
            portabilityEdits={name:line.decode() for name,line in removed_imports.items()},
            archiveSha256=hashlib.sha256((destination/'oas-runtime.zip').read_bytes()).hexdigest(),
            thirdPartyImports=sorted(imports-sys.stdlib_module_names-{'module','tasks','assets','deploy','ppocronnx','oashya'}),files=manifest)
(destination/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({key:report[key] for key in ('taskCount','fileCount','unpackedBytes','thirdPartyImports')},ensure_ascii=False))
