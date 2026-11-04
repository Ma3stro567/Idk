"""Работа с окнами Windows через ctypes: список окон, снимок конкретного окна (даже закрытого другими окнами),
клики по окну без перемещения настоящего курсора. На других системах функции недоступны."""
import ctypes
import random
import sys
import time

import numpy as np

AVAILABLE = sys.platform == "win32"

if AVAILABLE:
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32

    user32.GetWindowDC.argtypes = [wintypes.HWND]
    user32.GetWindowDC.restype = wintypes.HDC
    user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
    user32.PrintWindow.argtypes = [wintypes.HWND, wintypes.HDC, wintypes.UINT]
    user32.PrintWindow.restype = wintypes.BOOL
    user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    user32.ClientToScreen.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.POINT)]
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.IsIconic.argtypes = [wintypes.HWND]
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.SetForegroundWindow.argtypes = [wintypes.HWND]
    user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    gdi32.CreateCompatibleDC.argtypes = [wintypes.HDC]
    gdi32.CreateCompatibleDC.restype = wintypes.HDC
    gdi32.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
    gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
    gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
    gdi32.SelectObject.restype = wintypes.HGDIOBJ
    gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
    gdi32.DeleteDC.argtypes = [wintypes.HDC]
    gdi32.GetDIBits.argtypes = [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT,
                                ctypes.c_void_p, ctypes.c_void_p, wintypes.UINT]
    gdi32.GetDIBits.restype = ctypes.c_int

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
                    ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                    ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                    ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD),
                    ("biClrImportant", wintypes.DWORD)]

    WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.EnumWindows.argtypes = [WNDENUMPROC, wintypes.LPARAM]
    user32.EnumChildWindows.argtypes = [wintypes.HWND, WNDENUMPROC, wintypes.LPARAM]


def _text(hwnd):
    buf = ctypes.create_unicode_buffer(512)
    user32.GetWindowTextW(hwnd, buf, 512)
    return buf.value


def _cls(hwnd):
    buf = ctypes.create_unicode_buffer(256)
    user32.GetClassNameW(hwnd, buf, 256)
    return buf.value


def list_windows(skip_titles=("Chess Bot",)):
    """[(hwnd, заголовок)] видимых окон с заголовком."""
    if not AVAILABLE:
        return []
    out = []

    def cb(hwnd, _):
        if user32.IsWindowVisible(hwnd):
            t = _text(hwnd)
            if t and t not in skip_titles:
                r = wintypes.RECT()
                user32.GetWindowRect(hwnd, ctypes.byref(r))
                if r.right - r.left > 200 and r.bottom - r.top > 200:
                    out.append((hwnd, t))
        return True

    user32.EnumWindows(WNDENUMPROC(cb), 0)
    return out


def find_window(title):
    """Окно по сохранённому заголовку: точное совпадение, иначе по «хвосту» (у браузеров название вкладки меняется:
    «… - Google Chrome»)."""
    wins = list_windows()
    for h, t in wins:
        if t == title:
            return h
    suffix = title.rsplit(" - ", 1)[-1]
    for h, t in wins:
        if t.endswith(suffix):
            return h
    return None


def is_iconic(hwnd):
    return bool(user32.IsIconic(hwnd))


def window_origin(hwnd):
    """Левый верхний угол окна на экране (физические пиксели)."""
    r = wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    return r.left, r.top


def capture_window(hwnd):
    """Снимок окна целиком (RGB numpy) через PrintWindow — работает, даже если окно закрыто другими.
    Координаты в снимке отсчитываются от левого верхнего угла окна."""
    r = wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    w, h = r.right - r.left, r.bottom - r.top
    if w <= 0 or h <= 0:
        raise RuntimeError("окно имеет нулевой размер (свёрнуто?)")
    hdc = user32.GetWindowDC(hwnd)
    mdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    old = gdi32.SelectObject(mdc, bmp)
    try:
        user32.PrintWindow(hwnd, mdc, 2)  # PW_RENDERFULLCONTENT — для Chrome/Edge
        bi = BITMAPINFOHEADER()
        bi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        bi.biWidth, bi.biHeight = w, -h  # top-down
        bi.biPlanes, bi.biBitCount, bi.biCompression = 1, 32, 0
        buf = ctypes.create_string_buffer(w * h * 4)
        gdi32.GetDIBits(mdc, bmp, 0, h, buf, ctypes.byref(bi), 0)
    finally:
        gdi32.SelectObject(mdc, old)
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(mdc)
        user32.ReleaseDC(hwnd, hdc)
    arr = np.frombuffer(buf, np.uint8).reshape(h, w, 4)
    return arr[:, :, :3][:, :, ::-1].copy()


def _render_child(hwnd):
    found = []

    def cb(ch, _):
        if _cls(ch) == "Chrome_RenderWidgetHostHWND":
            found.append(ch)
            return False
        return True

    user32.EnumChildWindows(hwnd, WNDENUMPROC(cb), 0)
    return found[0] if found else hwnd


def post_click(hwnd, abs_x, abs_y):
    """Клик по точке экрана (abs_x, abs_y) внутри окна hwnd БЕЗ перемещения настоящего курсора."""
    target = _render_child(hwnd)
    pt = wintypes.POINT(0, 0)
    user32.ClientToScreen(target, ctypes.byref(pt))
    cx, cy = int(abs_x - pt.x), int(abs_y - pt.y)
    lp = ((cy & 0xFFFF) << 16) | (cx & 0xFFFF)
    WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP = 0x200, 0x201, 0x202
    user32.PostMessageW(target, WM_MOUSEMOVE, 0, lp)
    time.sleep(random.uniform(0.03, 0.09))
    user32.PostMessageW(target, WM_LBUTTONDOWN, 1, lp)
    time.sleep(random.uniform(0.04, 0.12))
    user32.PostMessageW(target, WM_LBUTTONUP, 0, lp)


def bring_to_front(hwnd):
    if is_iconic(hwnd):
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    user32.SetForegroundWindow(hwnd)
