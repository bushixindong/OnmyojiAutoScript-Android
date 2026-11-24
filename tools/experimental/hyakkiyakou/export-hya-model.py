"""Export the MIT oashya model through its installed CPU runtime for Android compatibility.

The published archive contains only a Windows extension; no executable from it is
shipped to Android. This captures the real ONNX data passed by its normal loader.
"""
import hashlib
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True
import onnxruntime

root=Path(__file__).resolve().parents[1]
destination=root/'app/src/main/assets/runtime'
original=onnxruntime.InferenceSession
captured=[]
def record(model,*args,**kwargs):
    data=Path(model).read_bytes() if isinstance(model,str) else bytes(model)
    captured.append(data)
    return original(model,*args,**kwargs)
onnxruntime.InferenceSession=record
from oashya.tracker import Tracker
tracker=Tracker(args=dict(conf_threshold=.5,iou_threshold=.5,precision='fp32',inference_engine='onnxruntime',debug=False))
import numpy as np
tracker.detect(np.zeros((720,1280,3),dtype=np.uint8))
if len(captured)!=1:raise RuntimeError(f'Expected one model, observed {len(captured)}')
data=captured[0]
(destination/'hya-mobile-fp32.onnx').write_bytes(data)
session=original(data,providers=['CPUExecutionProvider'])
report=dict(source='oashya 0.0.7, MIT, installed normal model loader',sha256=hashlib.sha256(data).hexdigest(),
            inputs=[dict(name=item.name,shape=item.shape,type=item.type) for item in session.get_inputs()],
            outputs=[dict(name=item.name,shape=item.shape,type=item.type) for item in session.get_outputs()])
(destination/'hya-model.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
