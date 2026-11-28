"""Runs original OAS tasks in-process; no Windows service, subprocess worker or OCR RPC."""
from __future__ import annotations
import ast
from collections import deque
import importlib
import json
import logging
import os
from pathlib import Path
import pickle
import sys
import threading
import time
import types
import traceback

_root=None
_error_mirror_dir=None
_host=None
_config=None
_config_name='android'
_active=False
_schedule_active=False
_ready=False
_running_task=None
_last_outcome=None
_sleep=time.sleep
_guard=None
_config_lock=threading.RLock()
_stop_event=threading.Event()
_wakeup_event=threading.Event()
_recent_logs=deque(maxlen=200)
_RESERVED_PRESETS={'template','android-audit','Home','home'}
_IDLE_POLL_SECONDS=30.0
_labels_cache=None
_catalog_cache=None

class Cancelled(BaseException):
    pass

def request_stop():
    _stop_event.set()
    _wakeup_event.set()

def wake_schedule():
    _wakeup_event.set()

def _notify_state():
    fn=getattr(_host,'notifyStateChanged',None)
    if callable(fn):
        try:fn()
        except Exception:pass

def check():
    if _active and (_stop_event.is_set() or not _host.isRunning()):
        raise Cancelled('任务已停止或达到运行时限')

def sleep(seconds):
    remaining=max(0.0,float(seconds))
    while remaining>0:
        check()
        duration=min(0.05,remaining)
        if _active:
            if _stop_event.wait(duration):check()
        else:
            _sleep(duration)
        remaining-=duration

def _wait_schedule(seconds):
    duration=max(0.0,float(seconds))
    if duration<=0:return
    if _wakeup_event.wait(duration):
        if not _stop_event.is_set():
            _wakeup_event.clear()

def _wait_idle(host, seconds):
    duration = max(0.0, float(seconds))
    begin = getattr(host, 'beginIdleWait', None)
    if duration >= 60 and begin is not None and begin(int(duration * 1000)):
        try:
            _wait_schedule(duration)
        finally:
            host.endIdleWait()
    else:
        # Config edits and stop requests already set _wakeup_event, so there is no
        # need to re-plan (config reload + catalog) every second while waiting.
        _wait_schedule(min(_IDLE_POLL_SECONDS, duration))

def _module(name,**values):
    module=types.ModuleType(name);module.__dict__.update(values);sys.modules[name]=module;return module

def initialize(root,host,mirror_dir=''):
    global _root,_error_mirror_dir,_host,_ready
    with _config_lock:
        if not (_active or _schedule_active):_host=host
    if mirror_dir:
        try:
            _error_mirror_dir=Path(str(mirror_dir))
            _error_mirror_dir.mkdir(parents=True,exist_ok=True)
        except Exception:
            _error_mirror_dir=None
    if _ready:return
    _root=Path(str(root));os.chdir(_root);sys.path.insert(0,str(_root));sys.dont_write_bytecode=True
    _sync_existing_error_mirrors()
    os.environ.setdefault('OPENBLAS_NUM_THREADS','2');os.environ.setdefault('OMP_NUM_THREADS','2')
    time.sleep=sleep
    _install_image()
    _install_ocr()
    from android_oas.device import install
    class DynamicHost:
        def __getattr__(self,name):return getattr(_host,name)
    install(_root,DynamicHost(),check)
    # Optional Frida extraction is replaced by the original root-free UI workflow.
    _module('oas_checkin_biggod',FRIDA_SERVER_XZ=b'',ADB_PATH='android-shizuku')
    from module.logger import logger
    import re
    _rich_tag=re.compile(r'\[(?:/|#[0-9a-fA-F]{3,8}|(?:bold|dim|italic|underline|blink|reverse|strike|black|red|green|yellow|blue|magenta|cyan|white|bright_[a-z]+|dark_[a-z]+|orange[0-9]?|purple|grey[0-9]*|gray[0-9]*|on [a-z_0-9#]+)(?:\s+(?:bold|dim|italic|underline|black|red|green|yellow|blue|magenta|cyan|white|bright_[a-z]+|on [a-z_0-9#]+))*)\]')
    _log_file_lock=threading.Lock()
    def _append_daily_log(text):
        try:
            day_str=time.strftime('%Y-%m-%d',time.localtime())
            log_dir=_root/'log'
            log_dir.mkdir(parents=True,exist_ok=True)
            log_path=log_dir/f'{day_str}_{_config_name}.txt'
            with _log_file_lock:
                with log_path.open('a',encoding='utf-8') as f:
                    f.write(text)
        except Exception:pass
    class AndroidLog(logging.Handler):
        def emit(self,record):
            try:
                raw=record.getMessage()
                msg=_rich_tag.sub('',raw)
                now=time.time()
                lt=time.localtime(now)
                ms=int((now-int(now))*1000)
                stamp=f"{time.strftime('%H:%M:%S',lt)}.{ms:03d}"
                line=f'{stamp} | {record.levelname} | {msg}'
                _recent_logs.append(line)
                host.log(line)
                day_str=time.strftime('%Y-%m-%d',lt)
                _append_daily_log(f'{day_str} {stamp} | {record.levelname} | {msg}\n')
            except Exception:pass
    logger.addHandler(AndroidLog())
    _orig_hr=getattr(logger,'hr',None)
    if callable(_orig_hr):
        def _android_hr(title,level=3):
            text=str(title or '').strip().upper()
            if level==0:
                _append_daily_log('═'*80+'\n'+f'{"─"*20} {text} {"─"*20}\n'+'═'*80+'\n')
            elif level==1:
                _append_daily_log(f'{"═"*20} {text} {"═"*20}\n')
            else:
                _append_daily_log(f'{"─"*20} {text} {"─"*20}\n')
            return _orig_hr(title,level=level)
        logger.hr=_android_hr
    _ready=True

