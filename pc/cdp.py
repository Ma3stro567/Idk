"""Управление вкладкой Chrome/Edge через DevTools Protocol: снимок вкладки и клики БЕЗ участия настоящей мыши.
Работает, даже если окно закрыто другими окнами или свёрнуто. Браузер нужно запускать с
--remote-debugging-port (кнопка «Запустить Chrome для бота» делает это сама, с отдельным профилем)."""
import base64
import io
import json
import os
import random
import subprocess
import sys
import time
import urllib.request

import numpy as np

PORT = 9222


def browser_path():
    cands = []
    for env in ("ProgramFiles", "ProgramFiles(x86)", "LocalAppData"):
        base = os.environ.get(env)
        if base:
            cands.append(os.path.join(base, "Google", "Chrome", "Application", "chrome.exe"))
            cands.append(os.path.join(base, "Microsoft", "Edge", "Application", "msedge.exe"))
    for c in cands:
        if os.path.exists(c):
            return c
    return None


def launch_browser(profile_dir, url="https://www.chess.com/play/computer"):
    """Запускает Chrome (или Edge) с отладочным портом и отдельным профилем (войти в аккаунт нужно один раз)."""
    path = browser_path()
    if not path:
        raise RuntimeError("Chrome/Edge не найден в стандартных папках")
    os.makedirs(profile_dir, exist_ok=True)
    subprocess.Popen([path, f"--remote-debugging-port={PORT}", "--remote-allow-origins=*",
                      f"--user-data-dir={profile_dir}", "--no-first-run", "--no-default-browser-check", url])


def available(port=PORT):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=1.5).read()
        return True
    except Exception:
        return False


class CDP:
    def __init__(self, port=PORT):
        self.port = port
        self.ws = None
        self._id = 0
        self.scale = 1.0
        self.last = None

    def tabs(self):
        data = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{self.port}/json", timeout=3).read())
        return [t for t in data if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]

    def connect(self, prefer=("chess", "lichess")):
        import websocket
        tabs = self.tabs()
        if not tabs:
            raise RuntimeError("в Chrome нет открытых вкладок")
        tab = next((t for t in tabs if any(p in t.get("url", "") for p in prefer)), tabs[0])
        self.title = tab.get("title", "")
        self.ws = websocket.create_connection(tab["webSocketDebuggerUrl"], timeout=15, suppress_origin=True)
        return self.title

    def close(self):
        try:
            if self.ws:
                self.ws.close()
        except Exception:
            pass
        self.ws = None

    def call(self, method, params=None):
        self._id += 1
        mid = self._id
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error'].get('message')}")
                return msg.get("result", {})

    def screenshot(self):
        """RGB numpy-снимок видимой части вкладки; запоминает масштаб «пиксель снимка → CSS-пиксель»."""
        from PIL import Image
        res = self.call("Page.captureScreenshot", {"format": "png", "fromSurface": True})
        img = Image.open(io.BytesIO(base64.b64decode(res["data"]))).convert("RGB")
        m = self.call("Page.getLayoutMetrics")
        vp = m.get("cssVisualViewport") or m.get("visualViewport") or {}
        css_w = vp.get("clientWidth") or vp.get("width")
        self.scale = img.width / css_w if css_w else 1.0
        self.last = (0.0, 0.0)
        return np.array(img)

    def click(self, x_img, y_img):
        """Клик в точке снимка (x_img, y_img): плавный подход курсора, нажатие и отпускание."""
        x, y = x_img / self.scale, y_img / self.scale
        sx, sy = self.last if self.last else (x, y)
        steps = random.randint(5, 9)
        for i in range(1, steps + 1):
            t = i / steps
            t = t * t * (3 - 2 * t)  # плавное ускорение/замедление
            self.call("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": sx + (x - sx) * t,
                                                   "y": sy + (y - sy) * t, "buttons": 0})
            time.sleep(random.uniform(0.01, 0.03))
        self.call("Input.dispatchMouseEvent", {"type": "mousePressed", "x": x, "y": y, "button": "left",
                                               "buttons": 1, "clickCount": 1})
        time.sleep(random.uniform(0.05, 0.13))
        self.call("Input.dispatchMouseEvent", {"type": "mouseReleased", "x": x, "y": y, "button": "left",
                                               "buttons": 0, "clickCount": 1})
        self.last = (x, y)
