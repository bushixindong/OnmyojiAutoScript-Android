"""Account-free integration probes, invoked only by the debug audit activity."""
import json
from pathlib import Path
import time
import traceback
import cv2
import numpy as np

def run(fixtures):
    from android_oas import bootstrap as engine
    report=[]
    def probe(name,fn):
        try:report.append(dict(test=name,status='passed',detail=fn()))
        except BaseException as error:report.append(dict(test=name,status='failed',detail=str(error),traceback=traceback.format_exc()))
    fixtures=Path(str(fixtures))
    def ocr():
        from module.ocr.models import get_ocr_model
        from module.server.setting import State
        assert State.deploy_config.OcrClientAddress
        system=get_ocr_model('ch');lines=[]
        for entry in json.loads((fixtures/'expected.json').read_text()):
            image=cv2.cvtColor(cv2.imread(str(fixtures/entry['file'])),cv2.COLOR_BGR2RGB)
            text,score=system.ocr_single_line(image)
            assert text==entry['expected'],(entry,text,score)
            assert abs(float(score)-entry['confidence'])<.015,(entry,score)
            lines.append(dict(text=text,score=float(score)))
        return lines
    probe('original-ocr-lines',ocr)
    def detector():
        from ppocronnx.predict_system import TextSystem
        system=TextSystem(use_angle_cls=False,ort_providers=['CPUExecutionProvider'])
        system.text_detector.preprocess_op[0].limit_type='min'
        system.text_detector.preprocess_op[0].limit_side_len=320
        results=[]
        for entry in json.loads((fixtures/'det-expected.json').read_text()):
            image=cv2.cvtColor(cv2.imread(str(fixtures/entry['file'])),cv2.COLOR_BGR2RGB)
            boxes=system.detect_and_ocr(image)
            assert {box.ocr_text for box in boxes}=={box['text'] for box in entry['boxes']},[(box.ocr_text,float(box.score)) for box in boxes]
            for expected in entry['boxes']:
                actual=next(box for box in boxes if box.ocr_text==expected['text'])
                assert np.max(np.abs(actual.box-np.array(expected['corners'])))<=4,(expected,actual.box)
            results.append(dict(file=entry['file'],texts=[box.ocr_text for box in boxes]))
        return results
    probe('original-ocr-detection',detector)
    class FakeHost:
        def __init__(self):self.running=True;self.touches=[];self.frames=0;self.page='exploration';self.expires=time.monotonic()+30
        def isRunning(self):return self.running and time.monotonic()<self.expires
        def gamePackage(self):return 'com.netease.onmyoji'
        def log(self,text):pass
        def screenshot(self):
            self.frames+=1
            from tasks.GameUi.assets import GameUiAssets
            from tasks.GlobalGame.assets import GlobalGameAssets
            image=np.zeros((720,1280,3),np.uint8)
            rules=[GameUiAssets.I_CHECK_MAIN] if self.page=='main' else [GameUiAssets.I_CHECK_EXPLORATION,GlobalGameAssets.I_UI_BACK_YELLOW]
            for rule in rules:
                x,y,w,h=map(int,rule.roi_front)
                image[y:y+h,x:x+w]=cv2.resize(rule.image,(w,h))
            return cv2.imencode('.png',cv2.cvtColor(image,cv2.COLOR_RGB2BGR))[1].tobytes()
        def tap(self,x,y):
            self.touches.append((x,y))
            from tasks.GlobalGame.assets import GlobalGameAssets
            rx,ry,rw,rh=GlobalGameAssets.I_UI_BACK_YELLOW.roi_front
            if self.page=='exploration' and rx<=x<rx+rw and ry<=y<ry+rh:self.page='main'
            else:raise AssertionError('Unexpected fake touch: '+str((x,y,self.page)))
        def isGameForeground(self):return True
        def isPortrait(self):return False
        def shell(self,args):raise AssertionError('Replay attempted shell query: '+str(args))
        def pressAndDrag(self,*args):self.touches.append(('drag',args))
        def drawPath(self,path,interval):self.touches.append(('draw',len(json.loads(path)),interval))
    original_host=engine._host
    original_config=engine._config
    # Tests use their own private config and cooperation database, never user options.
    from module.config.config import Config
    engine._config=Config('android-audit')
    engine._config.model.script.device.control_method='adb'
    try:
        def dashboard():
            entries=json.loads(engine.dashboard())
            assert sum(item['runnable'] for item in entries)==62
            assert next(item for item in entries if item['id']=='DailyTrifles')['label']=='每日琐事'
            old=engine._config.model.daily_trifles.scheduler.enable
            old_hya=engine._config.model.hyakkiyakou.scheduler.enable
            try:
                changed=json.loads(engine.set_task_enabled('DailyTrifles',not old))
                assert next(item for item in changed if item['id']=='DailyTrifles')['enabled']==(not old)
                engine._config.reload()
                assert engine._config.model.daily_trifles.scheduler.enable==(not old)
                changed=json.loads(engine.set_task_enabled('Hyakkiyakou',not old_hya))
                hya=next(item for item in changed if item['id']=='Hyakkiyakou')
                assert hya['runnable'] and hya['enabled']==(not old_hya)
            finally:
                engine.set_task_enabled('DailyTrifles',old)
                engine.set_task_enabled('Hyakkiyakou',old_hya)
            return dict(runnable=62,labels='Chinese',toggle='persisted')
        probe('ui-dashboard-enable-roundtrip',dashboard)
        def original_menu():
            from module.config.config_menu import ConfigMenu
            entries=json.loads(engine.dashboard());known={entry['id'] for entry in entries}
            expected=[(group,name) for group,names in ConfigMenu().menu.items() for name in names if name in known]
            actual=[(entry['groupId'],entry['id']) for entry in entries if entry['groupId']]
            assert actual==expected,(actual,expected)
            by_id={entry['id']:entry for entry in entries}
            assert by_id['WeeklyTrifles']['groupId']=='Weekly Task'
            assert by_id['DraftDuel']['groupId']=='Weekly Task'
            assert by_id['RealmRaid']['groupId']=='Guild'
            assert by_id['Hyakkiyakou']['groupId']=='Liver Emperor Exclusive' and by_id['Hyakkiyakou']['runnable']
            groups=list(dict.fromkeys(group for group,_ in actual))
            return dict(groups=groups,entryCount=len(actual),source='ConfigMenu',order='exact')
        probe('original-task-menu-groups',original_menu)
        def hyakkiyakou():
            from module.device.device import Device
            from module.exception import GameStuckError
            from oashya.labels import MODEL_CLASS_IDS, id2label, id2name, label2id
            settings=engine._config
            task=engine._entry('Hyakkiyakou')(config=settings,device=Device(settings))
            tracker=task.tracker
            assert label2id('sr_001')==MODEL_CLASS_IDS[4] and id2name(MODEL_CLASS_IDS[4])=='桃花妖'
            assert task._rarity_score(MODEL_CLASS_IDS[3]) > task._rarity_score(MODEL_CLASS_IDS[2])
            assert task._rarity_score(MODEL_CLASS_IDS[174])==6
            assert id2label(MODEL_CLASS_IDS[178])=='buff_006' and MODEL_CLASS_IDS[187] is None
            tracker.detect(np.zeros((720,1280,3),dtype=np.uint8))
            first=tracker.update([(5,.9,(500,300,80,100))],now=1.0)
            second=tracker.update([(5,.9,(470,300,80,100))],now=1.3)
            assert len(first)==len(second)==1 and second[0][0]==first[0][0]
            assert second[0][-1]<0 and len(second[0])==8
            previous_host=engine._host
            host=FakeHost()
            host.page='main'
            try:
                engine._host=host
                task.device.stuck_timer.clear()
                task.hya_fs_check_timer.reset()
                task.fast_screenshot(None)
                task.device.screenshot()
                assert host.frames==2
                task.hya_fs_check_timer.clear()
                try:task.fast_screenshot(None)
                except GameStuckError as error:assert str(error)=='百鬼夜行对局超时'
                else:raise AssertionError('Expected the round watchdog to stop an overdue round')
            finally:
                task.hya_fs_check_timer.reset()
                engine._host=previous_host
            return dict(model='FP32 CPU',trackId=second[0][0],x=second[0][3])
        probe('hyakkiyakou-model-and-tracker',hyakkiyakou)
        def navigation():
            host=FakeHost();result=json.loads(engine.run('GotoMain',host))
            assert result['status']=='finished',result
            assert host.page=='main' and len(host.touches)>=1,(host.page,host.touches)
            return dict(status=result['status'],frames=host.frames,touches=host.touches)
        probe('original-goto-main-navigation',navigation)
        def raw_rgb_navigation():
            import struct
            class RawFakeHost(FakeHost):
                def screenshotRgb(self):
                    self.frames+=1
                    from tasks.GameUi.assets import GameUiAssets
                    from tasks.GlobalGame.assets import GlobalGameAssets
                    image=np.zeros((720,1280,3),np.uint8)
                    rules=[GameUiAssets.I_CHECK_MAIN] if self.page=='main' else [GameUiAssets.I_CHECK_EXPLORATION,GlobalGameAssets.I_UI_BACK_YELLOW]
                    for rule in rules:
                        x,y,w,h=map(int,rule.roi_front)
                        image[y:y+h,x:x+w]=cv2.resize(rule.image,(w,h))
                    payload=image.tobytes()
                    return struct.pack('<IIII',0x4f415352,1280,720,len(payload))+payload
                def screenshot(self):raise AssertionError('PNG fallback should not run when screenshotRgb is present')
            host=RawFakeHost();result=json.loads(engine.run('GotoMain',host))
            assert result['status']=='finished' and host.page=='main' and len(host.touches)>=1,result
            return dict(status=result['status'],frames=host.frames,touches=host.touches)
        probe('raw-rgb-frame-path',raw_rgb_navigation)
        def device_actions():
            from module.device.device import Device
            host=FakeHost();engine._host=host;device=Device(engine._config)
            device.screenshot();device.click(12,24);device.press_and_drag((30,40),(120,240),hold_duration=.2,swipe_duration=.4)
            device.draw_adb([(100,100),(400,300),(800,100)])
            assert any(action[0]=='drag' for action in host.touches if isinstance(action[0],str)),host.touches
            assert any(action[0]=='draw' for action in host.touches if isinstance(action[0],str)),host.touches
            assert device.get_orientation()==0
            return host.touches
        # Accept the first isolated tap for the low-level API probe.
        original_tap=FakeHost.tap
        FakeHost.tap=lambda self,x,y:self.touches.append((x,y))
        probe('device-draw-hold-drag',device_actions)
        FakeHost.tap=original_tap
        def cancellation():
            host=FakeHost();host.running=False
            result=json.loads(engine.run('GotoMain',host))
            assert result['status']=='cancelled' and not host.touches,result
            return result['status']
        probe('executor-cancellation',cancellation)
        def failure():
            host=FakeHost()
            host.screenshot=lambda: (_ for _ in ()).throw(RuntimeError('synthetic screenshot failure'))
            result=json.loads(engine.run('GotoMain',host))
            assert result['status']=='failed' and 'synthetic screenshot failure' in result['detail'],result
            return result['status']
        probe('executor-failure',failure)
        def empty_plan():
            for name in type(engine._config.model).model_fields:
                item=getattr(engine._config.model,name)
                if hasattr(item,'scheduler'):item.scheduler.enable=False
            engine._config.save()
            result=json.loads(engine.schedule(FakeHost()))
            assert result['status']=='finished' and not result['runs'],result
            return result['status']
        probe('scheduler-empty-plan',empty_plan)
    finally:engine._host=original_host;engine._config=original_config
    return json.dumps(report,ensure_ascii=False)