def _install_image():
    # Upstream's package __init__ imports the desktop RPC client. Install the same
    # public exports after loading the local runtime, without that bootstrap cycle.
    parent=_module('module.image');parent.__path__=[str(_root/'module/image')]
    import numpy as np
    from module.image.runtime import ImageRuntime
    from module.exception import ScriptError
    source=(_root/'module/image/rpc.py').read_text(encoding='utf-8-sig')
    node=next(node for node in ast.parse(source).body if isinstance(node,ast.ClassDef) and node.name=='ImageClient')
    namespace=dict(np=np,pickle=pickle,ScriptError=ScriptError,__name__='module.image.rpc')
    exec(compile(ast.Module(body=[node],type_ignores=[]),str(_root/'module/image/rpc.py'),'exec'),namespace)
    # The bundled and hot-updated desktop runtime share the registration logic.
    # Give just that method a decoder for local NumPy snapshots, without changing
    # pickle or any other desktop API globally.
    register_frame = ImageRuntime.register_frame
    frame_globals = dict(register_frame.__globals__)
    frame_globals['pickle'] = types.SimpleNamespace(
        loads=lambda payload: payload if isinstance(payload, np.ndarray) else pickle.loads(payload))
    register_local_frame = types.FunctionType(register_frame.__code__, frame_globals,
        register_frame.__name__, register_frame.__defaults__, register_frame.__closure__)
    register_local_frame.__kwdefaults__ = register_frame.__kwdefaults__
    class LocalImageClient(namespace['ImageClient']):
        def __init__(self):
            self.address='in-process'
            self.client=ImageRuntime(dict(worker_count=2,frame_cache_max_count=24,frame_cache_expire_seconds=30.0))
        def register_frame(self, image, config_name):
            if not isinstance(image, np.ndarray):
                raise TypeError('register_frame expects a NumPy array')
            # A bytes-backed raw frame is already immutable. Keep the previous
            # snapshot semantics for callers passing a mutable image.
            if image.flags.writeable or not image.flags.c_contiguous:
                image = np.array(image, copy=True, order='C')
                image.setflags(write=False)
            return register_local_frame(self.client, image, config_name)
    client=LocalImageClient()
    rpc=_module('module.image.rpc',ImageClient=LocalImageClient,get_image_client=lambda *args,**kwargs:client,
            ensure_image_server_started=lambda:True,ensure_image_server_ready=lambda:True,
            shutdown_image_server=lambda **kwargs:client.client.shutdown())
    for name in ('ImageClient','get_image_client','ensure_image_server_started','ensure_image_server_ready','shutdown_image_server'):
        setattr(parent,name,getattr(rpc,name))
    parent.rpc=rpc

def _install_ocr():
    class _AndroidDeployConfig:
        OcrClientAddress='in-process'
        ImageClientAddress='in-process'
        Language='zh-CN'
        UseUnifiedRunner=False
        def __getattr__(self,name):return None
    try:
        from module.server.setting import State
        State._deploy_config_=_AndroidDeployConfig()
    except Exception:
        pass
    instance=[]
    def get_ocr_client(*args,**kwargs):
        if not instance:
            from ppocronnx.predict_system import TextSystem
            instance.append(TextSystem(ort_providers=['CPUExecutionProvider']))
        return instance[0]
    _module('module.ocr.rpc',get_ocr_client=get_ocr_client,ensure_ocr_server_started=lambda:True,
            ensure_ocr_server_ready=lambda:True,shutdown_ocr_server=lambda **kwargs:False)
    _module('module.ocr.models',get_ocr_model=lambda lang='ch':get_ocr_client())

def _validate_preset_name(name):
    import re
    clean=str(name or '').strip()
    if not clean:raise ValueError('名称不能为空')
    if clean in _RESERVED_PRESETS:raise ValueError('名称不可用：'+clean)
    if len(clean)>48 or not re.fullmatch(r'[A-Za-z0-9_\-\u4e00-\u9fff ]+',clean):
        raise ValueError('名称只能包含中英文、数字、空格、下划线或连字符')
    return clean

def _normalize_android_device(settings,create=False):
    # Platform selectors do not describe a desktop emulator on a phone.
    device=settings.model.script.device
    checkin=settings.model.auto_checkin_big_god.checkin_config
    values=dict(control_method='adb',screenshot_method='ADB',serial='android-shizuku',
        package_name=str(_host.gamePackage()))
    changed=create or not checkin.manual_claim
    for field,value in values.items():
        if getattr(device,field)!=value:
            setattr(device,field,value);changed=True
    checkin.manual_claim=True
    if changed:settings.save()
    return settings

def _load_config(name):
    from module.config.config import Config
    path=_root/'config'/f'{name}.json'
    exists=path.is_file()
    if exists:
        saved=json.loads(path.read_text(encoding='utf-8-sig'))
        device=saved.get('script',{}).get('device',{})
        changed=False
        if device.get('control_method')=='ADB':
            device['control_method']='adb';changed=True
        if device.get('screenshot_method') not in ('ADB','window_background','ldopengl','nemu_ipc'):
            device['screenshot_method']='ADB';changed=True
        if changed:
            path.write_text(json.dumps(saved,ensure_ascii=False,indent=2),encoding='utf-8')
    return _normalize_android_device(Config(name),create=not exists)

def config(preset=None):
    global _config
    with _config_lock:
        if preset is not None:
            name=_validate_preset_name(preset)
            if name!=_config_name:
                if not (_root/'config'/f'{name}.json').is_file():
                    raise ValueError('预设不存在：'+name)
                return _load_config(name)
        if _config is None:_config=_load_config(_config_name)
        return _config

def active_config():
    with _config_lock:return _config_name

def set_active_config(name):
    with _config_lock:
        if _active or _schedule_active:raise RuntimeError('任务运行时请先停止再切换脚本预设')
        _activate_preset_locked(name)
        return preset_bundle()

def _activate_preset_locked(preset):
    global _config,_config_name
    settings=config(preset)
    if preset is not None:
        _config_name=_validate_preset_name(preset)
        _config=settings

def preset_bundle(preset=None):
    with _config_lock:
        name=_config_name if preset is None else _validate_preset_name(preset)
        settings=config(name)
        return json.dumps(dict(active=name,presets=json.loads(_list_presets(name,settings)),
            dashboard=_dashboard(name,settings)),ensure_ascii=False)

def _preset_preview(raw,translations,key_to_task):
    from datetime import datetime
    now=datetime.now().replace(microsecond=0)
    pending=[];waiting=[]
    for key,section in raw.items():
        if not isinstance(section,dict):continue
        sched=section.get('scheduler')
        if not isinstance(sched,dict) or not sched.get('enable'):continue
        task_id=key_to_task.get(key)
        if not task_id:continue
        nr=str(sched.get('next_run') or '')
        title=translations.get(task_id,task_id)
        try:dt=datetime.fromisoformat(nr.replace(' ','T'))
        except Exception:dt=now
        (pending if dt<=now else waiting).append((dt,nr,title,task_id))
    if pending:
        pending.sort(key=lambda x:x[0])
        return dict(type='pending',id=pending[0][3],title=pending[0][2],time='')
    if waiting:
        waiting.sort(key=lambda x:x[0])
        return dict(type='waiting',id=waiting[0][3],title=waiting[0][2],time=waiting[0][1])
    return dict(type='none',id='',title='',time='')

def list_presets():
    with _config_lock:return _list_presets(_config_name,config())

