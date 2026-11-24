"""Exercise the OASA OASX-layout UI on Android without running game tasks.

Uses Android UIAutomator semantics, not fixed screen coordinates. Saves local
screenshots for visual review. Only changes/restores the app's theme preference.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'captures/ui-oasx')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)


def adb(*argv):
    return subprocess.check_output(['adb', '-s', args.serial, *argv], stderr=subprocess.STDOUT)


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/oas-ui-check.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/oas-ui-check.xml'))


def find(label, timeout=20, prefix=False):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        root = tree()
        for node in root.iter('node'):
            text = node.get('text') or ''
            desc = node.get('content-desc') or ''
            matched = (text.startswith(label) or desc.startswith(label)) if prefix else (text == label or desc == label)
            if matched:
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    return node
        time.sleep(0.3)
    raise AssertionError('UI element missing: ' + label)


def click(label, prefix=False):
    node = find(label, prefix=prefix)
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.5)


def scroll_find(label, swipes=4):
    for attempt in range(swipes + 1):
        try:
            return find(label, timeout=1.5 if attempt < swipes else 5)
        except AssertionError:
            if attempt >= swipes:
                raise
            adb('shell', 'input', 'swipe', '360', '960', '360', '360', '300')
            time.sleep(0.4)


def scroll_click(label):
    node = scroll_find(label)
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.5)


def shot(name):
    (args.output / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def launch(orientation):
    adb('shell', 'am', 'force-stop', 'com.oas.android')
    adb('shell', 'am', 'start', '-n', 'com.oas.android/.MainActivity', '--es', 'uiTestOrientation', orientation)
    find('主页')
    time.sleep(1)


initial = 'system'
try:
    prefs = ET.fromstring(adb('shell', 'run-as', 'com.oas.android', 'cat', 'shared_prefs/MainActivity.xml'))
    initial = next((node.text for node in prefs if node.get('name') == 'appearance'), 'system')
except subprocess.CalledProcessError:
    pass

report = []
try:
    launch('portrait')
    find('OASA / 主页')
    find('脚本列表', prefix=True)
    find('android')
    find('新增脚本预设')
    shot('scripts-portrait')
    click('新增脚本预设')
    find('新增配置')
    find('从已有配置复制')
    find('选择配置文件 (.json)')
    shot('add-preset-dialog')
    click('取消')
    report.append('OASX single-pane script collection page and add/import preset dialog')

    click('android')
    for label in ('总览', '任务', '日志', '画面', '全部立即运行', '全部置为等待'):
        find(label)
    shot('home-portrait')
    report.append('OASX active config header, wrapped tabs and overview')

    click('任务')
    find('搜索任务')
    find('日常任务')
    shot('tasks-portrait')

    click('搜索任务')
    adb('shell', 'input', 'text', 'Draft')
    time.sleep(0.5)
    find('协同对弈')
    shot('search')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    click('清除搜索')

    click('搜索任务')
    adb('shell', 'input', 'text', 'Orochi')
    time.sleep(0.5)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    click('八岐大蛇')
    find('运行此任务')
    find('导入任务 JSON')
    find('导出任务 JSON')
    find('复制任务 JSON')
    find('任务调度')
    find('下次执行时间')
    shot('editor')
    click('返回')

    click('总览')
    shot('plan')
    click('日志')
    find('信息')
    find('错误')
    shot('logs')
    click('画面')
    find('手动控制')
    shot('preview')

    click('设置')
    find('设备与环境')
    find('OAS 设置')
    scroll_click('深色')
    shot('settings-dark')
    click('主页')
    shot('home-dark')
    report.append('task search, catalog, parameters, overview quick-schedule, logs, preview and dark theme')

    click('设置')
    scroll_click('浅色')
    scroll_click('设备与游戏')
    find('设备与游戏')
    find('后台虚拟屏')
    find('前台兼容')
    shot('device-sheet')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')

    launch('landscape')
    find('脚本列表', prefix=True)
    find('总览')
    shot('home-landscape')
    click('任务')
    find('搜索任务')
    find('脚本')
    find('御魂副本')
    shot('tasks-landscape')
    report.append('responsive OASX rail + two-pane script list and workspace in landscape')
finally:
    try:
        launch('portrait')
        click('设置')
        scroll_click({'dark': '深色', 'light': '浅色'}.get(initial, '跟随系统'))
        adb('shell', 'am', 'force-stop', 'com.oas.android')
        adb('shell', 'am', 'start', '-n', 'com.oas.android/.MainActivity')
    finally:
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(status='passed', checks=report, screenshots=str(args.output)), ensure_ascii=False))
