"""Read OASX Dart string maps and branding assets without importing/running OASX."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import pprint
import re
import shutil

parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);parser.add_argument('--release',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
constants={name:value for name,value in re.findall(r"(\w+)\s*=\s*'([^']*)'",(args.source/'lib/translation/i18n_content.dart').read_text(encoding='utf-8'))}
literal=r"'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\""
pattern=re.compile(r"(?P<key>I18n\.\w+|'(?:\\.|[^'\\])*')\s*:\s*(?P<value>(?:(?:"+literal+r")\s*)+)")
translations={};sources=[]
for path in sorted((args.source/'lib/translation/cn_parts').glob('*.dart')):
    text=path.read_text(encoding='utf-8')
    sources.append(dict(path=path.relative_to(args.source).as_posix(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    for match in pattern.finditer(text):
        key=match['key'];key=constants.get(key[5:]) if key.startswith('I18n.') else ast.literal_eval(key)
        if not key:continue
        try:value=''.join(ast.literal_eval(token.group()) for token in re.finditer(literal,match['value']))
        except (ValueError,SyntaxError):continue
        translations[key]=value
target=root/'app/src/main/python/android_oas/oasx_labels.py'
target.write_text('# SPDX-License-Identifier: GPL-3.0-only\n# Generated from OASX/lib/translation/cn_parts. See UI_SOURCE.md.\nTRANSLATIONS = '+pprint.pformat(translations,width=120,sort_dicts=True)+'\n',encoding='utf-8')
destination=root/'app/src/main/res/drawable-nodpi';destination.mkdir(parents=True,exist_ok=True)
logo=args.release/'data/flutter_assets/assets/images/Icon-app.png'
shutil.copyfile(logo,destination/'oasx_logo.png')
notice=root/'app/src/main/assets/OASX-LICENSE.txt';shutil.copyfile(args.source/'LICENSE',notice)
manifest=dict(source=str(args.source),release=str(args.release),translationCount=len(translations),logoSha256=hashlib.sha256(logo.read_bytes()).hexdigest(),files=sources)
(root/'app/src/main/assets/oasx-ui-source.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(translations=len(translations),examples={key:translations.get(key) for key in ('DailyTrifles','Orochi','FrogBoss','scheduler')}),ensure_ascii=False))
