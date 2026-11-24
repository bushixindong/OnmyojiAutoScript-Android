"""Read-only source inventory plus measured Android audit status; no live-test claims."""
import argparse
import ast
import json
import re
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--source',type=Path,required=True)
parser.add_argument('--audit',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
audit=json.loads(args.audit.read_text(encoding='utf-8-sig')) if args.audit else {}
results={entry['task']:entry for entry in audit.get('entries',[])}
forms={entry['task']:entry for entry in audit.get('forms',[])}
entries=[]
for path in sorted((args.source/'tasks').glob('*/script_task.py')):
    text=path.read_text(encoding='utf-8-sig');tree=ast.parse(text);name=path.parent.name
    deferred=name=='Hyakkiyakou';result=results.get(name,{});form=forms.get(name,{})
    checked=result.get('status')=='ready' and form.get('status')=='passed'
    entries.append(dict(task=name,source=path.relative_to(args.source).as_posix(),lines=len(text.splitlines()),
        androidStatus='deferred_by_user' if deferred else 'ported_offline_validated_awaiting_live_test' if checked else 'ported_audit_pending',
        originalEntryImport=result.get('status','not_run'),nativeConfigRoundtrip=form.get('status','not_run'),
        liveTest='not_run',runnable=not deferred,
        scope='用户要求暂缓百鬼夜行，不加载跟踪库。' if deferred else '复用原 Python 任务与资源，Android 替换设备、图像服务和 OCR 边界；真实游戏效果待用户测试。',
        imports=sorted({node.module for node in ast.walk(tree) if isinstance(node,ast.ImportFrom) and node.module})))
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
report=dict(version=version,architecture='embedded-original-python-engine',taskCount=len(entries),
    runnableTaskCount=sum(entry['runnable'] for entry in entries),sourceLines=sum(entry['lines'] for entry in entries),
    userRunsLiveTests=True,verificationDevice='MuMu Android 12 x86_64',
    limitations=['新版 arm64-v8a 尚未运行审计','跨脚本协同保留原本地数据库协议，不提供手机与电脑之间的远程协调',
        '大神签到仅使用原纯 UI 模式，不使用 Frida/Root','手机不支持关闭/启动桌面模拟器，相关空闲策略映射为留在庭院或关闭游戏'],
    integrationAudit=audit.get('integration',[]),tasks=entries)
(root/'MIGRATION_STATUS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f"{len(entries)} task entries; {report['runnableTaskCount']} runnable; {sum(entry['androidStatus']=='ported_offline_validated_awaiting_live_test' for entry in entries)} offline validated; Hyakkiyakou deferred")
