"""Portable ONNX detector and multi-object tracker for the existing eight-column Hya agent API.

The upstream wheel contains a Windows-only Cython extension. Android keeps its
model/agent and uses actual CPU inference plus class-aware motion association.
"""
from datetime import timedelta
from pathlib import Path
import time
import numpy as np
import cv2

class Tracker:
    def __init__(self,args=None):
        args=args or {}
        self.conf_threshold=float(args.get('conf_threshold',.5));self.iou_threshold=float(args.get('iou_threshold',.5))
        if not 0<self.conf_threshold<=1 or not 0<self.iou_threshold<=1:raise ValueError('Invalid detector thresholds')
        self.precision=args.get('precision','fp32');self.inference_engine=args.get('inference_engine','onnxruntime')
        if self.precision!='fp32' or self.inference_engine!='onnxruntime':raise ValueError('Android uses the original FP32 CPU model')
        self.debug=bool(args.get('debug',False));self.dual_detect=True;self.toggle=True
        self.detect_time=timedelta();self.track_time=timedelta();self._session=None;self.clear_tracks()
    def clear_tracks(self):
        self.current_tracks=[];self._tracks={};self.id_cnt=0;self.last_time=time.monotonic()
    def _predict(self,image):
        if self._session is None:
            from onnxruntime import InferenceSession
            self._session=InferenceSession(str(Path.cwd()/'oashya/mobile_fp32.onnx'),providers=['CPUExecutionProvider'])
        tensor=cv2.resize(image,(640,640),interpolation=cv2.INTER_LINEAR)[:,:,::-1].transpose(2,0,1)[None].astype(np.float32)/255
        return self._session.run(None,{self._session.get_inputs()[0].name:np.ascontiguousarray(tensor)})[0]
    def postprocess(self,output):
        result=[]
        for row in np.asarray(output).reshape(-1,6):
            x1,y1,x2,y2,confidence,label=map(float,row)
            if confidence<self.conf_threshold or x2<=x1 or y2<=y1:continue
            result.append((int(label),confidence,[int((x1+x2)/2),int((y1+y2)/2),int(x2-x1),int(y2-y1)]))
        return result
    @staticmethod
    def right_offset(detections):return [(label,confidence,[box[0]+640,*box[1:]]) for label,confidence,box in detections]
    def detect(self,image):
        started=time.monotonic()
        if image.shape[:2]!=(720,1280):raise ValueError('Hyakkiyakou uses 1280x720 screenshots')
        left=self.postprocess(self._predict(image[:640,:640]))
        right=self.right_offset(self.postprocess(self._predict(image[:640,640:1280])))
        result=left+right;self.detect_time=timedelta(seconds=time.monotonic()-started);return result
    def __call__(self,image,response=None):
        detections=self.detect(image);return self.update(detections,response=response)
    def update(self,detections,response=None,now=None):
        started=time.monotonic();now=started if now is None else float(now)
        dt=max(.001,now-self.last_time);self.last_time=now
        used=set();observed=set();result=[]
        # Globally choose nearest class-compatible predicted center; reject implausible jumps.
        candidates=[]
        for index,(label,confidence,box) in enumerate(detections):
            for track_id,state in self._tracks.items():
                if state['label']!=label:continue
                predicted=state['box'][0]+state['velocity']*dt*1000
                distance=float(np.hypot(box[0]-predicted,box[1]-state['box'][1]))
                if distance<max(100,box[2]*1.5):candidates.append((distance,index,track_id))
        assignments={}
        for distance,index,track_id in sorted(candidates):
            if index not in assignments and track_id not in used:assignments[index]=track_id;used.add(track_id)
        for index,(label,confidence,box) in enumerate(detections):
            track_id=assignments.get(index)
            if track_id is None:track_id=self.id_cnt;self.id_cnt+=1;velocity=-.1
            else:velocity=.6*self._tracks[track_id]['velocity']+.4*(box[0]-self._tracks[track_id]['box'][0])/(dt*1000)
            self._tracks[track_id]=dict(label=label,confidence=confidence,box=list(box),velocity=float(velocity),seen=now)
            observed.add(track_id)
        for track_id,state in list(self._tracks.items()):
            if track_id not in observed:
                hit=bool(response and len(response)>2 and response[2] and abs(float(response[0])-state['box'][0])<max(100,state['box'][2]))
                if hit or now-state['seen']>.35:del self._tracks[track_id];continue
                state['box'][0]+=state['velocity']*dt*1000
            x,y,w,h=state['box']
            if x+w/2<0 or x-w/2>1280:del self._tracks[track_id];continue
            result.append((track_id,state['label'],state['confidence'],int(x),int(y),int(w),int(h),state['velocity']))
        self.current_tracks=result;self.track_time=timedelta(seconds=time.monotonic()-started);return result
