"""Зрение бота: поиск шахматной доски на скриншоте и чтение позиции.

Те же алгоритмы, что в Android-версии (проверены на реальных скриншотах chess.com).
Изображение — numpy-массив RGB (H, W, 3), uint8/int.
Клетка (row, col) — экранные координаты. Индекс клетки в позиции: rank*8 + file (a1 = 0).
"""
import os
import sys

import numpy as np

N = 20
BG_THR = 14
STEP = 4

# известные цвета досок (светлая, тёмная)
PRESETS = [
    ((238, 238, 210), (118, 150, 86)),   # chess.com зелёная
    ((240, 217, 181), (181, 136, 99)),   # lichess коричневая
    ((234, 233, 210), (119, 154, 88)),   # lichess зелёная
]
YELLOW = (255, 255, 51)      # подсветка хода chess.com (50%)
LICHESS_HL = (155, 199, 0)   # подсветка хода lichess (41%)


def _res(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def _d(a, b):
    return int(np.abs(np.asarray(a, int) - np.asarray(b, int)).max())


def _mix(base, over, alpha):
    return (1 - alpha) * np.asarray(base, float) + alpha * np.asarray(over, float)


def _tints(base):
    return [np.asarray(base, float), _mix(base, YELLOW, 0.5), _mix(base, LICHESS_HL, 0.41)]


def _longest_run(cnt, frac):
    mx = cnt.max()
    if mx < 20:
        return None
    thr = mx * frac
    best_s = best_e = -1
    s = -1
    gap = 0
    for i in range(len(cnt) + 1):
        ok = i < len(cnt) and cnt[i] >= thr
        if ok:
            if s < 0:
                s = i
            gap = 0
            if i - s > best_e - best_s:
                best_s, best_e = s, i
        elif s >= 0:
            gap += 1
            if gap > 1 or i >= len(cnt):
                s = -1
                gap = 0
    return None if best_s < 0 else (best_s, best_e)


# ---------------------------------------------------------------- поиск доски

class Finder:
    def __init__(self):
        self.tol_mask = 10
        self.tol_match = 24
        self.last_info = ""

    def _near(self, px, tints):
        ok = np.zeros(len(px), bool)
        for t in tints:
            ok |= np.abs(px - t).max(axis=1) <= self.tol_match
        return ok

    def sharp(self, img, rect, a, b):
        """Углы клеток (3% от края) должны чередоваться светлый/тёмный; подсвеченная клетка засчитывается как своя."""
        l, t, r, bt = rect
        cw = (r - l) / 8.0
        ch = (bt - t) / 8.0
        H, W, _ = img.shape
        offs = (0.03, 0.97)
        xs, ys, par = [], [], []
        for row in range(8):
            for col in range(8):
                for ox in offs:
                    for oy in offs:
                        xs.append(int(l + (col + ox) * cw))
                        ys.append(int(t + (row + oy) * ch))
                        par.append((row + col) % 2 == 0)
        xs = np.clip(np.array(xs), 0, W - 1)
        ys = np.clip(np.array(ys), 0, H - 1)
        par = np.array(par)
        px = img[ys, xs].astype(float)
        na = self._near(px, _tints(a))
        nb = self._near(px, _tints(b))
        ok_a = int(((par & na) | (~par & nb)).sum())
        ok_b = int(((par & nb) | (~par & na)).sum())
        return max(ok_a, ok_b) / len(par)

    def refine(self, img, rect, a, b):
        l, t, r, bt = rect

        def edge(cur, f):
            best = -1.0
            hits = []
            for dl in range(-56, 57, 2):
                sc = f(cur + dl)
                if sc > best + 1e-9:
                    best, hits = sc, [cur + dl]
                elif abs(sc - best) <= 1e-9:
                    hits.append(cur + dl)
            return int(sum(hits) / len(hits))

        for _ in range(2):
            l = edge(l, lambda v: 0 if r - v < 100 else self.sharp(img, (v, t, r, bt), a, b))
            t = edge(t, lambda v: 0 if bt - v < 100 else self.sharp(img, (l, v, r, bt), a, b))
            r = edge(r, lambda v: 0 if v - l < 100 else self.sharp(img, (l, t, v, bt), a, b))
            bt = edge(bt, lambda v: 0 if v - t < 100 else self.sharp(img, (l, t, r, v), a, b))
        size = ((r - l) + (bt - t)) // 2
        return (l, t, l + size, t + size)

    def _candidate(self, img, sm, a, b):
        m = (np.abs(sm - np.asarray(a)).max(axis=2) <= self.tol_mask) | \
            (np.abs(sm - np.asarray(b)).max(axis=2) <= self.tol_mask)
        col_c = m.sum(axis=0)
        rx = _longest_run(col_c, 0.3)
        if not rx:
            return None
        left = rx[0] * STEP
        size = (rx[1] + 1 - rx[0]) * STEP
        H, W, _ = img.shape
        if size < 160 or size > min(W, H) + 8:
            return None
        best, best_t = -1.0, 0
        for t in range(0, max(H - size, 0) + 1, 4):
            sc = self.sharp(img, (left, t, left + size, t + size), a, b)
            if sc > best:
                best, best_t = sc, t
        return (left, best_t, left + size, best_t + size)

    def find(self, img, relaxed=False):
        self.tol_mask = 26 if relaxed else 10
        self.tol_match = 44 if relaxed else 24
        img = np.asarray(img)
        sm = img[::STEP, ::STEP].astype(int)
        h, w, _ = sm.shape
        keys = ((sm[..., 0] >> 3) << 10) | ((sm[..., 1] >> 3) << 5) | (sm[..., 2] >> 3)
        uniq, first, counts = np.unique(keys.ravel(), return_index=True, return_counts=True)
        order = np.argsort(-counts)
        flat = sm.reshape(-1, 3)
        cands = []
        for i in order:
            if counts[i] <= w * h * 0.004 or len(cands) >= 14:
                break
            col = flat[first[i]]
            if all(_d(c[1], col) >= 10 for c in cands):
                cands.append((int(counts[i]), col))
        pairs = [(np.array(a), np.array(b)) for a, b in PRESETS]
        for i in range(len(cands)):
            for j in range(i + 1, len(cands)):
                (ci, a), (cj, b) = cands[i], cands[j]
                if _d(a, b) < 15 or max(ci, cj) > 2.5 * min(ci, cj):
                    continue
                pairs.append((a, b))
        best, best_score = None, 0.0
        for a, b in pairs:
            r0 = self._candidate(img, sm, a, b)
            if not r0:
                continue
            r = self.refine(img, r0, a, b)
            sc = self.sharp(img, r, a, b)
            if sc > best_score:
                best, best_score = r, sc
        hexes = " ".join("#%02X%02X%02X" % tuple(int(v) for v in c[1]) for c in cands[:4])
        self.last_info = f"цвета экрана: {hexes}; лучшая оценка доски: {best_score:.2f}"
        return best if best_score >= (0.5 if relaxed else 0.6) else None

    def fallback(self, img, start):
        """Доска «примерно здесь»: подгоняем края по шахматному узору."""
        self.tol_match = 24
        img = np.asarray(img)
        l, t, r, b = start
        cw = (r - l) / 8.0
        H, W, _ = img.shape

        def px(col):
            x = min(max(int(l + (col + 0.5) * cw), 0), W - 1)
            y = min(max(int(t + 0.1 * cw), 0), H - 1)
            return img[y, x].astype(int)

        pairs = [(np.array(a), np.array(c)) for a, c in PRESETS] + [(px(0), px(1))]
        best, best_score = None, 0.0
        for a, c in pairs:
            if _d(a, c) < 15:
                continue
            rr = self.refine(img, start, a, c)
            sc = self.sharp(img, rr, a, c)
            if sc > best_score:
                best, best_score = rr, sc
        return best if best_score >= 0.45 else None


# ---------------------------------------------------------------- чтение фигур

class Reader:
    PIECES = "PNBRQKpnbrqk"

    def __init__(self):
        self.templates = {}
        self.use = np.array([not ((k % N >= 17 and k // N >= 17) or (k % N <= 2 and k // N <= 2))
                             for k in range(N * N)])

    @property
    def ready(self):
        return len(self.templates) == 12

    def load_builtin(self):
        try:
            z = np.load(_res("templates_chesscom.npz"))
            self.templates = {str(c): (z["px"][i].astype(int), z["isbg"][i]) for i, c in enumerate(z["chars"])}
            return self.ready
        except Exception:
            return False

    def save(self, path):
        np.savez_compressed(path, chars=np.array(list(self.templates)),
                            px=np.array([v[0] for v in self.templates.values()]),
                            isbg=np.array([v[1] for v in self.templates.values()]))

    def load(self, path):
        try:
            z = np.load(path)
            self.templates = {str(c): (z["px"][i].astype(int), z["isbg"][i]) for i, c in enumerate(z["chars"])}
            return self.ready
        except Exception:
            return False

    @staticmethod
    def _patch(img, rect, row, col):
        l, t, r, b = rect
        cw = (r - l) / 8.0
        ch = (b - t) / 8.0
        H, W, _ = img.shape
        x0 = l + col * cw
        y0 = t + row * ch
        idx = 0.10 + 0.80 * (np.arange(N) + 0.5) / N
        xs = np.clip((x0 + cw * idx).astype(int), 0, W - 1)
        ys = np.clip((y0 + ch * idx).astype(int), 0, H - 1)
        px = img[ys[:, None], xs[None, :]].reshape(-1, 3).astype(int)
        fs = np.array([0.07, 0.09, 0.11, 0.89, 0.91, 0.93])
        cx = np.clip((x0 + cw * fs).astype(int), 0, W - 1)
        cy = np.clip((y0 + ch * fs).astype(int), 0, H - 1)
        corners = img[cy[:, None], cx[None, :]].reshape(-1, 3).astype(int)
        bg = np.median(corners, axis=0).astype(int)
        return px, bg

    @staticmethod
    def _rowcol(file, rank, white):
        return (7 - rank, file) if white else (rank, 7 - file)

    def learn(self, img, rect, white, which=0):
        img = np.asarray(img)
        if which == 0:
            reps = dict(P=(3, 1), N=(1, 0), B=(2, 0), R=(0, 0), Q=(3, 0), K=(4, 0),
                        p=(3, 6), n=(1, 7), b=(2, 7), r=(0, 7), q=(3, 7), k=(4, 7))
        else:
            reps = dict(P=(5, 1), N=(6, 0), B=(5, 0), R=(7, 0), Q=(3, 0), K=(4, 0),
                        p=(5, 6), n=(6, 7), b=(5, 7), r=(7, 7), q=(3, 7), k=(4, 7))
        self.templates = {}
        for c, (f, rk) in reps.items():
            row, col = self._rowcol(f, rk, white)
            px, bg = self._patch(img, rect, row, col)
            self.templates[c] = (px, np.abs(px - bg).max(axis=1) <= BG_THR)

    def _classify(self, img, rect, row, col):
        px, bg = self._patch(img, rect, row, col)
        use = self.use
        cnt = use.sum()
        best_d = np.abs(px - bg).max(axis=1)[use].sum() / cnt
        best = " "
        for c, (tp, ib) in self.templates.items():
            ref = np.where(ib[:, None], bg[None, :], tp)
            d = np.abs(px - ref).max(axis=1)[use].sum() / cnt
            if d < best_d:
                best_d, best = d, c
        return "?" if best_d > 70 else best

    def read(self, img, rect, white):
        """64 символов FEN: ' ' пусто, '?' не распознано."""
        img = np.asarray(img)
        res = [" "] * 64
        for rank in range(8):
            for file in range(8):
                row, col = self._rowcol(file, rank, white)
                res[rank * 8 + file] = self._classify(img, rect, row, col)
        return res

    @staticmethod
    def white_at_bottom(obs):
        """Белые внизу? Сравниваем среднюю высоту белых и чёрных фигур (при раскладке «белые снизу»)."""
        ws = bs = 0.0
        wn = bn = 0
        for i, c in enumerate(obs):
            if c in " ?":
                continue
            row = 7 - i // 8
            if c.isupper():
                ws += row
                wn += 1
            else:
                bs += row
                bn += 1
        if not wn or not bn:
            return None
        return ws / wn >= bs / bn

    @staticmethod
    def luma_white_at_bottom(img, rect):
        l, t, r, b = rect
        cw = (r - l) / 8.0
        ch = (b - t) / 8.0
        img = np.asarray(img)

        def avg(rows):
            vals = []
            for row in rows:
                for col in range(8):
                    xs = (l + (col + 0.25 + 0.5 * (np.arange(6) + 0.5) / 6) * cw).astype(int)
                    ys = (t + (row + 0.2 + 0.6 * (np.arange(6) + 0.5) / 6) * ch).astype(int)
                    p = img[ys[:, None], xs[None, :]].reshape(-1, 3)
                    vals.append((0.299 * p[:, 0] + 0.587 * p[:, 1] + 0.114 * p[:, 2]).mean())
            return float(np.mean(vals))
        return avg((6, 7)) >= avg((0, 1))
