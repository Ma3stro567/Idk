"""Chess Bot для ПК (Windows): читает доску с экрана, считает ход Stockfish, рисует стрелку 1 → 2
и (по желанию) сама кликает мышью. Предназначен для игры против ботов / в своей игре."""
import ctypes
import json
import os
import random
import shutil
import sys
import threading
import time
try:
    import tkinter as tk
    from tkinter import ttk
except ImportError:  # самопроверка (--selftest) работает и без tkinter
    tk = ttk = None

import chess
import chess.engine
import numpy as np

from vision import Finder, Reader
import winutil
import cdp as cdpmod

CDP_OPTION = "Chrome (режим бота)"

APP_DIR = os.path.join(os.path.expanduser("~"), "ChessBotPC")
CFG_PATH = os.path.join(APP_DIR, "config.json")
TPL_PATH = os.path.join(APP_DIR, "templates.npz")
DEFAULTS = dict(elo=2800, ms=1000, pmin=0, pmax=0, color="Авто", auto=True, last_board=None,
                window="Весь экран", click_mode="Фоновые")


def load_cfg():
    try:
        with open(CFG_PATH, encoding="utf-8") as f:
            return {**DEFAULTS, **json.load(f)}
    except Exception:
        return dict(DEFAULTS)


def save_cfg(cfg):
    os.makedirs(APP_DIR, exist_ok=True)
    with open(CFG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f)


def find_stockfish():
    here = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
    # Stockfish вшит внутрь ChessBot.exe (распаковывается во временную папку) либо лежит рядом
    for d in (getattr(sys, "_MEIPASS", None), here, os.getcwd()):
        if not d:
            continue
        try:
            for name in sorted(os.listdir(d)):
                low = name.lower()
                if low.startswith("stockfish") and (low.endswith(".exe") or "." not in low):
                    return os.path.join(d, name)
        except OSError:
            pass
    return shutil.which("stockfish")


# ---------------------------------------------------------------- шахматная часть

def placement(board):
    return [(" " if board.piece_at(i) is None else board.piece_at(i).symbol()) for i in range(64)]


def mismatch(a, b):
    return sum(1 for x, y in zip(a, b) if x != y)


def mid_game_ok(obs):
    return obs.count("?") <= 3 and obs.count("K") == 1 and obs.count("k") == 1


def fen_of(obs, white_to_move):
    rows = []
    for rank in range(7, -1, -1):
        s, empty = "", 0
        for file in range(8):
            c = obs[rank * 8 + file]
            if c == " ":
                empty += 1
            else:
                if empty:
                    s += str(empty)
                    empty = 0
                s += c
        rows.append(s + (str(empty) if empty else ""))
    cs = ""
    if obs[4] == "K" and obs[7] == "R":
        cs += "K"
    if obs[4] == "K" and obs[0] == "R":
        cs += "Q"
    if obs[60] == "k" and obs[63] == "r":
        cs += "k"
    if obs[60] == "k" and obs[56] == "r":
        cs += "q"
    return "/".join(rows) + (" w " if white_to_move else " b ") + (cs or "-") + " - 0 1"


def find_move(board, obs, diff_cur):
    best, best_d = None, 99
    for m in list(board.legal_moves):
        board.push(m)
        d = mismatch(obs, placement(board))
        board.pop()
        if d < best_d:
            best, best_d = m, d
    return best if best is not None and best_d <= 1 and best_d < diff_cur else None


def square_center(rect, white, sq):
    l, t, r, b = rect
    f, rk = sq % 8, sq // 8
    col = f if white else 7 - f
    row = 7 - rk if white else rk
    return l + (col + 0.5) * (r - l) / 8.0, t + (row + 0.5) * (b - t) / 8.0


# ---------------------------------------------------------------- программа

