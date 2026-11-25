"""Keep OAS control/stuck/cache logic; replace only desktop backends with Shizuku callbacks."""
from __future__ import annotations
import ast
import json
import struct
import importlib
from collections import deque
from datetime import datetime
from pathlib import Path
import shlex
import sys
import time
import types
import numpy as np
import cv2

def install(root,host,check):
    from module.base.timer import Timer
    from module.base.decorator import cached_property
    from module.base import utils
    from module.logger import logger
    from module import exception
    from module.config.utils import get_server_next_update
    def module(name,**values):
        result=types.ModuleType(name);result.__dict__.update(values);sys.modules[name]=result
        if '.' in name:
            parent,attribute=name.rsplit('.',1)
            if parent in sys.modules:setattr(sys.modules[parent],attribute,result)
        return result
    importlib.import_module('module.device')
    importlib.import_module('module.device.method')
    namespace=dict(vars(utils))
    namespace.update(dict(__name__='module.device.control',time=time,np=np,Timer=Timer,cached_property=cached_property,logger=logger,IS_WINDOWS=False))
    for name in ('Minitouch','Adb','Scrcpy','Window','Platform','Screenshot','AppControl'):
        namespace[name]=type(name,(),{})
    def original_class(filename,name):
        parsed=ast.parse((root/filename).read_text(encoding='utf-8-sig'))
        node=next(item for item in parsed.body if isinstance(item,ast.ClassDef) and item.name==name)
        exec(compile(ast.Module(body=[node],type_ignores=[]),str(root/filename),'exec'),namespace)
        return namespace[name]
    Control=original_class('module/device/control.py','Control')
    namespace.update(dict(deque=deque,datetime=datetime,get_server_next_update=get_server_next_update))
    namespace.update(vars(exception))
    namespace['__name__']='module.device.device'
    OriginalDevice=original_class('module/device/device.py','Device')

    class AndroidAdb:
        serial='android-shizuku'
        def __init__(self,device):self.device=device
        def shell(self,args,**kwargs):return self.device.adb_shell(args,**kwargs)
        def app_start(self,package,**kwargs):host.launch(str(package))
        def app_stop(self,package,**kwargs):host.stopPackage(str(package))
        def app_current(self):return dict(package=self.device.package)
        def list_packages(self):return self.shell(['pm','list','packages'])
        def keyevent(self,key):self.device.keyevent(key)
        def send_keys(self,text,clear=False,**kwargs):
            check()
            if clear:host.replaceText(str(text))
            else:self.device.send_text(text)

    class Device(OriginalDevice):
        def __init__(self,config,*args,**kwargs):
            self.config=config;self.serial='android-shizuku';self.orientation=0
            self.package=str(config.script.device.package_name);self.image=None;self.image_frame_id=None
            self.detect_record=set();self.click_record=deque(maxlen=15)
            max_shots=max(1,min(20,int(getattr(getattr(config.script,'error',None),'screenshot_length',1) or 1)))
            self.screenshot_deque=deque(maxlen=max_shots)
            self.stuck_timer=Timer(60,count=60).start();self.stuck_timer_long=Timer(300,count=300).start()
            self._screen_size_checked=True;self._screen_black_checked=True
            self.interval_timer={};self._image_batch_cache_frame_id=None;self._image_batch_cache={}
            self.adb=self.adb_device=AndroidAdb(self)
            self.u2=self.adb
            self._last_screenshot=0;self._screenshot_seconds=.3;self.screenshot_interval_set()
            self.is_mumu_family=False;self.is_nox_family=False;self.is_bluestacks_family=False
            self.is_network_device=False;self.is_over_http=False
        def screenshot(self):
            check();self.stuck_record_check()
            time.sleep(max(0,self._last_screenshot+self._screenshot_seconds-time.monotonic()))
            raw_reader=getattr(host,'screenshotRgb',None)
            raw=raw_reader() if raw_reader is not None else None
            self._last_screenshot=time.monotonic()
            if raw is not None:
                data=bytes(raw)
                if len(data)<16:raise RuntimeError('原始帧头不完整')
                magic,width,height,length=struct.unpack_from('<IIII',data)
                if magic!=0x4f415352 or width*height*3!=length or len(data)!=length+16:
                    raise RuntimeError('原始帧协议或尺寸无效')
                self.image=np.frombuffer(data,dtype=np.uint8,offset=16).reshape(height,width,3)
            else:
                data=bytes(host.screenshot())
                frame=cv2.imdecode(np.frombuffer(data,dtype=np.uint8),cv2.IMREAD_COLOR)
                if frame is None:raise RuntimeError('Android 截图解码失败')
                self.image=cv2.cvtColor(frame,cv2.COLOR_BGR2RGB)
            from module.image.rpc import get_image_client
            entry=get_image_client().register_frame(self.image,self.config.config_name)
            self.image_frame_id=entry['frame_id'];self.reset_image_batch_cache(self.image_frame_id)
            try:
                snapshot = self.image.copy() if self.image.flags.writeable else self.image
                self.screenshot_deque.append((int(time.time()*1000),snapshot))
            except Exception:pass
            return self.image
        def screenshot_adb(self):return self.screenshot()
        screenshot_nemu_ipc=screenshot_adb
        screenshot_window_background=screenshot_adb
        screenshot_scrcpy=screenshot_adb
        def click_adb(self,x,y):check();host.tap(int(x),int(y))
        click_minitouch=click_adb;click_uiautomator2=click_adb;click_window_message=click_adb
        def long_click_adb(self,x,y,duration):
            check();host.pressAndDrag(int(x),int(y),int(x),int(y),int(float(duration)*1000),1)
        long_click_minitouch=long_click_adb;long_click_uiautomator2=long_click_adb;long_click_scrcpy=long_click_adb;long_click_window_message=long_click_adb
        def swipe_adb(self,p1,p2,duration=.3):
            check();host.swipe(int(p1[0]),int(p1[1]),int(p2[0]),int(p2[1]),int(float(duration)*1000))
        swipe_minitouch=swipe_adb;swipe_uiautomator2=swipe_adb;swipe_scrcpy=swipe_adb;swipe_window_message=swipe_adb
        def press_and_drag(self,p1,p2,hold_duration=None,swipe_duration=None,**kwargs):
            self.handle_control_check(kwargs.get('name','Press_and_Drag'));check()
            hold=utils.ensure_time(hold_duration if hold_duration is not None else .65)
            duration=utils.ensure_time(swipe_duration if swipe_duration is not None else .5)
            host.pressAndDrag(int(p1[0]),int(p1[1]),int(p2[0]),int(p2[1]),int(hold*1000),int(duration*1000))
            self.invalidate_image_batch_cache()
        def drag(self,p1,p2,swipe_duration=.5,**kwargs):return self.press_and_drag(p1,p2,swipe_duration=swipe_duration,**kwargs)
        def multi_click(self,button,n,interval=(.1,.2)):
            for _ in range(n):
                point=button.coord() if hasattr(button,'coord') else button
                self.click(*point);time.sleep(utils.ensure_time(interval))
        def app_start(self,package_name=None):check();host.launch(str(package_name or self.package))
        def app_stop(self,package_name=None):check();host.stopPackage(str(package_name or self.package))
        def app_is_alive(self,package_name=None,**kwargs):return bool(self.adb_shell(['pidof',str(package_name or self.package)]).strip())
        def app_is_running(self):return bool(host.isGameForeground())
        def app_current_adb(self):return str(host.currentPackage())
        app_current_uiautomator2=app_current_adb
        def wait_app_start_ready(self,timeout=15,interval=.5):
            deadline=time.monotonic()+timeout
            while not self.app_is_running():
                check()
                if time.monotonic()>deadline:raise RuntimeError('等待应用前台超时')
                time.sleep(interval)
        def keyevent(self,key):check();host.keyEvent(str(key))
        def send_text(self,text):check();host.inputText(str(text))
        input_text=send_text
        def sleep(self,seconds):
            remaining=float(utils.ensure_time(seconds))
            deadline=time.monotonic()+max(0.0,remaining)
            while True:
                check()
                left=deadline-time.monotonic()
                if left<=0:break
                time.sleep(min(0.1,left))
        def release_during_wait(self):check()
        def screenshot_interval_set(self,interval=None):
            if interval is None:interval=max(.1,min(.3,self.config.script.optimization.screenshot_interval))
            elif interval=='combat':interval=max(.3,min(1.,self.config.script.optimization.combat_screenshot_interval))
            self._screenshot_seconds=max(0,float(interval))
        def orientation_check(self):return self.orientation
        def get_orientation(self):self.orientation=0;return 0
        def uninstall_minicap(self):logger.info('Android 使用 Shizuku 截图，无需卸载 minicap')
        def draw_adb(self,points,interval=.01):
            if len(points)<2:return
            # Keep the original curve construction without importing desktop minitouch.
            source=ast.parse((root/'module/device/method/minitouch.py').read_text(encoding='utf-8-sig'))
            node=next(node for node in source.body if isinstance(node,ast.FunctionDef) and node.name=='smooth_path')
            scope=dict(vars(utils));scope.update(np=np)
            exec(compile(ast.Module(body=[node],type_ignores=[]),'oas-smooth-path','exec'),scope)
            path=scope['smooth_path'](points)
            check();host.drawPath(json.dumps([[int(x),int(y)] for x,y in path]),max(1,int(float(interval)*1000)))
            self.invalidate_image_batch_cache()
        def adb_command(self,args,**kwargs):
            check()
            if len(args)!=3 or args[0]!='pull':raise RuntimeError('手机端仅支持原任务所需的 pull 文件读取')
            destination=Path(args[2]).resolve()
            if not destination.is_relative_to(root.resolve()):raise ValueError('文件目标必须在应用任务目录内')
            destination.parent.mkdir(parents=True,exist_ok=True)
            host.pullFile(str(args[1]),str(destination));return str(destination)
        def check_screen_size(self):return self.image is not None and self.image.shape[:2] in ((720,1280),(1280,720))
        def adb_shell(self,args,**kwargs):
            check();args=shlex.split(args) if isinstance(args,str) else [str(item) for item in args]
            if not args:return ''
            if args[:2]==['input','tap']:
                self.click_adb(int(args[2]),int(args[3]));return ''
            if args[:2]==['input','swipe']:
                host.swipe(*map(int,args[2:6]),int(args[6]) if len(args)>6 else 400);return ''
            if args[:2]==['input','text']:self.send_text(args[2]);return ''
            if args[:2]==['input','keyevent']:self.keyevent(args[2]);return ''
            if args[:2]==['am','start'] and '-n' in args:
                host.launch(args[args.index('-n')+1].split('/')[0]);return 'Starting'
            if args[:2]==['am','force-stop']:host.stopPackage(args[2]);return ''
            if args[0]=='monkey' and '-p' in args:host.launch(args[args.index('-p')+1]);return 'Events injected: 1'
            if args[:2]==['wm','size'] and len(args)==2:return 'Physical size: 720x1280' if host.isPortrait() else 'Physical size: 1280x720'
            if args[0] in ('wm','settings','su','reboot'):raise RuntimeError('手机端不修改主屏或系统设置：'+str(args))
            return str(host.shell(json.dumps(args,ensure_ascii=False)))
        def dump_hierarchy(self):
            from lxml import etree
            self.adb_shell(['uiautomator','dump','/data/local/tmp/oas-uiautomator.xml'])
            self.hierarchy=etree.fromstring(self.adb_shell(['cat','/data/local/tmp/oas-uiautomator.xml']).encode())
            return self.hierarchy
        def __str__(self):return 'Android Shizuku device'

    module('module.device.device',Device=Device)
    module('module.device.control',Control=Control)
    # Keep constant imports usable without starting the desktop scrcpy transport.
    package=module('module.device.method.scrcpy')
    package.__path__=[str(root/'module/device/method/scrcpy')]
    # Desktop window-handle discovery is not a mobile control backend.
    from anytree import Node
    class Handle:
        @staticmethod
        def all_windows():return []
    module('module.device.handle',Handle=Handle,WindowNode=Node,handle_num2title=lambda value:'Android',
           handle_title2num=lambda value:0,is_handle_valid=lambda value:False)
