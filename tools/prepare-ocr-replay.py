"""Generate synthetic, account-free OCR fixtures and compare with OAS's original recognizer."""
import argparse
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True

parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.source / "toolkit/Lib/site-packages"))
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from ppocronnx.rec.predict_rec import TextRecognizer

root = Path(__file__).resolve().parents[1]
destination = root / "app/src/debug/assets/ocr-replay"
destination.mkdir(parents=True, exist_ok=True)
recognizer = TextRecognizer()
fixtures = []
for index, text in enumerate(("12345", "1.2万", "633/180", "准备")):
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 28)
    bounds = font.getbbox(text)
    image = Image.new("RGB", (bounds[2] - bounds[0] + 12, 40), (38, 30, 23))
    ImageDraw.Draw(image).text((6 - bounds[0], (40 - bounds[3] + bounds[1]) // 2 - bounds[1]), text, font=font, fill=(248, 239, 217))
    prediction, elapsed = recognizer([np.array(image)])
    actual, confidence = prediction[0]
    if actual != text:
        raise AssertionError(f"Reference did not recognize synthetic fixture: {text} -> {prediction}")
    filename = f"line-{index}.png"
    image.save(destination / filename)
    fixtures.append(dict(file=filename, expected=text, confidence=float(confidence)))
    print(text, actual, confidence)
(destination / "expected.json").write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# Native detector upscales small text ROIs to short side 320 and bounds long side at
# 1920. Set the legacy wrapper's resize explicitly for this geometry comparison.
from ppocronnx.predict_system import TextSystem
system = TextSystem(use_angle_cls=False)
system.text_detector.preprocess_op[0].limit_type = "min"
system.text_detector.preprocess_op[0].limit_side_len = 320
detector_fixtures = []
for filename, dimensions, labels, font_size in (
    ("det-page.png", (720, 400), [("准备", 80, 40), ("12345", 80, 135), ("1.2万", 400, 230)], 32),
    ("det-list.png", (106, 603), [(text, 30, 20 + i*101) for i,text in enumerate(("拾", "悲", "神", "虚"))], 38),
):
    image = Image.new("RGB", dimensions, (38, 30, 23))
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", font_size)
    for text,x,y in labels:
        ImageDraw.Draw(image).text((x,y),text,font=font,fill=(248,239,217))
    boxes = system.detect_and_ocr(np.array(image))
    actual = {box.ocr_text for box in boxes}
    wanted = {text for text,_,_ in labels}
    if actual != wanted:
        raise AssertionError(f"Detection fixture mismatch {filename}: {actual} vs {wanted}")
    image.save(destination / filename)
    expected = [dict(text=box.ocr_text, confidence=float(box.score), corners=box.box.astype(int).tolist()) for box in boxes]
    detector_fixtures.append(dict(file=filename, boxes=expected))
    print(filename, sorted(actual))
(destination / "det-expected.json").write_text(json.dumps(detector_fixtures,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