def _list_presets(selected,settings):
    config_dir=_root/'config'
    names=[p.stem for p in config_dir.glob('*.json') if p.stem not in _RESERVED_PRESETS]
    names=sorted(set(names),key=lambda s:(0 if s=='android' else 1,s.lower()))
    if not names:names=['android']
    translations=json.loads(labels())
    key_to_task={k:type(settings.model).type(k) for k in type(settings.model).model_fields}
    running_title=translations.get(_running_task,_running_task) if (_active and _running_task) else ''
    result=[]
    for stem in names:
        path=config_dir/f'{stem}.json'
        preview=dict(type='none',id='',title='',time='')
        if stem==_config_name and _active and _running_task:
            preview=dict(type='running',id=_running_task,title=running_title,time='')
        elif path.is_file():
            try:
                raw=json.loads(path.read_text(encoding='utf-8-sig'))
                preview=_preset_preview(raw,translations,key_to_task)
            except Exception:
                pass
        result.append(dict(name=stem,active=(stem==selected),running=(stem==_config_name and (_active or _schedule_active)),preview=preview))
    return json.dumps(result,ensure_ascii=False)

def suggest_preset_name():
    existing={item['name'] for item in json.loads(list_presets())}
    idx=1
    while f'oas{idx}' in existing:idx+=1
    return f'oas{idx}'

def create_preset(name,source=None,import_json=None):
    global _config,_config_name
    with _config_lock:
        if _active or _schedule_active:raise RuntimeError('任务运行时请先停止再新建预设')
        clean=_validate_preset_name(name)
        config_dir=_root/'config'
        config_dir.mkdir(parents=True,exist_ok=True)
        target=config_dir/f'{clean}.json'
        if target.exists():raise ValueError('同名预设已存在：'+clean)
        try:
            if import_json is not None and str(import_json).strip():
                raw=json.loads(str(import_json))
                if not isinstance(raw,dict):raise ValueError('导入 JSON 顶层必须是对象')
                raw['config_name']=clean
                device=raw.setdefault('script',{}).setdefault('device',{})
                device['control_method']='adb'
                device['screenshot_method']='ADB'
                device['serial']='android-shizuku'
                target.write_text(json.dumps(raw,ensure_ascii=False,indent=2),encoding='utf-8')
            elif source and str(source).strip() and str(source)!='__template__':
                src_file=config_dir/f'{str(source).strip()}.json'
                if not src_file.is_file():raise ValueError('来源预设不存在')
                raw=json.loads(src_file.read_text(encoding='utf-8-sig'))
                raw['config_name']=clean
                target.write_text(json.dumps(raw,ensure_ascii=False,indent=2),encoding='utf-8')
            settings=_load_config(clean)
        except Exception:
            if target.exists():target.unlink()
            raise
        _config_name=clean;_config=settings
        return preset_bundle()

def rename_preset(old_name,new_name,selected=None):
    global _config,_config_name
    with _config_lock:
        if _active or _schedule_active:raise RuntimeError('任务运行时请先停止再重命名')
        old_clean=_validate_preset_name(old_name)
        new_clean=_validate_preset_name(new_name)
        selected=_config_name if selected is None else _validate_preset_name(selected)
        config_dir=_root/'config'
        src=config_dir/f'{old_clean}.json'
        dst=config_dir/f'{new_clean}.json'
        if not src.is_file():raise ValueError('预设不存在')
        if dst.exists():raise ValueError('名称已存在：'+new_clean)
        raw=json.loads(src.read_text(encoding='utf-8-sig'))
        raw['config_name']=new_clean
        dst.write_text(json.dumps(raw,ensure_ascii=False,indent=2),encoding='utf-8')
        src.unlink()
        if _config_name==old_clean:_config_name=new_clean;_config=None
        return preset_bundle(new_clean if selected==old_clean else selected)

def delete_preset(name,selected=None):
    global _config,_config_name
    with _config_lock:
        if _active or _schedule_active:raise RuntimeError('任务运行时请先停止再删除预设')
        clean=_validate_preset_name(name)
        selected=_config_name if selected is None else _validate_preset_name(selected)
        presets=json.loads(list_presets())
        remaining=[item['name'] for item in presets if item['name']!=clean]
        if not remaining:raise ValueError('至少保留一个脚本预设')
        target=_root/'config'/f'{clean}.json'
        if target.is_file():target.unlink()
        if _config_name==clean:_config_name=remaining[0];_config=None
        return preset_bundle(remaining[0] if selected==clean else selected)

def export_preset(name=None):
    with _config_lock:
        target_name=_validate_preset_name(name or _config_name)
        if target_name==_config_name:config().save()
        path=_root/'config'/f'{target_name}.json'
        if not path.is_file():raise ValueError('预设不存在')
        return path.read_text(encoding='utf-8-sig')

def import_task_json(name,json_text,preset=None):
    with _config_lock:
        preset=_config_name if preset is None else _validate_preset_name(preset)
        if _task_is_running(preset,name):raise RuntimeError('当前任务正在运行中，请稍后再导入')
        settings=config(preset)
        entry=next(item for item in json.loads(catalog(settings)) if item['id']==name)
        key=entry['configKey']
        if key is None:raise ValueError('该任务没有独立配置')
        payload=json.loads(str(json_text))
        if not isinstance(payload,dict):raise ValueError('导入 JSON 必须是对象')
        if key in payload and isinstance(payload[key],dict):payload=payload[key]
        definition=_save_task_config(name,json.dumps(payload,ensure_ascii=False),settings)
        return json.dumps(dict(config=json.loads(definition),dashboard=_dashboard(preset,settings)),ensure_ascii=False)

def export_task_json(name,preset=None):
    data=json.loads(task_config(name,preset))['value']
    return json.dumps(data,ensure_ascii=False,indent=2)

def _file_signature(*filenames):
    signature=[str(_root)]
    for filename in filenames:
        try:
            stat=(_root/filename).stat();signature.append((filename,stat.st_mtime_ns,stat.st_size))
        except OSError:
            signature.append((filename,None,None))
    return tuple(signature)

def catalog(settings=None):
    global _catalog_cache
    if settings is None:settings=config()
    # Called by every scheduler pass and UI refresh; rebuild only when the hot-updated
    # manifest/i18n files or the config model class change.
    signature=(_file_signature('runtime-manifest.json'),_labels_signature(),type(settings.model))
    if _catalog_cache is not None and _catalog_cache[0]==signature:return _catalog_cache[1]
    names=json.loads((_root/'runtime-manifest.json').read_text())['tasks']
    translations=_label_map()
    model_type=type(settings.model)
    keys={}
    for key in model_type.model_fields:
        try:kind=model_type.type(key)
        except Exception:continue
        keys.setdefault(kind,key)
    result=[]
    for name in names:
        result.append(dict(id=name,label=translations.get(name,name),configKey=keys.get(name),runnable=True,deferred=False))
    for name,key in (('GlobalGame','global_game'),('Script','script')):
        result.append(dict(id=name,label=translations.get(name,name),configKey=key,runnable=False))
    text=json.dumps(result,ensure_ascii=False)
    _catalog_cache=(signature,text)
    return text

