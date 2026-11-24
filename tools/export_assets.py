"""Export a small, auditable subset of existing OAS RuleImage assets without importing OAS."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/oas"
SOURCES = {
    "tasks/GameUi/assets.py": {
        "I_CHECK_MAIN": "庭院", "I_CHECK_EXPLORATION": "探索", "I_CHECK_SOUL_ZONES": "御魂选择",
        "I_ACT_LIST_EXPAND": "庭院活动入口",
        "I_CHECK_ACT_LIST": "活动列表",
        "I_MAIN_SCROLL_CLOSE": "庭院卷轴", "I_MAIN_GOTO_MALL": "进入商城", "I_CHECK_MALL": "商城",
        "I_MAIN_GOTO_EXPLORATION": "进入探索", "I_CHECK_AWAKE": "觉醒入口页", "I_CHECK_GORYOU": "御灵入口页",
        "I_EXPLORATION_GOTO_AWAKE_ZONE": "进入觉醒", "I_EXPLORATION_GOTO_SOUL_ZONE": "进入御魂",
        "I_EXPLORATION_GOTO_GORYOU_REALM": "进入御灵",
        "I_CHECK_MALL_RECOMMEND": "商城推荐", "I_AD_CLOSE_RED": "关闭广告", "I_PAPER_DOLL_CLOSE": "关闭纸人",
    },
    "tasks/Component/GeneralBattle/assets.py": {
        "I_WIN": "战斗胜利", "I_FALSE": "战斗失败", "I_REWARD": "战斗奖励",
        "I_DE_WIN": "逢魔胜利", "I_REWARD_GOLD": "金币奖励", "I_BATTLE_INFO": "战斗中",
        "I_PREPARE_HIGHLIGHT": "战斗准备", "I_PREPARE_DARK": "准备未加载", "I_GB_SKIN_CONFIRM": "皮肤碎片确认",
        "I_OVER_GHOST": "御魂溢出", "C_RANDOM_BOTTOM": "结算点击区域", "O_BATTLE_PREPARE": "准备文字",
    },
    "tasks/GlobalGame/assets.py": {"I_UI_BACK_YELLOW": "黄色返回", "I_UI_BACK_RED": "关闭窗口",
        "I_UI_BACK_BLUE": "蓝色返回", "I_UI_CANCEL_SAMLL": "取消弹窗", "I_UI_REWARD": "获得奖励"},
    "tasks/DailyTrifles/assets.py": {
        "I_HARVEST_MAIL": "纸人邮件", "I_HARVEST_MAIL_COPY": "纸人邮件备用",
        "I_DT_HARVEST_MAIL_COPY2": "庭院邮件入口", "I_HARVEST_MAIL_ALL": "全部收取邮件",
        "I_READ_ALL_MAIL": "一键已读", "I_HARVEST_MAIL_CONFIRM": "邮件确认",
        "I_ENTER_COURTYARD_AFFAIRS": "进入庭院事务", "I_CHECK_COURTYARD_AFFAIRS": "庭院事务",
        "I_ONE_COMPLETE": "一键完成", "I_ENTER_DAILY": "日常页签", "I_CHECK_IN_DAILY": "日常页签已选择",
        "I_ROOM_GIFT": "礼包屋入口", "I_GIFT_RECOMMEND": "礼包推荐", "I_GIFT_SIGN": "免费签到",
        "O_INCOME_GOLD": "金币", "O_INCOME_JADE": "勾玉", "O_INCOME_AP": "体力",
    },
    "tasks/Restart/assets.py": {"I_HARVEST_MAIL_TITLE": "邮件页", "I_LOGIN_8": "游戏登录页",
        "I_LOGIN_SCROOLL_OPEN": "卷轴开启标志", "I_LOGIN_SCROOLL_CLOSE": "卷轴关闭标志",
        "C_LOGIN_SCROLL_CLOSE_AREA": "庭院卷轴点击区域"},
    "tasks/Component/SwitchAccount/assets.py": {"C_SA_LOGIN_FORM_ENTER_GAME_BTN": "进入当前游戏"},
    "tasks/Orochi/assets.py": {"I_OROCHI": "进入八岐大蛇", "I_OROCHI_FIRE": "御魂挑战",
        "I_OROCHI_CHECK_10": "八岐大蛇", "I_OROCHI_CHECK_11": "巫女大蛇", "I_OROCHI_CHECK_12": "神罚",
        "I_OROCHI_CHECK_13": "虚无大蛇", "I_OROCHI_LOCK": "御魂阵容已锁定", "I_OROCHI_UNLOCK": "御魂阵容未锁定",
        "L_LAYER_LIST": "御魂层数"},
    "tasks/EvoZone/assets.py": {"I_FIRE_KIRIN": "火麒麟", "I_WIND_KIRIN": "风麒麟", "I_WATER_KIRIN": "水麒麟",
        "I_LIGHTNING_KIRIN": "雷麒麟", "I_EVOZONE_FIRE": "觉醒挑战", "I_EVOZONE_LOCK": "觉醒阵容已锁定",
        "I_EVOZONE_UNLOCK": "觉醒阵容未锁定", "L_LAYER_LIST": "觉醒层数"},
    "tasks/GoryouRealm/assets.py": {"I_GR_FIRE": "御灵挑战", "I_GR_LOCK": "御灵阵容已锁定",
        "I_GR_UNLOCK": "御灵阵容未锁定", "C_GR_C_1": "暗神龙", "C_GR_C_2": "暗白藏主",
        "C_GR_C_3": "暗黑豹", "C_GR_C_4": "暗孔雀", "O_GR_TICKET": "御灵票数"},
    "tasks/Sougenbi/assets.py": {"I_S_SOUGENBI": "进入业原火", "I_S_TEAM_LOCK": "业原火阵容已锁定",
        "I_S_TEAM_UNLOCK": "业原火阵容未锁定", "I_S_FIRE": "业原火挑战", "I_S_CHECK_SOUGENBI": "业原火",
        "I_S_FIRE_GREED": "贪挑战票", "I_S_FIRE_ANGER": "嗔挑战票", "I_S_FIRE_FOOLERY": "痴挑战票",
        "C_C_GREED": "选择贪", "C_C_ANGER": "选择嗔", "C_C_FOOLERY": "选择痴",
        "O_S_GREED": "贪票数", "O_S_ANGER": "嗔票数", "O_S_FOOLERY": "痴票数"},
}


def export(repo: Path, check: bool = False) -> None:
    rules, ocr, clicks, lists = [], [], [], []
    for source, labels in SOURCES.items():
        parsed = ast.parse((repo / source).read_text(encoding="utf-8"))
        found = set()
        for node in ast.walk(parsed):
            if not isinstance(node, ast.Assign) or not isinstance(node.value, ast.Call):
                continue
            name = node.targets[0].id if isinstance(node.targets[0], ast.Name) else ""
            if name not in labels:
                continue
            arguments = {kw.arg: ast.literal_eval(kw.value) for kw in node.value.keywords}
            kind = getattr(node.value.func, "id", "")
            if kind == "RuleList":
                lists.append(dict(id=source.split('/')[1] + '.' + name, label=labels[name], roi=arguments['roi_back'],
                                  size=arguments['size'], direction=arguments['direction'], mode=arguments['mode'],
                                  items=arguments['array'], source=source))
                found.add(name)
                continue
            if kind == "RuleOcr":
                ocr.append(dict(id=name, label=labels[name], roi=arguments["roi"], mode=arguments["mode"],
                                keyword=arguments["keyword"], source=source))
                found.add(name)
                continue
            if kind == "RuleClick":
                clicks.append(dict(id=name, label=labels[name], roi=arguments["roi_front"], source=source))
                found.add(name)
                continue
            if kind != "RuleImage":
                raise ValueError(f"Unsupported asset type: {name} / {kind}")
            if arguments["method"] != "Template matching":
                raise ValueError(f"Unsupported recognition method: {name}")
            asset = (repo / arguments["file"]).resolve()
            asset.relative_to(repo)
            filename = name.lower() + ".png"
            digest = hashlib.sha256(asset.read_bytes()).hexdigest()
            if check:
                assert (DEST / filename).read_bytes() == asset.read_bytes(), f"Asset drift: {name}"
            else:
                DEST.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(asset, DEST / filename)
            rules.append(dict(id=name, label=labels[name], file=filename, roi=arguments["roi_back"],
                              reference_roi=arguments["roi_front"],
                              threshold=arguments["threshold"], source=source,
                              source_image=asset.relative_to(repo).as_posix(), sha256=digest))
            found.add(name)
        if found != labels.keys():
            raise ValueError(f"Missing source assets: {labels.keys() - found}")
    models = []
    model_dir = repo / "toolkit/Lib/site-packages/ppocronnx/model"
    for filename in ("ch_PP-OCRv2_rec_infer.onnx", "ch_PP-OCRv3_det_infer.onnx", "ppocr_keys_v1.txt"):
        asset = model_dir / filename
        target = DEST / "models" / filename
        digest = hashlib.sha256(asset.read_bytes()).hexdigest()
        if check:
            assert target.read_bytes() == asset.read_bytes(), f"Model drift: {filename}"
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(asset, target)
        models.append(dict(file="models/" + filename, sha256=digest, source="ppocr-onnx 0.0.3.9 / PaddleOCR"))
    catalog = json.dumps(dict(width=1280, height=720, method="TM_CCOEFF_NORMED_RGB", rules=rules,
                             ocr=ocr, clicks=clicks, lists=lists, models=models),
                         ensure_ascii=False, indent=2) + "\n"
    if check:
        assert (DEST / "catalog.json").read_text(encoding="utf-8") == catalog, "Catalog drift"
    else:
        (DEST / "catalog.json").write_text(catalog, encoding="utf-8")
    print(f"{'Verified' if check else 'Exported'} {len(rules)} image / {len(ocr)} OCR / {len(clicks)} click rules and OCR model")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path, help="Read-only path to the OAS source checkout")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    export(args.source.resolve(), args.check)
