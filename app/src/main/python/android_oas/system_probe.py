"""Explicit debug-only caller exercises real raw transport; never navigates or touches a game."""
import json
import time
import numpy as np

def run():
    from android_oas import bootstrap as engine
    from module.config.config import Config
    from module.device.device import Device
    previous=engine._config
    try:
        engine._config=Config('android-system-audit')
        device=Device(engine._config)
        durations=[]
        for _ in range(6):
            start=time.monotonic();image=device.screenshot();durations.append(time.monotonic()-start)
            assert image.shape==(720,1280,3),image.shape
            assert image.dtype==np.uint8
            assert image[50,1190,0]>200 and image[675,1190,1]>200
            assert image[675,1190,0]<40,'BGR/RGB swapped'
            assert device.image_frame_id,'Raw frame did not reach native image RPC'
        return json.dumps(dict(status='passed',frames=len(durations),shape=list(image.shape),rgb=True,pngFallback=False,registered=True))
    finally:engine._config=previous
