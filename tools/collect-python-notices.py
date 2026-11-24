"""Bundle the installed Android distributions' actual license and notice files."""
from pathlib import Path
import argparse
import email
import json
import shutil

parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
destination=root/'app/src/main/assets/python-licenses';destination.mkdir(parents=True,exist_ok=True)
folders=list((root/'app/build/python/pip/debug').glob('*/*.dist-info'))
site=args.source/'toolkit/Lib/site-packages'
for package in ('ppocr_onnx','paho_mqtt','onepush'):
    folders.extend(site.glob(package+'-*.dist-info'))
report=[]
for folder in folders:
    metadata=folder/'METADATA'
    if not metadata.is_file():continue
    content=email.message_from_bytes(metadata.read_bytes());name=content['Name'];version=content['Version']
    target=destination/f'{name}-{version}';target.mkdir(exist_ok=True)
    files=[]
    for path in folder.rglob('*'):
        if path.is_file() and path.name.upper().startswith(('LICENSE','COPYING','NOTICE','AUTHORS')):
            relative=path.relative_to(folder);out=target/relative;out.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,out);files.append(relative.as_posix())
    report.append(dict(name=name,version=version,license=content['License-Expression'] or content['License'],files=files))
(destination/'index.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'{len(report)} distribution records, {sum(len(entry["files"]) for entry in report)} license/notice copies (ABIs can share files)')