def _entry(name):
    allowed={item['id'] for item in json.loads(catalog()) if item['runnable']}
    if name not in allowed:raise ValueError('未知任务：'+name)
    entry=importlib.import_module('tasks.'+name+'.script_task').ScriptTask
    if not callable(getattr(entry,'run',None)):raise TypeError('任务没有执行入口：'+name)
    if name=='Hyakkiyakou':
        from android_oas.hyakkiyakou import adapt_task
        return adapt_task(entry)
    if name=='DraftDuel':
        from tasks.DraftDuel.runtime import deployment
        def drag_to_field(device,p1,p2,hold_duration=.2,point_random=(-2,-2,2,2),swipe_duration=.45,name='draft_battle_field'):
            device.press_and_drag(p1,p2,hold_duration=hold_duration,swipe_duration=swipe_duration,name=name)
            return True
        deployment.drag_to_field=drag_to_field
    if name=='AutoCheckinBigGod':
        entry._check_adb_connection=lambda self:True
        entry._adb_shell=lambda self,cmd,timeout=15:self.device.adb_shell(cmd)
        from tasks.AutoCheckinBigGod.portrait_ui import PortraitUIMixin
        def begin(self):
            _host.setPortrait(True);self.device.orientation=0
            self.device._screen_size_checked=True;self.device._screen_black_checked=True
        def launch(self,package,timeout=30):
            _host.launch(str(package));return self.device.app_is_alive(package)
        def cleanup(self):
            _host.setPortrait(False);_host.launch(str(_host.gamePackage()))
        PortraitUIMixin._init_portrait=begin;PortraitUIMixin._launch_app_foreground=launch
        PortraitUIMixin._cleanup_portrait=cleanup
    return entry

def task_config(name,preset=None):
    with _config_lock:return _task_config(name,config(preset))

def _task_config(name,settings):
    entry=next(item for item in json.loads(catalog(settings)) if item['id']==name)
    if entry['configKey'] is None:return json.dumps(dict(value={},schema=dict(type='object',properties={})))
    model=getattr(settings.model,entry['configKey'])
    # Some upstream defaults are stored as old aliases (e.g. green_mark_type).
    # Normalize them with the real validator before presenting an editable form.
    value=type(model).model_validate(model.model_dump()).model_dump(mode='json')
    schema=type(model).model_json_schema()
    desktop_fields={}
    if name=='Hyakkiyakou':
        from android_oas.hyakkiyakou import DESKTOP_FIELDS
        desktop_fields=DESKTOP_FIELDS
    # Respect OAS dynamic_hide (0xABCDEF sentinel) and ui_hidden markers, matching desktop config_model.py.
    try:
        visible=model.model_dump(context={'hide':True})
        defs=schema.get('$defs') or {}
        top_props=schema.get('properties') or {}
        for gkey in list(top_props.keys()):
            gprop=top_props.get(gkey) or {}
            if visible.get(gkey)==0xABCDEF or gprop.get('ui_hidden') is True:
                top_props.pop(gkey,None)
                continue
            gval=visible.get(gkey)
            if not isinstance(gval,dict):continue
            hidden=desktop_fields.get(gkey,())
            ref=gprop.get('$ref')
            if isinstance(ref,str) and ref.startswith('#/$defs/'):
                dname=ref[len('#/$defs/'):]
                target=defs.get(dname)
                if isinstance(target,dict) and isinstance(target.get('properties'),dict):
                    cloned=json.loads(json.dumps(target))
                    sub_props=cloned['properties']
                    for fkey in list(sub_props.keys()):
                        fprop=sub_props.get(fkey) or {}
                        if fkey in hidden or gval.get(fkey)==0xABCDEF or fprop.get('ui_hidden') is True:
                            sub_props.pop(fkey,None)
                    new_name=f'{dname}__{gkey}'
                    defs[new_name]=cloned
                    gprop['$ref']=f'#/$defs/{new_name}'
            elif isinstance(gprop.get('properties'),dict):
                sub_props=gprop['properties']
                for fkey in list(sub_props.keys()):
                    fprop=sub_props.get(fkey) or {}
                    if fkey in hidden or gval.get(fkey)==0xABCDEF or fprop.get('ui_hidden') is True:
                        sub_props.pop(fkey,None)
    except Exception:pass
    if name=='CustomTask':
        from tasks.CustomTask.config import TASK_TYPES
        variants=[];definitions=schema.setdefault('$defs',{})
        def rewrite(node,prefix):
            if isinstance(node,dict):
                return {key:(value.replace('#/$defs/','#/$defs/'+prefix) if key=='$ref' else rewrite(value,prefix)) for key,value in node.items()}
            if isinstance(node,list):return [rewrite(item,prefix) for item in node]
            return node
        for task,(label,kind) in TASK_TYPES.items():
            child=kind.model_json_schema();prefix=task+'-'
            definitions.update({prefix+key:rewrite(item,prefix) for key,item in child.pop('$defs',{}).items()})
            child=rewrite(child,prefix);child['properties'].pop('scheduler',None)
            variants.append(dict(type='object',title=label,properties={
                'id':dict(type='string',**{'x-random-id':True}),
                'type':dict(type='string',const=task,default=task),
                'settings':child}))
        schema['properties']['entries']['items']={'oneOf':variants}
    return json.dumps(dict(value=value,schema=schema),ensure_ascii=False)

def form_cases():
    from tasks.CustomTask.config import CustomTask,TASK_TYPES
    model=CustomTask()
    for task in TASK_TYPES:model.add(task)
    definition=json.loads(task_config('CustomTask'));definition['value']=model.model_dump(mode='json')
    return json.dumps([dict(task='CustomTask',label='CustomTask-six-entry-types',definition=definition)],ensure_ascii=False)

_LABEL_FILES=('module/config/i18n/zh-CN.json','assets/i18n/zh-CN.json')

def _labels_signature():
    return _file_signature(*_LABEL_FILES)

def _load_labels():
    global _labels_cache
    signature=_labels_signature()
    if _labels_cache is not None and _labels_cache[0]==signature:return _labels_cache
    from android_oas.task_titles import TITLES
    result=dict(TITLES)
    from android_oas.oasx_labels import TRANSLATIONS
    result.update(TRANSLATIONS)
    for filename in _LABEL_FILES:
        path=_root/filename
        if path.is_file():result.update(json.loads(path.read_text(encoding='utf-8-sig')))
    _labels_cache=(signature,result,json.dumps(result,ensure_ascii=False))
    return _labels_cache

def _label_map():
    return _load_labels()[1]

def labels():
    return _load_labels()[2]

def _task_is_running(preset,name):
    return preset==_config_name and _active and _running_task==name

def dashboard(preset=None):
    with _config_lock:
        name=_config_name if preset is None else _validate_preset_name(preset)
        return json.dumps(_dashboard(name,config(name)),ensure_ascii=False)

