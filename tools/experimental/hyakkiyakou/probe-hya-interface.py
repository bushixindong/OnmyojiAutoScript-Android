"""Synthetic interface oracle for the published tracker; never captures or controls a game."""
import onnxruntime as ort
import numpy as np
original=ort.InferenceSession
class Session:
    def __init__(self,*args,**kwargs):self.real=original(*args,**kwargs)
    def __getattr__(self,name):return getattr(self.real,name)
    def run(self,names,feed,*args,**kwargs):
        value=next(iter(feed.values()))
        print('input',value.shape,'corners',value[0,:,0,0],value[0,:,-1,-1])
        output=np.zeros((1,300,6),np.float32);output[0,0]=[100,100,200,200,.9,3]
        return [output]
ort.InferenceSession=Session
from oashya.tracker import Tracker
tracker=Tracker(args=dict(conf_threshold=.5,iou_threshold=.5,precision='fp32',inference_engine='onnxruntime',debug=False))
image=np.zeros((720,1280,3),np.uint8)
image[:,:640,0]=100;image[:,640:,0]=200
image[:,:,1]=(np.arange(720,dtype=np.int32)%256).astype(np.uint8)[:,None]
print('detect',tracker.detect(image))
print('call',tracker(image,response=[0,0,False,10]))
