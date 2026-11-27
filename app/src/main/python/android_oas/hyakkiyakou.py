"""Use the Android device bridge for the original Hyakkiyakou task."""
from functools import cached_property


DESKTOP_FIELDS = {
    'debug_config': {'hya_screenshot_method', 'hya_control_method', 'hya_show'},
    'hyakkiyakou_models': {'model_precision', 'inference_engine'},
}


def adapt_task(entry):
    from module.exception import GameStuckError
    from module.logger import logger
    from tasks.Hyakkiyakou.config import InferenceEngine, ModelPrecision
    from oashya.labels import MODEL_BOSS_SCORES, id2name
    from oashya.tracker import Tracker as AndroidTracker

    class AndroidHyakkiyakou(entry):
        @cached_property
        def _config(self):
            options = self.config.hyakkiyakou.model_copy(deep=True)
            options.debug_config.hya_show = False
            options.hyakkiyakou_models.inference_engine = InferenceEngine.ONNXRUNTIME
            options.hyakkiyakou_models.model_precision = ModelPrecision.FP32
            return options

        @cached_property
        def tracker(self):
            # Always use the Android mmap + native runYolo tracker instead of desktop ORT .run().
            conf = self._config.hyakkiyakou_models.conf_threshold
            nms = self._config.hyakkiyakou_models.iou_threshold
            return AndroidTracker(args={'conf_threshold': conf, 'iou_threshold': nms})

        def run(self):
            # Check the model and screenshot interval before entering a ticketed round.
            self.tracker.prepare()
            self.debugger
            return super().run()

        def _rarity_score(self, class_id):
            score = MODEL_BOSS_SCORES.get(class_id)
            return super()._rarity_score(class_id) if score is None else score

        def _detect_current_rarity(self):
            self.screenshot()
            detections = self.tracker.detect(self.device.image, include_initial=True)
            best = max(((self._rarity_score(label), label) for label, _, _ in detections),
                       key=lambda match: match[0], default=(-1, -1))
            if best[0] >= 0:
                logger.info(f'Hyakki select: detect {id2name(best[1])} with rarity score {best[0]}')
                return best
            return -1, -1

        def fast_screenshot(self, screenshot):
            if self.hya_fs_check_timer.reached():
                raise GameStuckError('百鬼夜行对局超时')
            # A round can exceed the general 60-second navigation watchdog.
            self.device.stuck_timer.reset()
            return self.device.screenshot(min_interval=self._config.debug_config.hya_interval / 1000)

        def fast_click(self, x, y, control_method=None):
            # Predicted targets can leave the screen before the next captured frame.
            x, y = int(x), int(y)
            if 0 <= x < 1280 and 0 <= y < 720:
                self.device.click(x, y, control_check=False)

    return AndroidHyakkiyakou