def _dashboard(preset,settings):
    from module.config.config_menu import ConfigMenu
    result=[];translations=json.loads(labels());locations={}
    # OASX gets this exact menu from /script_menu. Preserve both levels of order.
    for group_order,(group,names) in enumerate(ConfigMenu().menu.items()):
        for task_order,name in enumerate(names):
            locations.setdefault(name,dict(groupId=group,groupLabel=translations.get(group,group),
                menuOrder=group_order*1000+task_order))
    for entry in json.loads(catalog(settings)):
        key=entry['configKey'];model=getattr(settings.model,key) if key else None
        scheduler=getattr(model,'scheduler',None)
        result.append(dict(entry,**locations.get(entry['id'],dict(groupId='',groupLabel='',menuOrder=1000000)),enabled=bool(scheduler and scheduler.enable),
            hasScheduler=scheduler is not None,nextRun=str(scheduler.next_run) if scheduler else None,
            running=_task_is_running(preset,entry['id'])))
    return sorted(result,key=lambda entry:entry['menuOrder'])

def set_task_enabled(name,enabled,preset=None):
    with _config_lock:
        preset=_config_name if preset is None else _validate_preset_name(preset)
        if _task_is_running(preset,name):raise RuntimeError('当前任务正在运行中，暂不可修改其启用状态')
        settings=config(preset)
        entry=next(item for item in json.loads(catalog(settings)) if item['id']==name)
        if not entry['runnable']:raise ValueError('该入口不能加入任务计划')
        model=getattr(settings.model,entry['configKey']) if entry['configKey'] else None
        if model is None or not hasattr(model,'scheduler'):raise ValueError('该任务只能单独运行')
        model.scheduler.enable=bool(enabled);settings.save()
        _wakeup_event.set();_notify_state()
        return json.dumps(_dashboard(preset,settings),ensure_ascii=False)

def set_task_next_run(name,next_run,enable=None,preset=None):
    from datetime import datetime
    with _config_lock:
        preset=_config_name if preset is None else _validate_preset_name(preset)
        if _task_is_running(preset,name):raise RuntimeError('当前任务正在运行中，暂不可调整调度时间')
        settings=config(preset)
        entry=next(item for item in json.loads(catalog(settings)) if item['id']==name)
        if not entry['runnable']:raise ValueError('该入口不能加入任务计划')
        model=getattr(settings.model,entry['configKey']) if entry['configKey'] else None
        if model is None or not hasattr(model,'scheduler'):raise ValueError('该任务没有调度计划')
        model.scheduler.next_run=datetime.fromisoformat(str(next_run).strip().replace(' ','T'))
        if enable is not None:model.scheduler.enable=bool(enable)
        settings.save()
        _wakeup_event.set();_notify_state()
        return json.dumps(_dashboard(preset,settings),ensure_ascii=False)

