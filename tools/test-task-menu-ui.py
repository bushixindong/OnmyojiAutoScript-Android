"""Check the original OAS/OASX task menu in the installed app; no game actions."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);args=parser.parse_args()
output=Path(__file__).resolve().parents[1]/'captures/ui-menu-0.4.1';output.mkdir(parents=True,exist_ok=True)
def adb(*argv):return subprocess.check_output(['adb','-s',args.serial,*argv],stderr=subprocess.STDOUT)
def tree():
    adb('shell','uiautomator','dump','/sdcard/oas-menu-check.xml')
    return ET.fromstring(adb('exec-out','cat','/sdcard/oas-menu-check.xml'))
def bounds(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))
def node_for(root,label):
    for node in root.iter('node'):
        box=bounds(node)
        if (node.get('text')==label or node.get('content-desc')==label) and len(box)==4 and box[2]>box[0] and box[3]>box[1]:return node
def find(label):
    for _ in range(8):
        found=node_for(tree(),label)
        if found is not None:return found
        time.sleep(.3)
    raise AssertionError('Missing UI: '+label)
def tap(node):
    x1,y1,x2,y2=bounds(node);adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4)
def click(label):tap(find(label))
def scroll(root):
    nodes=[node for node in root.iter('node') if node.get('scrollable')=='true' and len(bounds(node))==4]
    node=max(nodes,key=lambda item:(bounds(item)[2]-bounds(item)[0])*(bounds(item)[3]-bounds(item)[1]))
    x1,y1,x2,y2=bounds(node);x=(x1+x2)//2
    adb('shell','input','swipe',str(x),str(int(y1+(y2-y1)*.85)),str(x),str(int(y1+(y2-y1)*.3)),'350');time.sleep(.4)
def reach(label):
    for _ in range(8):
        root=tree();node=node_for(root,label)
        if node is not None:return node
        scroll(root)
    raise AssertionError('Could not scroll to '+label)
def screenshot(name):(output/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
def launch(orientation):
    adb('shell','am','force-stop','com.oas.android')
    adb('shell','am','start','-n','com.oas.android/.MainActivity','--es','uiTestOrientation',orientation)
    find('主页');click('全部任务');find('搜索任务')

checks=[]
try:
    launch('portrait');screenshot('original-groups-portrait')
    expected=['脚本','御魂副本','日常任务','肝帝专属','阴阳寮','每周任务','限时活动','游戏辅助'];observed=[]
    for _ in range(8):
        root=tree()
        for node in root.iter('node'):
            label=node.get('text')
            if label in expected and node_for(root,label) is not None and label not in observed:observed.append(label)
        if observed==expected:break
        scroll(root)
    assert observed==expected,observed
    screenshot('original-groups-lower');checks.append('all eight original group names in original order')
    launch('portrait');tap(reach('每周任务'));tap_target=reach('每周琐事');screenshot('weekly-tasks')
    reach('协同对弈');reach('百鬼棋局');checks.append('weekly tasks include weekly trifles, draft duel and chess')
    launch('portrait');click('搜索任务');adb('shell','input','text','Draft');adb('shell','input','keyevent','KEYCODE_BACK')
    find('每周任务');find('协同对弈');screenshot('weekly-search')
    click('清除搜索');click('搜索任务');adb('shell','input','text','Custom');adb('shell','input','keyevent','KEYCODE_BACK')
    find('游戏辅助');find('自定义任务');screenshot('helper-search');checks.append('search preserves original weekly/helper grouping')
    click('筛选任务');find('已启用');find('未启用');adb('shell','input','keyevent','KEYCODE_BACK')
    launch('landscape');find('脚本列表');find('脚本');find('御魂副本');screenshot('original-groups-landscape')
    checks.append('original catalog toolbar and landscape layout')
finally:
    adb('shell','am','force-stop','com.oas.android');adb('shell','am','start','-n','com.oas.android/.MainActivity')
    (output/'report.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(status='passed',checks=checks,screenshots=str(output)),ensure_ascii=True))