class App:
    def __init__(self):
        self.cfg = load_cfg()
        self.root = tk.Tk()
        self.root.title("Chess Bot")
        self.root.attributes("-topmost", True)
        self.root.resizable(False, False)
        self.running = False
        self.alive = True
        self.worker = None
        self.engine = None
        self.overlay = None
        self.sct = None
        self.mon = None
        self.target = None   # hwnd выбранного окна (None = весь экран)
        self.cdp = None      # подключение к вкладке Chrome через DevTools
        self.win_map = {}
        self.excluded = False
        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.quit)
        self.root.after(300, self._init_win)

    # ---------- окно
    def _build_ui(self):
        f = ttk.Frame(self.root, padding=10)
        f.grid()
        self.v_elo = tk.StringVar(value=str(self.cfg["elo"]))
        self.v_ms = tk.StringVar(value=str(self.cfg["ms"]))
        self.v_pmin = tk.StringVar(value=str(self.cfg["pmin"]))
        self.v_pmax = tk.StringVar(value=str(self.cfg["pmax"]))
        self.v_color = tk.StringVar(value=self.cfg["color"])
        self.v_auto = tk.BooleanVar(value=self.cfg["auto"])
        rows = [("Сила, Эло (1320–3190, 0 = максимум)", ttk.Entry(f, textvariable=self.v_elo, width=8)),
                ("Время на ход, мс", ttk.Entry(f, textvariable=self.v_ms, width=8)),
                ("Пауза перед ходом от, с", ttk.Entry(f, textvariable=self.v_pmin, width=8)),
                ("Пауза перед ходом до, с", ttk.Entry(f, textvariable=self.v_pmax, width=8)),
                ("Я играю за", ttk.Combobox(f, textvariable=self.v_color, width=8, state="readonly",
                                            values=["Авто", "Белых", "Чёрных"]))]
        for i, (txt, w) in enumerate(rows):
            ttk.Label(f, text=txt).grid(row=i, column=0, sticky="w", pady=2)
            w.grid(row=i, column=1, padx=6)
        self.v_window = tk.StringVar(value=self.cfg["window"])
        self.v_mode = tk.StringVar(value=self.cfg["click_mode"])
        ttk.Label(f, text="Окно с доской").grid(row=5, column=0, sticky="w", pady=2)
        wf = ttk.Frame(f)
        wf.grid(row=5, column=1, sticky="w", padx=6)
        self.cb_window = ttk.Combobox(wf, textvariable=self.v_window, width=34, state="readonly")
        self.cb_window.grid(row=0, column=0)
        ttk.Button(wf, text="⟳", width=3, command=self.refresh_windows).grid(row=0, column=1, padx=2)
        ttk.Button(f, text="Запустить Chrome для бота", command=self.launch_chrome).grid(
            row=10, column=0, columnspan=2, sticky="w", pady=(6, 0))
        ttk.Label(f, text="Клики в окне").grid(row=6, column=0, sticky="w", pady=2)
        ttk.Combobox(f, textvariable=self.v_mode, width=34, state="readonly",
                     values=["Фоновые", "Мышью"]).grid(row=6, column=1, sticky="w", padx=6)
        ttk.Checkbutton(f, text="Кликать автоматически", variable=self.v_auto).grid(
            row=7, column=0, columnspan=2, sticky="w", pady=4)
        b = ttk.Frame(f)
        b.grid(row=8, column=0, columnspan=2, pady=4)
        ttk.Button(b, text="▶ Мой ход", command=lambda: self.start(False)).grid(row=0, column=0, padx=2)
        ttk.Button(b, text="▶ Его ход", command=lambda: self.start(True)).grid(row=0, column=1, padx=2)
        ttk.Button(b, text="⏸ Пауза", command=self.pause).grid(row=0, column=2, padx=2)
        self.status = tk.StringVar(value="Открой партию с видимой доской и жми «▶ Мой ход» (или «▶ Его ход»).")
        ttk.Label(f, textvariable=self.status, wraplength=360, justify="left").grid(
            row=9, column=0, columnspan=2, sticky="w", pady=(6, 0))
        self.refresh_windows()

    def refresh_windows(self):
        wins = winutil.list_windows() if winutil.AVAILABLE else []
        self.win_map = {t: h for h, t in wins}
        vals = ["Весь экран", CDP_OPTION] + sorted(self.win_map)
        cur = self.v_window.get()
        if cur not in vals:
            vals.insert(1, cur)  # сохранённое окно, даже если сейчас его нет в списке
        self.cb_window.configure(values=vals)

    def launch_chrome(self):
        try:
            cdpmod.launch_browser(os.path.join(APP_DIR, "chrome-profile"))
            self.v_window.set(CDP_OPTION)
            self.set_status("Chrome запущен в режиме бота (отдельный профиль: в нём один раз войди в аккаунт). "
                            "Открой партию, выбери «Chrome (режим бота)» и жми «▶ Мой ход».")
        except Exception as e:
            self.set_status(f"Не удалось запустить Chrome: {e}")

    def _init_win(self):
        if sys.platform == "win32":
            self._exclude_from_capture(self.root)
        self.root.geometry("+30+30")

    def _hwnd(self, win):
        u = ctypes.windll.user32
        return u.GetParent(win.winfo_id()) or win.winfo_id()

    def _exclude_from_capture(self, win):
        """Окна бота не попадают на скриншоты (Windows 10 2004+), поэтому не мешают распознаванию."""
        try:
            ok = ctypes.windll.user32.SetWindowDisplayAffinity(self._hwnd(win), 0x11)
            if win is self.root:
                self.excluded = bool(ok)
        except Exception:
            pass

    def set_status(self, s):
        self.root.after(0, lambda: self.status.set(s))

    def ui(self, fn):
        """Выполнить fn в потоке интерфейса и дождаться."""
        ev, box = threading.Event(), []

        def run():
            try:
                box.append(fn())
            finally:
                ev.set()
        self.root.after(0, run)
        ev.wait(5)
        return box[0] if box else None

    # ---------- оверлей
    def _make_overlay(self):
        mon = self.mon
        w = tk.Toplevel(self.root)
        w.overrideredirect(True)
        w.geometry(f"{mon['width']}x{mon['height']}+{mon['left']}+{mon['top']}")
        w.attributes("-topmost", True)
        key = "#010101"
        w.config(bg=key)
        try:
            w.attributes("-transparentcolor", key)
        except tk.TclError:
            pass
        cv = tk.Canvas(w, bg=key, highlightthickness=0)
        cv.pack(fill="both", expand=True)
        w.update_idletasks()
        if sys.platform == "win32":
            try:
                u = ctypes.windll.user32
                hwnd = self._hwnd(w)
                style = u.GetWindowLongW(hwnd, -20)
                u.SetWindowLongW(hwnd, -20, style | 0x80000 | 0x20 | 0x80)  # layered + click-through + toolwindow
                u.SetWindowDisplayAffinity(hwnd, 0x11)
            except Exception:
                pass
        self.overlay, self.canvas = w, cv

    def show_move(self, a, b, cell):
        def draw():
            if self.overlay is None:
                self._make_overlay()
            cv, mon = self.canvas, self.mon
            cv.delete("all")
            ax, ay, bx, by = a[0] - mon["left"], a[1] - mon["top"], b[0] - mon["left"], b[1] - mon["top"]
            r = cell * 0.42
            cv.create_line(ax, ay, bx, by, width=max(8, cell * 0.1), fill="#ff3b30", arrow=tk.LAST,
                           arrowshape=(24, 28, 12), capstyle=tk.ROUND)
            for (x, y, col, n) in ((ax, ay, "#34c759", "1"), (bx, by, "#ff9500", "2")):
                cv.create_oval(x - r, y - r, x + r, y + r, outline=col, width=6)
                cv.create_text(x, y, text=n, fill="white", font=("Arial", int(r * 0.9), "bold"))
        self.ui(draw)

    def clear_move(self):
        self.ui(lambda: self.canvas.delete("all") if self.overlay is not None else None)

    # ---------- захват экрана
    def _open_capture(self):
        """mss на Windows привязан к потоку, где создан: создаём заново в каждом запуске (иначе ошибка BitBlt)."""
        import mss
        self._close_capture()
        self.sct = mss.mss()
        self.mon = dict(self.sct.monitors[1])

    def _close_capture(self):
        try:
            if self.sct is not None:
                self.sct.close()
        except Exception:
            pass
        self.sct = None

    def grab(self):
        hide = (not self.excluded) and self.overlay is not None
        if hide:
            self.ui(lambda: self.overlay.withdraw())
            time.sleep(0.12)
        try:
            last = None
            for attempt in range(4):
                try:
                    if self.cdp:
                        try:
                            return self.cdp.screenshot()
                        except Exception:
                            self.cdp.close()      # вкладку могли перезагрузить: переподключаемся
                            self.cdp.connect()
                            return self.cdp.screenshot()
                    if self.target:
                        if winutil.is_iconic(self.target):
                            raise RuntimeError("окно свёрнуто — разверни его (можно закрыть другими окнами)")
                        return winutil.capture_window(self.target)
                    shot = self.sct.grab(self.mon)
                    return np.array(shot)[:, :, :3][:, :, ::-1]
                except RuntimeError:
                    raise
                except Exception as e:  # BitBlt/GetDIBits после блокировки экрана, смены разрешения и т.п.
                    last = e
                    time.sleep(0.4)
                    if not self.target and not self.cdp:
                        try:
                            self._open_capture()
                        except Exception as e2:
                            last = e2
            raise RuntimeError(f"не удалось сделать снимок экрана: {last}")
        finally:
            if hide:
                self.ui(lambda: self.overlay.deiconify())

    # ---------- координаты: x, y — в системе снимка (экран или окно)
    def to_screen(self, x, y):
        if self.target:
            wl, wt = winutil.window_origin(self.target)
            return wl + x, wt + y
        return self.mon["left"] + x, self.mon["top"] + y

    # ---------- клики
    @staticmethod
    def _human_point(x, y, cell):
        dx = max(-0.3, min(0.3, random.gauss(0, 0.12))) * cell
        dy = max(-0.3, min(0.3, random.gauss(0, 0.12))) * cell
        return x + dx, y + dy

    def _mouse_click(self, x, y):
        import pyautogui
        pyautogui.PAUSE = 0
        pyautogui.moveTo(x, y, duration=random.uniform(0.25, 0.6), tween=pyautogui.easeOutQuad)
        time.sleep(random.uniform(0.05, 0.15))
        pyautogui.mouseDown()
        time.sleep(random.uniform(0.04, 0.12))
        pyautogui.mouseUp()

    def _click_point(self, x, y, cell):
        """Один клик в точке снимка (x, y) с человеческим разбросом."""
        x, y = self._human_point(x, y, cell)
        if self.cdp:
            self.cdp.click(x, y)  # клик прямо во вкладку, настоящая мышь не используется
            return
        ax, ay = self.to_screen(x, y)
        if self.target and self.cfg["click_mode"] == "Фоновые":
            winutil.post_click(self.target, ax, ay)  # настоящий курсор не двигается
        else:
            self._mouse_click(ax, ay)

    def click_move(self, move, rect, white):
        import pyautogui
        cell = (rect[2] - rect[0]) / 8.0
        ax, ay = square_center(rect, white, move.from_square)
        bx, by = square_center(rect, white, move.to_square)
        use_mouse = not (self.target and self.cfg["click_mode"] == "Фоновые")
        old_pos = None
        if self.target and use_mouse and not self.cdp:
            old_pos = pyautogui.position()
            winutil.bring_to_front(self.target)
            time.sleep(0.25)
        self._click_point(ax, ay, cell)
        time.sleep(random.uniform(0.28, 0.8))
        if not self.running:
            return  # поставили на паузу между кликами
        self._click_point(bx, by, cell)
        if move.promotion:
            time.sleep(random.uniform(0.45, 0.9))
            self._click_point(bx, by, cell)  # ферзь — первый вариант на поле назначения
        if old_pos is not None:
            pyautogui.moveTo(old_pos[0], old_pos[1], duration=0.2)  # вернуть курсор пользователю

    # ---------- управление
    def _read_cfg(self):
        def num(v, d, lo, hi):
            try:
                return max(lo, min(hi, int(float(v.get()))))
            except Exception:
                return d
        self.cfg.update(elo=num(self.v_elo, 2800, 0, 3190), ms=num(self.v_ms, 1000, 100, 60000),
                        pmin=num(self.v_pmin, 0, 0, 120), pmax=num(self.v_pmax, 0, 0, 120),
                        color=self.v_color.get(), auto=bool(self.v_auto.get()),
                        window=self.v_window.get(), click_mode=self.v_mode.get())
        save_cfg(self.cfg)

    def start(self, opponent_first):
        # повторное нажатие (в т.ч. после паузы) перезапускает бота: доска находится и читается заново
        old = self.worker
        if old is not None and old.is_alive():
            self.running = False
            old.join(4)
        self._read_cfg()
        self.running = True
        self.worker = threading.Thread(target=self.run_bot, args=(opponent_first,), daemon=True)
        self.worker.start()

    def pause(self):
        """Пауза: бот ничего не делает. «▶ Мой ход» / «▶ Его ход» — доска будет прочитана заново."""
        self.running = False
        self.clear_move()
        self.set_status("Пауза. Выбери «▶ Мой ход» или «▶ Его ход» — бот заново прочитает доску и продолжит.")

    def quit(self):
        self.alive = self.running = False
        try:
            if self.engine:
                self.engine.quit()
        except Exception:
            pass
        self.root.destroy()

    def nap(self, s):
        time.sleep(s)

    # ---------- главный цикл
    def run_bot(self, opponent_first):
        try:
            self._open_capture()
            self._run(opponent_first)
        except Exception as e:  # noqa
            self.set_status(f"Ошибка: {e}")
        finally:
            self.running = False
            self.clear_move()
            self._close_capture()
            if self.cdp:
                self.cdp.close()

    def _run(self, opponent_first):
        cfg = self.cfg
        sf = find_stockfish()
        if not sf:
            self.set_status("Не найден Stockfish. Положи stockfish.exe рядом с ChessBot.exe.")
            return
        title = cfg.get("window", "Весь экран")
        self.target = None
        if self.cdp:
            self.cdp.close()
        self.cdp = None
        if title == CDP_OPTION:
            if not cdpmod.available():
                self.set_status("Chrome в режиме бота не запущен. Нажми «Запустить Chrome для бота», открой партию "
                                "и снова жми «▶ Мой ход».")
                return
            self.cdp = cdpmod.CDP()
            self.cdp.connect()
        elif title and title != "Весь экран":
            self.target = winutil.find_window(title) if winutil.AVAILABLE else None
            if not self.target:
                self.set_status(f"Окно «{title}» не найдено. Открой его и нажми ⟳ в списке окон.")
                return
        self.set_status("Ищу доску " + ("в окне…" if (self.target or self.cdp) else "на экране…"))
        frame = self.grab()
        first_shape = frame.shape
        finder = Finder()
        rect = finder.find(frame) or finder.find(frame, relaxed=True)
        if rect is None and cfg.get("last_board"):
            rect = finder.fallback(frame, tuple(cfg["last_board"]))
        if rect is None:
            os.makedirs(APP_DIR, exist_ok=True)
            try:
                from PIL import Image
                Image.fromarray(frame).save(os.path.join(APP_DIR, "debug.png"))
            except Exception:
                pass
            self.set_status(f"Не нашёл доску на экране. {finder.last_info}. Кадр сохранён: {APP_DIR}\\debug.png")
            return
        cfg["last_board"] = list(rect)
        save_cfg(cfg)
        cell = (rect[2] - rect[0]) / 8.0

        reader = Reader()
        had = reader.load(TPL_PATH) or reader.load_builtin()
        expected = placement(chess.Board())
        mode = {"Белых": True, "Чёрных": False}.get(cfg["color"])
        obs, start_pos = None, False
        if had:
            tmp = reader.read(frame, rect, True)
            white = mode if mode is not None else Reader.white_at_bottom(tmp)
            if white is None:
                white = Reader.luma_white_at_bottom(frame, rect)
            obs = reader.read(frame, rect, white)
            start_pos = mismatch(obs, expected) <= 1
        else:
            white = mode if mode is not None else Reader.luma_white_at_bottom(frame, rect)

        board = chess.Board()
        if not had or (not start_pos and not mid_game_ok(obs)):
            learned, bad = False, 0
            for which in (0, 1):
                reader.learn(frame, rect, white, which)
                obs = reader.read(frame, rect, white)
                bad = mismatch(obs, expected)
                if bad <= 4:
                    learned = True
                    break
            if not learned:
                self.set_status("Не узнаю фигуры. Бот должен один раз увидеть начальную позицию (новая партия, "
                                f"максимум пара ходов) — он запомнит вид фигур. (ошибок: {bad})")
                return
            os.makedirs(APP_DIR, exist_ok=True)
            reader.save(TPL_PATH)
            start_pos = mismatch(obs, expected) <= 1

        my_color = chess.WHITE if white else chess.BLACK
        if not start_pos:
            white_to_move = (my_color == chess.WHITE) == (not opponent_first)
            board.set_fen(fen_of(obs, white_to_move))
        self.set_status(f"Доска найдена, я за {'белых' if white else 'чёрных'}" +
                        (", новая партия" if start_pos else ", партия с середины"))

        if self.engine is None:
            self.set_status("Запускаю Stockfish…")
            self.engine = chess.engine.SimpleEngine.popen_uci(sf)
            self.engine.configure({"Threads": min(4, os.cpu_count() or 1), "Hash": 128})
        if cfg["elo"] > 0:
            self.engine.configure({"UCI_LimitStrength": True, "UCI_Elo": max(1320, min(3190, cfg["elo"]))})
        else:
            self.engine.configure({"UCI_LimitStrength": False})

        pending, pending_since, prev = None, 0.0, None
        while self.alive and self.running:
            frame = self.grab()
            if frame.shape != first_shape:  # окно изменили в размере — ищем доску заново
                new = finder.find(frame) or finder.find(frame, relaxed=True)
                if new is not None:
                    rect = new
                    cell = (rect[2] - rect[0]) / 8.0
                    cfg["last_board"] = list(rect)
                first_shape = frame.shape
            obs = reader.read(frame, rect, white)
            if prev is None or mismatch(obs, prev) > 1:
                prev = obs
                self.set_status("Жду, пока картинка устоится…")
                self.nap(0.25)
                continue
            cur0 = placement(board)
            diff = mismatch(obs, cur0)
            if diff >= 2:
                mv = find_move(board, obs, diff)
                if mv is None:
                    board.turn = not board.turn  # очередь хода могла быть угадана неверно
                    mv = find_move(board, obs, diff)
                    if mv is None:
                        board.turn = not board.turn
                if mv is not None:
                    board.push(mv)
                    pending = None
                    self.clear_move()
                    if board.is_checkmate():
                        self.set_status("Мат. Партия окончена")
                        break
                    if board.is_stalemate() or board.is_insufficient_material():
                        self.set_status("Ничья. Партия окончена")
                        break
                elif mid_game_ok(obs):
                    moved = next((i for i in range(64) if obs[i] not in " ?" and obs[i] != cur0[i]), None)
                    wtm = (not obs[moved].isupper()) if moved is not None else (my_color == chess.WHITE)
                    board.set_fen(fen_of(obs, wtm))
                    pending = None
                    self.clear_move()
                    self.set_status("Позиция пересинхронизирована с экрана")
                else:
                    self.set_status(f"Не понял, что изменилось на доске (отличий: {diff}). Жду…")
                    self.nap(0.3)
                    continue

            if board.turn == my_color and pending is None:
                self.set_status("Думаю…")
                res = self.engine.play(board, chess.engine.Limit(time=cfg["ms"] / 1000.0))
                move = res.move
                if move is None:
                    self.set_status("Нет хода от движка")
                    break
                lo, hi = cfg["pmin"], max(cfg["pmax"], cfg["pmin"])
                if hi > 0:
                    secs = random.uniform(lo, hi)
                    if random.random() < 0.1:
                        secs = hi + random.random() * hi * 0.2
                    until = time.time() + secs
                    while self.alive and self.running and time.time() < until:
                        self.set_status(f"Ход найден, жду {int(until - time.time()) + 1} с…")
                        self.nap(0.25)
                    if not (self.alive and self.running):
                        break
                pending, pending_since = move, time.time()
                a = square_center(rect, white, move.from_square)
                b = square_center(rect, white, move.to_square)
                if not self.cdp and not (self.target and cfg["auto"]):  # в фоновых режимах стрелка не нужна
                    self.show_move(self.to_screen(*a), self.to_screen(*b), cell)
                self.set_status(f"Ход: {move.uci()}   (1 — откуда, 2 — куда)")
                if cfg["auto"]:
                    self.click_move(move, rect, white)
            elif pending is not None and cfg["auto"] and time.time() - pending_since > 6:
                pending_since = time.time()
                self.click_move(pending, rect, white)  # ход не прошёл — пробуем ещё раз
            if board.turn != my_color:
                self.set_status("Жду хода соперника…")
            self.nap(0.35)

    def main(self):
        self.root.mainloop()


def selftest(path):
    """python chessbot_pc.py --selftest скриншот.png — проверка распознавания без экрана и без мыши."""
    from PIL import Image
    img = np.array(Image.open(path).convert("RGB"))
    f = Finder()
    rect = f.find(img) or f.find(img, relaxed=True)
    print("доска:", rect, f.last_info)
    if not rect:
        return
    rd = Reader()
    rd.load_builtin()
    tmp = rd.read(img, rect, True)
    white = Reader.white_at_bottom(tmp)
    obs = rd.read(img, rect, white if white is not None else True)
    print("белые внизу:", white)
    for row in range(8):
        line = []
        for col in range(8):
            f_, r_ = (col, 7 - row) if white else (7 - col, row)
            c = obs[r_ * 8 + f_]
            line.append("." if c == " " else c)
        print(" ".join(line))
    print("FEN (ход белых):", fen_of(obs, True))


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--selftest":
        selftest(sys.argv[2])
    else:
        if sys.platform == "win32":
            try:
                ctypes.windll.shcore.SetProcessDpiAwareness(2)  # координаты в физических пикселях
            except Exception:
                pass
        App().main()
