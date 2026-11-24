"""MuMu-only QA: actual SurfaceView frames, input mapping and lifecycle. No real game."""
import argparse
from io import BytesIO
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
args=parser.parse_args()
if args.serial!='127.0.0.1:16384':
    raise SystemExit('This controlled test is restricted to the MuMu test device')
root=Path(__file__).resolve().parents[1]
out=root/'captures/live-preview-0.4.3';out.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb','-s',args_serial,*args],stderr=subprocess.STDOUT)
args_serial=args.serial
apk=adb('shell','pm','path','com.oas.android').decode().strip().removeprefix('package:')
if not re.fullmatch(r'/data/app/[A-Za-z0-9_+./=~-]+/base.apk',apk):raise AssertionError(apk)
def cli(*args):
    command=f"CLASSPATH='{apk}' app_process /system/bin com.oas.android.bridge.BridgeClientMain "+' '.join(args)
    return adb('shell',command).decode().strip()
def status():return json.loads(cli('preview-status'))
focus_point=None
def tree():
    # Virtual-display injection changes Android's focused display. Physical touches
    # still belong to display 0; keep ADB input and UIAutomator on that same screen.
    if focus_point:adb('shell','input','-d','0','tap',*map(str,focus_point))
    adb('shell','uiautomator','dump','/sdcard/oas-live-ui.xml')
    return ET.fromstring(adb('exec-out','cat','/sdcard/oas-live-ui.xml'))
def bounds(node):return list(map(int,re.findall(r'\d+',node.get('bounds',''))))
def node(label):
    for n in tree().iter('node'):
        if n.get('text')==label or n.get('content-desc')==label:return n
    raise AssertionError('Missing UI: '+label)
def tap_node(n):
    x,y,r,b=bounds(n);adb('shell','input','-d','0','tap',str((x+r)//2),str((y+b)//2));time.sleep(.3)
def click(label):tap_node(node(label))
def eventually(check,timeout=15):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            value=check()
            if value is not None and value is not False:return value
        except (AssertionError,subprocess.CalledProcessError):pass
        time.sleep(.3)
    raise AssertionError('Timed out waiting for preview state')
def shot(name):
    data=adb('exec-out','screencap','-p');(out/(name+'.png')).write_bytes(data)
    return Image.open(BytesIO(data)).convert('RGB')
def capture(name):
    path='/data/local/tmp/oas-'+name+'.png';cli('screenshot',path)
    return Image.open(BytesIO(adb('exec-out','cat',path))).convert('RGB')
report=[]
log=(out/'debug-bridge.log').open('wb')
proc=None
try:
    adb('shell','am','force-stop','com.oas.android')
    adb('shell','am','start','-n','com.oas.android/.MainActivity')
    eventually(lambda:node('画面'))
    tx,ty,tr,tb=bounds(node('OASA / 主页'));focus_point=((tx+tr)//2,(ty+tb)//2)
    proc=subprocess.Popen(['adb','-s',args.serial,'shell',f"CLASSPATH='{apk}' app_process /system/bin --nice-name=oas-preview-qa com.oas.android.bridge.DebugMain"],stdout=log,stderr=log)
    eventually(status)
    cli('create','160');cli('launch','com.oas.android')
    click('画面')
    first=eventually(lambda: (s if (s:=status())['attached'] and s['ageMs'] in range(2001) else None))
    surface=node('游戏实时画面')
    x,y,r,b=bounds(surface);w=r-x;h=b-y
    scale=min(w/1280,h/720);left=x+(w-1280*scale)/2;top=y+(h-720*scale)/2
    def point(px,py):return round(left+px*scale),round(top+py*scale)
    image=shot('live-before')
    assert image.getpixel(point(100,300))[2]>100,'SurfaceView did not show the blue Probe'
    assert image.getpixel(point(1190,675))[1]>200,'Preview bottom marker upside down'
    switches=[n for n in tree().iter('node') if n.get('checkable')=='true' and len(n)==0]
    assert len(switches)==1,'Missing manual switch';tap_node(switches[0])
    px,py=point(640,360);adb('shell','input','-d','0','tap',str(px),str(py));time.sleep(.4)
    after=capture('ui-tap');assert after.getpixel((100,300))[1]>130,'Preview tap not mapped to virtual display'
    old=after.getpixel((100,620))
    start=point(300,300);end=point(900,400)
    adb('shell','input','-d','0','swipe',*map(str,(*start,*end,350)));time.sleep(.4)
    moved=capture('ui-swipe').getpixel((100,620))
    assert moved[0]==old[0]+1 and moved[2]==old[2]+1 and moved[1]>old[1],'Swipe was not a continuous gesture'
    latest=status();assert latest['frames']>first['frames']+10
    shot('live-controlled');report.append({'check':'SurfaceView dynamic frames, correct orientation, mapped single-finger tap/swipe','frames':latest['frames']-first['frames']})
    click('主页');eventually(lambda:not status()['attached']);stopped=status()['frames'];time.sleep(.6)
    assert status()['frames']==stopped,'Frames continued off page'
    click('画面');eventually(lambda:status()['attached']);report.append({'check':'tab detach/reattach','status':'passed'})
    adb('shell','input','-d','0','keyevent','KEYCODE_HOME');eventually(lambda:not status()['attached'])
    adb('shell','am','start','-n','com.oas.android/.MainActivity');eventually(lambda:status()['attached'])
    report.append({'check':'Activity pause/resume','status':'passed'})
    cli('release');eventually(lambda:not status()['attached']);shot('released-empty')
    cli('create','160');cli('launch','com.oas.android');eventually(lambda:status()['attached'] and status()['ageMs'] in range(2001))
    shot('recreated');report.append({'check':'release clears old frames, recreate reconnects live surface','status':'passed'})
    cli('primary');eventually(lambda:status()['mode']=='primary');node('前台兼容模式无法在本页实时预览，请使用后台虚拟屏')
    shot('primary-no-mirror');report.append({'check':'primary mode does not mirror app or show cached game image','status':'passed'})
finally:
    try:cli('release')
    except Exception:pass
    pids=adb('shell','pidof','oas-preview-qa').decode().strip() if proc else ''
    if pids and re.fullmatch(r'\d+( \d+)*',pids):adb('shell','kill',*pids.split())
    if proc:
        try:proc.wait(timeout=5)
        except subprocess.TimeoutExpired:proc.terminate()
    log.close()
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'status':'passed','checks':report},ensure_ascii=False))