def quick_run_task(name,preset=None):
    from datetime import datetime,timedelta
    target=(datetime.now().replace(microsecond=0)-timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S')
    return set_task_next_run(name,target,enable=True,preset=preset)

def quick_wait_task(name,preset=None):
    from datetime import datetime,timedelta
    target=(datetime.now().replace(microsecond=0)+timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S')
    return set_task_next_run(name,target,enable=True,preset=preset)

def bulk_quick_schedule(run_now,preset=None):
    from datetime import datetime,timedelta
    target=datetime.now().replace(microsecond=0)+(timedelta(days=-1) if run_now else timedelta(days=1))
    with _config_lock:
        preset=_config_name if preset is None else _validate_preset_name(preset)
        settings=config(preset);changed=False
        for entry in json.loads(catalog(settings)):
            if not entry['runnable'] or not entry['configKey']:continue
            if _task_is_running(preset,entry['id']):continue
            model=getattr(settings.model,entry['configKey'])
            scheduler=getattr(model,'scheduler',None)
            if scheduler is not None and scheduler.enable:
                scheduler.next_run=target;changed=True
        if changed:
            settings.save()
            _wakeup_event.set();_notify_state()
        return json.dumps(_dashboard(preset,settings),ensure_ascii=False)

def save_task_config(name,data,preset=None):
    with _config_lock:
        return _save_task_config(name,data,config(preset))

def _save_task_config(name,data,settings):
    entry=next(item for item in json.loads(catalog(settings)) if item['id']==name)
    if entry['configKey'] is None:
        if json.loads(str(data)):raise ValueError('该任务没有独立配置')
        return _task_config(name,settings)
    old=getattr(settings.model,entry['configKey'])
    replacement=type(old).model_validate(json.loads(str(data)))
    if name=='AutoCheckinBigGod' and not replacement.checkin_config.manual_claim:
        raise ValueError('手机版大神签到使用无需 Root 的界面领取模式，请保持“手动领取”开启')
    setattr(settings.model,entry['configKey'],replacement);settings.save()
    _wakeup_event.set();_notify_state()
    return _task_config(name,settings)

def _mirror_md_name(meta):
    import re
    def clean(val, fb):
        s = re.sub(r'[\\/:*?"<>|\s]+', '_', str(val or '').strip()).strip('_.')[:48]
        return s or fb
    cfg = clean(meta.get('config'), 'android')
    task = clean(meta.get('task') or meta.get('taskLabel'), 'error')
    ts = clean(meta.get('timeText') or meta.get('id'), 'log')
    return f'OASA-error-{cfg}-{task}-{ts}.md'

def _write_error_mirror(folder, meta, full_log):
    if _error_mirror_dir is None:
        return
    try:
        import base64
        _error_mirror_dir.mkdir(parents=True, exist_ok=True)
        dst = _error_mirror_dir / _mirror_md_name(meta)
        parts = [
            '# OASA 报错日志报告',
            '',
            f"- **配置名称**：`{meta.get('config', 'android')}`",
            f"- **报错任务**：{meta.get('taskLabel', '')} (`{meta.get('task', '')}`)",
            f"- **发生时间**：{meta.get('timeText', '')}",
            f"- **异常摘要**：{meta.get('summary', '')}",
            f"- **记录编号**：`{meta.get('id', '')}`",
            '',
        ]
        pngs = sorted(folder.glob('*.png'))
        if pngs:
            parts.append('## 现场截图')
            parts.append('')
            for idx, p in enumerate(pngs, 1):
                try:
                    raw = p.read_bytes()
                    mime = 'image/png'
                    try:
                        import cv2, numpy as np
                        arr = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
                        if arr is not None:
                            ok, jbuf = cv2.imencode('.jpg', arr, [int(cv2.IMWRITE_JPEG_QUALITY), 82])
                            if ok:
                                raw = jbuf.tobytes()
                                mime = 'image/jpeg'
                    except Exception:
                        pass
                    b64 = base64.b64encode(raw).decode('ascii')
                    parts.append(f'### 截图 {idx} · `{p.name}`')
                    parts.append('')
                    parts.append(f'![{p.name}](data:{mime};base64,{b64})')
                    parts.append('')
                except Exception:
                    pass
        parts.append('## 完整报错日志')
        parts.append('')
        parts.append('```text')
        parts.append(full_log)
        parts.append('```')
        dst.write_text('\n'.join(parts), encoding='utf-8')
        existing = sorted([p for p in _error_mirror_dir.glob('OASA-error-*.md')], key=lambda p: p.stat().st_mtime, reverse=True)
        for stale in existing[30:]:
            try:
                stale.unlink()
            except Exception:
                pass
    except Exception:
        pass

def _sync_existing_error_mirrors():
    if _error_mirror_dir is None or _root is None:
        return
    try:
        err_root = _root / 'log' / 'error'
        if not err_root.is_dir():
            return
        for folder in sorted([p for p in err_root.iterdir() if p.is_dir()], key=lambda p: p.name, reverse=True)[:30]:
            meta_file = folder / 'meta.json'
            log_file = folder / 'log.txt'
            if not meta_file.is_file() or not log_file.is_file():
                continue
            meta = json.loads(meta_file.read_text(encoding='utf-8'))
            dst = _error_mirror_dir / _mirror_md_name(meta)
            if not dst.is_file():
                _write_error_mirror(folder, meta, log_file.read_text(encoding='utf-8', errors='replace'))
    except Exception:
        pass

def _save_error_snapshot(task_name,error_text,tb_text,device=None,settings=None):
    try:
        err_cfg=getattr(getattr(settings,'model',None),'script',None)
        err_cfg=getattr(err_cfg,'error',None)
        if err_cfg is not None and not getattr(err_cfg,'save_error',True):
            return None
        ts_ms=int(time.time()*1000)
        err_root=_root/'log'/'error'
        folder=err_root/str(ts_ms)
        folder.mkdir(parents=True,exist_ok=True)
        frames=[]
        if device is not None:
            q=getattr(device,'screenshot_deque',None)
            if q:frames=list(q)
            elif getattr(device,'image',None) is not None:frames=[(ts_ms,device.image)]
        limit=max(1,min(20,int(getattr(err_cfg,'screenshot_length',1) or 1)))
        frames=frames[-limit:]
        saved_images=0
        if frames:
            import cv2
            for idx,(_,img) in enumerate(frames,1):
                try:
                    ok,buf=cv2.imencode('.png',cv2.cvtColor(img,cv2.COLOR_RGB2BGR))
                    if ok:
                        (folder/f'{idx:03d}.png').write_bytes(buf.tobytes())
                        saved_images+=1
                except Exception:pass
        translations=json.loads(labels())
        meta=dict(
            id=str(ts_ms),
            timestampMs=ts_ms,
            timeText=time.strftime('%Y-%m-%d %H:%M:%S',time.localtime(ts_ms/1000.0)),
            config=_config_name,
            task=str(task_name),
            taskLabel=translations.get(str(task_name),str(task_name)),
            summary=str(error_text),
            imageCount=saved_images,
        )
        (folder/'meta.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
        log_body='\n'.join(list(_recent_logs))
        full_log=f"{log_body}\n\n=== ERROR ({meta['timeText']} · {meta['taskLabel']}) ===\n{error_text}\n{tb_text}".strip()
        (folder/'log.txt').write_text(full_log,encoding='utf-8')
        _write_error_mirror(folder,meta,full_log)
        # Keep at most 30 error folders to avoid filling phone storage.
        import shutil
        existing=sorted([p for p in err_root.iterdir() if p.is_dir()],key=lambda p:p.name,reverse=True)
        for stale in existing[30:]:
            shutil.rmtree(stale,ignore_errors=True)
        return str(ts_ms)
    except Exception:
        return None

def list_error_logs(limit=30):
    err_root=_root/'log'/'error'
    if not err_root.is_dir():return '[]'
    items=[]
    for folder in sorted([p for p in err_root.iterdir() if p.is_dir()],key=lambda p:p.name,reverse=True)[:int(limit)]:
        meta_file=folder/'meta.json'
        if meta_file.is_file():
            try:
                items.append(json.loads(meta_file.read_text(encoding='utf-8')))
                continue
            except Exception:pass
        pngs=sorted(folder.glob('*.png'))
        items.append(dict(id=folder.name,timestampMs=int(folder.name) if folder.name.isdigit() else 0,
            timeText=folder.name,config=_config_name,task='',taskLabel='异常记录',summary='查看详情',imageCount=len(pngs)))
    return json.dumps(items,ensure_ascii=False)

def get_error_log(error_id):
    clean=str(error_id or '').strip()
    if not clean.isdigit():raise ValueError('无效的错误记录编号')
    folder=_root/'log'/'error'/clean
    if not folder.is_dir():raise ValueError('错误记录不存在')
    meta_file=folder/'meta.json'
    meta=json.loads(meta_file.read_text(encoding='utf-8')) if meta_file.is_file() else dict(
        id=clean,timeText=clean,task='',taskLabel='异常记录',summary='',imageCount=0)
    log_file=folder/'log.txt'
    log_text=log_file.read_text(encoding='utf-8') if log_file.is_file() else ''
    images=[str(p) for p in sorted(folder.glob('*.png'))]
    meta['imageCount']=len(images)
    return json.dumps(dict(meta=meta,logText=log_text,imagePaths=images),ensure_ascii=False)

def delete_error_log(error_id=None):
    import shutil
    err_root=_root/'log'/'error'
    if not err_root.is_dir():return '[]'
    if error_id is None or not str(error_id).strip():
        shutil.rmtree(err_root,ignore_errors=True)
        if _error_mirror_dir is not None and _error_mirror_dir.is_dir():
            for f in _error_mirror_dir.glob('OASA-error-*.md'):
                try:f.unlink()
                except Exception:pass
    else:
        clean=str(error_id).strip()
        if clean.isdigit():
            folder=err_root/clean
            if _error_mirror_dir is not None and (folder/'meta.json').is_file():
                try:
                    meta=json.loads((folder/'meta.json').read_text(encoding='utf-8'))
                    md=_error_mirror_dir/_mirror_md_name(meta)
                    if md.is_file():md.unlink()
                except Exception:pass
            shutil.rmtree(folder,ignore_errors=True)
    return list_error_logs()

def _stats_service():
    from module.server import log_stats
    log_stats.LOG_ROOT=(_root/'log').resolve()
    return log_stats.log_stats_service

def stats_dates(script_name=None):
    name=str(script_name or _config_name).strip() or _config_name
    payload=_stats_service().list_available_dates(name)
    return json.dumps(payload,ensure_ascii=False)

def stats_day(date_text=None,script_name=None):
    from datetime import date,datetime
    name=str(script_name or _config_name).strip() or _config_name
    raw_date=str(date_text or '').strip()
    target_day=datetime.strptime(raw_date,'%Y-%m-%d').date() if raw_date else date.today()
    payload=_stats_service().build_stats(name,target_day)
    payload['date']=target_day.isoformat()
    return json.dumps(payload,ensure_ascii=False)

def _income_mod():
    from module import log_income
    log_income.INCOME_LOG_DIR=(_root/'log').resolve()
    log_income.INCOME_STORE_DIR=(_root/'log'/'income').resolve()
    return log_income

def income_dates(script_name=None):
    name=str(script_name or _config_name).strip() or _config_name
    dates=[d.isoformat() for d in _income_mod().income_dates(name)]
    return json.dumps(dict(script_name=name,dates=dates),ensure_ascii=False)

def income_day(date_text=None,script_name=None):
    from datetime import date,datetime
    mod=_income_mod()
    name=str(script_name or _config_name).strip() or _config_name
    raw_date=str(date_text or '').strip()
    target_day=datetime.strptime(raw_date,'%Y-%m-%d').date() if raw_date else date.today()
    report=mod.build_income_report(name,target_day)
    if report is None:
        report=dict(script_name=name,date=target_day.isoformat(),collected_at='',baseline_date='',items=[])
    return json.dumps(report,ensure_ascii=False)

def income_history(days=30,script_name=None):
    name=str(script_name or _config_name).strip() or _config_name
    payload=_income_mod().income_history(name,max(1,min(3650,int(days or 30))))
    return json.dumps(payload,ensure_ascii=False)

def income_delete(date_text=None,script_name=None):
    from datetime import datetime
    mod=_income_mod()
    name=str(script_name or _config_name).strip() or _config_name
    raw_date=str(date_text or '').strip()
    if raw_date:
        days=[datetime.strptime(part.strip(),'%Y-%m-%d').date() for part in raw_date.split(',') if part.strip()]
    else:
        days=mod.income_dates(name)
    deleted=mod.delete_income_days(name,days)
    remaining=[d.isoformat() for d in mod.income_dates(name)]
    return json.dumps(dict(script_name=name,deleted=deleted,dates=remaining),ensure_ascii=False)

def run(name,host=None,preset=None):
    global _active,_running_task,_last_outcome,_host
    from module.device.device import Device
    from module.exception import GameNotRunningError,TaskEnd
    with _config_lock:
        if host is not None:_host=host
        if preset is not None:_activate_preset_locked(preset)
        if _host is not None and _host.isRunning():
            _stop_event.clear()
        _active=True;_running_task=name;_last_outcome=None
    _notify_state()
    cooperation=None
    device=None
    settings=None
    trace_count=0
    def trace(frame,event,arg):
        nonlocal trace_count
        trace_count+=1
        if trace_count%32==0:check()
        return trace
    try:
        with _config_lock:
            settings=config();settings.task=types.SimpleNamespace(command=name)
            settings.model.script.device.package_name=str(_host.gamePackage())
            settings.save()
        from module.cooperation import Cooperation
        cooperation=Cooperation(settings.config_name);cooperation.start();cooperation.publish(name)
        settings.cooperation_runtime=cooperation
        device=Device(settings)
        if name not in ('Restart','AutoCheckinBigGod') and not device.app_is_running():
            raise GameNotRunningError('游戏未运行')
        if name!='Restart':
            device.screenshot()
        task=_entry(name)(config=settings,device=device)
        # Preserve task_delay semantics but retain the actual scheduler success for the run report.
        original=settings.task_delay
        def record_delay(*args,**kwargs):
            global _last_outcome
            _last_outcome=kwargs.get('success')
            with _config_lock:
                ret=original(*args,**kwargs)
            _notify_state()
            return ret
        settings.task_delay=record_delay
        from module.logger import logger
        logger.info(f'[Task] {name} (android)')
        logger.hr(name,level=0)
        sys.settrace(trace)
        try:
            result=task.run()
        except TaskEnd:
            result=None
        finally:
            settings.task_delay=original
            logger.info(f'{name} task ended')
        check()
        status='failed' if _last_outcome is False or result is False else 'finished'
        return json.dumps(dict(task=name,status=status,detail='任务流程已结束，真实效果请核对游戏',runtimeOutcome=getattr(settings,'task_runtime_outcome',None)),ensure_ascii=False,default=str)
    except Cancelled as error:
        try: logger.info(f'{name} cancelled')
        except Exception: pass
        return json.dumps(dict(task=name,status='cancelled',detail=str(error)),ensure_ascii=False)
    except GameNotRunningError as error:
        return json.dumps(dict(task=name,status='game_not_running',detail=str(error)),ensure_ascii=False)
    except Exception as error:
        try: logger.error(f'{name} failed: {error}')
        except Exception: pass
        tb=traceback.format_exc()
        error_id=_save_error_snapshot(name,str(error),tb,device=device,settings=settings)
        return json.dumps(dict(task=name,status='failed',detail=str(error),traceback=tb,errorId=error_id),ensure_ascii=False)
    finally:
        sys.settrace(None)
        with _config_lock:_active=False;_running_task=None
        _notify_state()
        if cooperation is not None:cooperation.close()

def scheduled_plan():
    with _config_lock:
        settings=config();settings.reload();settings.update_scheduler()
        allowed={entry['id'] for entry in json.loads(catalog()) if entry['runnable']}
        pending=[task for task in settings.pending_task if task.command in allowed]
        pending.sort(key=lambda t:(0 if t.command=='Restart' else 1,t.next_run))
        waiting=[task for task in settings.waiting_task if task.command in allowed]
        return pending,waiting

def schedule(host,preset=None):
    global _host,_schedule_active
    with _config_lock:
        _host=host
        _activate_preset_locked(preset)
        _schedule_active=True
    try:
        return _schedule(host)
    finally:
        with _config_lock:_schedule_active=False

def _schedule(host):
    global _guard
    from datetime import datetime,timedelta
    import copy
    from module.logger import logger
    from module.config.anti_ban import AntiBanGuard
    if _guard is None:_guard=AntiBanGuard()
    history=[];failures={}
    if host.isRunning():
        _stop_event.clear();_wakeup_event.clear()
    source=ast.parse((_root/'script.py').read_text(encoding='utf-8-sig'))
    script=next(node for node in source.body if isinstance(node,ast.ClassDef) and node.name=='Script')
    method=next(node for node in script.body if isinstance(node,ast.FunctionDef) and node.name=='_hoard_next_task')
    namespace=dict(datetime=datetime,timedelta=timedelta,copy=copy,logger=logger)
    exec(compile(ast.Module(body=[method],type_ignores=[]),'oas-task-hoarding','exec'),namespace)
    hoarding=types.SimpleNamespace(config=config(),task_hoarding_until=None,task_hoarding_released=False,is_first_task=True)
    idle_since=None;idle_prepared=False;game_closed=False;display_released=False;wait_message=None
    try:
        while host.isRunning() and not _stop_event.is_set():
            pending,waiting=scheduled_plan()
            if not pending and not waiting:return json.dumps(dict(status='finished',detail='没有启用的待运行任务',runs=history),ensure_ascii=False)
            wake=_guard.wake_time(datetime.now(),config().script.anti_ban)
            if wake is not None:
                message='按作息配置休息至 '+str(wake)
                if message!=wait_message:host.log(message);wait_message=message
                _wait_idle(host,(wake-datetime.now()).total_seconds());continue
            if not pending:
                hoarding.task_hoarding_until=None;hoarding.task_hoarding_released=False
                from datetime import datetime
                delay=max(0.0,(waiting[0].next_run-datetime.now()).total_seconds())
                if idle_since is None:idle_since=time.monotonic()
                optimization=config().script.optimization
                mode=str(getattr(optimization.when_task_queue_empty,'value',optimization.when_task_queue_empty))
                limit=optimization.close_game_limit_time
                threshold=limit.hour*3600+limit.minute*60+limit.second
                should_close=mode in ('close_game','close_emulator_or_close_game') and (threshold<=0 or delay>threshold)
                if should_close and not game_closed:
                    host.log('下一任务间隔超过阈值，自动关闭游戏省电')
                    host.stopPackage(str(host.gamePackage()));game_closed=True;idle_prepared=True
                    if mode=='close_emulator_or_close_game' and not display_released and hasattr(host,'releaseDisplay'):
                        host.log('释放后台虚拟屏以进一步省电')
                        host.releaseDisplay();display_released=True
                elif not idle_prepared:
                    if host.isGameForeground():
                        result=json.loads(run('GotoMain',host));history.append(result)
                        if result['status'] not in ('finished','game_not_running'):
                            return json.dumps(dict(status=result['status'],detail='空闲前返回庭院失败：'+result['detail'],runs=history),ensure_ascii=False)
                    idle_prepared=True
                message='等待计划任务：'+waiting[0].command
                if message!=wait_message:host.log(message);wait_message=message
                _wait_idle(host,delay);continue
            candidate=namespace['_hoard_next_task'](hoarding,pending[0],datetime.now())
            if candidate.next_run>datetime.now():_wait_idle(host,(candidate.next_run-datetime.now()).total_seconds());continue
            if display_released and hasattr(host,'ensureDisplay'):
                host.log('计划任务到点，自动重建后台虚拟屏')
                host.ensureDisplay();display_released=False;_wait_schedule(0.8)
            if game_closed:game_closed=False
            if not host.isRunning() or _stop_event.is_set():break
            idle_since=None;idle_prepared=False;wait_message=None;started=time.monotonic()
            result=json.loads(run(candidate.command,host));history.append(result)
            hoarding.is_first_task=False
            _guard.record_active(time.monotonic()-started)
            if len(history)>100:history=history[-100:]
            if result['status']=='cancelled':return json.dumps(dict(status='cancelled',detail='计划运行已停止',runs=history),ensure_ascii=False)
            if result['status']=='game_not_running':
                logger.warning('检测到游戏未运行，自动触发 Restart 启动登录')
                with _config_lock:config().task_call('Restart')
                _notify_state()
                if not host.isRunning() or _stop_event.is_set():break
                restart_res=json.loads(run('Restart',host));history.append(restart_res)
                if restart_res['status']=='cancelled':
                    return json.dumps(dict(status='cancelled',detail='计划运行已停止',runs=history),ensure_ascii=False)
                if restart_res['status']=='failed':
                    failures['Restart']=failures.get('Restart',0)+1
                    if failures['Restart']>=3:
                        return json.dumps(dict(status='failed',detail='Restart 启动游戏连续失败三次，计划已停止',runs=history),ensure_ascii=False)
                else:
                    failures['Restart']=0
                _notify_state()
                _wait_schedule(0.2);continue
            if result['status']=='failed':
                failures[candidate.command]=failures.get(candidate.command,0)+1
                if failures[candidate.command]>=3:return json.dumps(dict(status='failed',detail=candidate.command+' 连续失败三次，计划已停止',runs=history),ensure_ascii=False)
                with _config_lock:config().task_delay(candidate.command,success=False,server=False)
                _notify_state()
            else:failures[candidate.command]=0
            # Some immediate tools intentionally have no next-run update. Do not hot-loop them.
            pending_after,_=scheduled_plan()
            if any(task.command==candidate.command and task.next_run<=candidate.next_run for task in pending_after):
                from datetime import datetime,timedelta
                with _config_lock:config().task_delay(candidate.command,target=datetime.now()+timedelta(minutes=1),server=False)
                _notify_state()
            _wait_schedule(0.1)
        return json.dumps(dict(status='cancelled',detail='计划运行已停止或达到时限',runs=history),ensure_ascii=False)
    except Exception as error:return json.dumps(dict(status='failed',detail=str(error),traceback=traceback.format_exc(),runs=history),ensure_ascii=False)

def validate_task_config(name,data):
    entry=next(item for item in json.loads(catalog()) if item['id']==name)
    if entry['configKey'] is None:return '{}'
    model=getattr(config().model,entry['configKey'])
    return type(model).model_validate(json.loads(str(data))).model_dump_json()

def equal_config(left,right):return json.loads(str(left))==json.loads(str(right))

def audit():
    import socket
    saved_connect=socket.socket.connect;saved_connect_ex=socket.socket.connect_ex;saved_create=socket.create_connection
    def offline(*args,**kwargs):raise OSError('Offline task audit does not open network connections')
    socket.socket.connect=offline;socket.socket.connect_ex=offline;socket.create_connection=offline
    try:return _audit_entries()
    finally:
        socket.socket.connect=saved_connect;socket.socket.connect_ex=saved_connect_ex;socket.create_connection=saved_create

def _audit_entries():
    report=[]
    for entry in json.loads(catalog()):
        if not entry['runnable']:continue
        try:
            factory=_entry(entry['id'])
            description=json.loads(task_config(entry['id']))
            # Validate all default nested options using the actual Pydantic 2 model, not a substitute.
            key=entry['configKey']
            if key is not None:
                model=getattr(config().model,key);type(model).model_validate(description['value'])
            from module.device.device import Device
            task=factory(config=config(),device=Device(config()))
            report.append(dict(task=entry['id'],status='ready',runMethod=task.run.__qualname__,constructed=True))
        except Exception as error:report.append(dict(task=entry['id'],status='failed',detail=str(error),traceback=traceback.format_exc()))
    return json.dumps(report,ensure_ascii=False)
