"""The ONNX Runtime Python interface backed by the app's real native Android runtime."""
import base64
import json
import weakref
import numpy as np
from java import jclass

__version__='1.20.0'

class SessionOptions:
    intra_op_num_threads=2
    inter_op_num_threads=1
    log_severity_level=3

class GraphOptimizationLevel:
    ORT_DISABLE_ALL=0
    ORT_ENABLE_BASIC=1
    ORT_ENABLE_EXTENDED=2
    ORT_ENABLE_ALL=99

class ExecutionMode:
    ORT_SEQUENTIAL=0
    ORT_PARALLEL=1

class Node:
    def __init__(self,data):self.__dict__.update(data)

def get_available_providers():return ['CPUExecutionProvider']
def get_device():return 'CPU'

class InferenceSession:
    def __init__(self,path_or_bytes,sess_options=None,providers=None,asset=False,**kwargs):
        bridge=jclass('com.oas.android.engine.PythonOnnxSession')
        if asset:self._session=bridge.openAsset(str(path_or_bytes))
        else:self._session=bridge(path_or_bytes if isinstance(path_or_bytes,(str,bytes,bytearray)) else str(path_or_bytes))
        self._info=json.loads(str(self._session.metadata()))
        self._finalizer=weakref.finalize(self,self._session.close)
    def get_inputs(self):return [Node(item) for item in self._info['inputs']]
    def get_outputs(self):return [Node(item) for item in self._info['outputs']]
    def get_providers(self):return ['CPUExecutionProvider']
    def run_yolo(self,input_name,value,threshold):
        array=np.ascontiguousarray(value,dtype=np.float32)
        result=self._session.runYolo(input_name,array.tobytes(),list(array.shape),float(threshold))
        return np.frombuffer(bytes(result),dtype=np.float32).reshape(-1,6)
    def run(self,output_names,input_feed,**kwargs):
        payload={}
        for name,value in input_feed.items():
            array=np.ascontiguousarray(value)
            payload[name]=dict(shape=list(array.shape),dtype=str(array.dtype),data=base64.b64encode(array.tobytes()).decode())
        result=json.loads(str(self._session.run(json.dumps(payload))))
        names=output_names or [item['name'] for item in self._info['outputs']]
        return [np.frombuffer(base64.b64decode(result[name]['data']),dtype=result[name]['dtype']).reshape(result[name]['shape']).copy() for name in names]
