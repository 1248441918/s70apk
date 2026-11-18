"""校验 Android 工程：XML 合法性、Java 花括号平衡、以及算法逻辑的 Python 复刻测试。

这里没有 JDK/Android SDK，无法真正编译 APK；这个脚本尽量把"不需要编译器也能查的"
问题提前查出来，并用同样的算术复刻 Java 版算法，确认检测/修复逻辑正确。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree

import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app" / "src" / "main"


# --------------------------------------------------------------------- xml
def check_xml() -> list[str]:
    errors = []
    for p in sorted(ROOT.rglob("*.xml")):
        try:
            ElementTree.parse(p)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{p.relative_to(ROOT)}: {exc}")
    return errors


# ------------------------------------------------------------- java syntax
def _strip_java(src: str) -> str:
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            quote = c
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                i += 1
            out.append(" ")
        elif c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_java_balance() -> list[str]:
    errors = []
    for p in sorted((ANDROID / "java").rglob("*.java")):
        src = _strip_java(p.read_text(encoding="utf-8"))
        for open_ch, close_ch, label in (("{", "}", "braces"), ("(", ")", "parens")):
            depth = 0
            bad = False
            for ch in src:
                if ch == open_ch:
                    depth += 1
                elif ch == close_ch:
                    depth -= 1
                    if depth < 0:
                        bad = True
                        break
            if bad or depth != 0:
                errors.append(f"{p.name}: {label} unbalanced (depth={depth})")
    return errors


# ------------------------------------------------------- algorithm replica
def java_style_detect(frames: np.ndarray, threshold: int = 30, coverage: float = 0.6,
                      dilate: int = 1) -> np.ndarray:
    """复刻 PixelMapEngine.accumulate + pointsFromCounts 的算术。"""
    n, h, w, _ = frames.shape
    counts = np.zeros((h, w), dtype=np.int32)
    limit = threshold * 8
    for f in range(n):
        frame = frames[f].astype(np.int32)
        hot = np.zeros((h, w), dtype=bool)
        for ch in range(3):
            c = frame[:, :, ch]
            padded = np.pad(c, 1, mode="edge")
            hsum = padded[:, :-2] + padded[:, 1:-1] + padded[:, 2:]
            sum8 = hsum[:-2, :] + hsum[1:-1, :] + hsum[2:, :] - c
            hot |= (c * 8 - sum8) > limit
        counts += hot.astype(np.int32)

    min_hits = max(1, int(np.ceil(coverage * n)))
    mask = counts >= min_hits
    if dilate > 0:
        grown = mask.copy()
        for dy in range(-dilate, dilate + 1):
            for dx in range(-dilate, dilate + 1):
                grown |= np.roll(np.roll(mask, dy, axis=0), dx, axis=1)
        mask = grown
    return mask


def java_style_repair(img: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """复刻 PixelMapEngine.repair：8 邻居中位数。"""
    out = img.astype(np.int32).copy()
    h, w, _ = out.shape
    ys, xs = np.nonzero(mask)
    for y, x in zip(ys.tolist(), xs.tolist()):
        for ch in range(3):
            vals = []
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    if dx == 0 and dy == 0:
                        continue
                    vals.append(out[min(max(y + dy, 0), h - 1), min(max(x + dx, 0), w - 1), ch])
            vals.sort()
            out[y, x, ch] = (vals[3] + vals[4]) // 2
    return np.clip(out, 0, 255)


def test_algorithm() -> list[str]:
    errors = []
    rng = np.random.default_rng(11)
    h, w = 240, 320
    points = [(40, 30), (41, 30), (200, 120), (310, 239), (5, 235)]  # 含一对相邻坏点

    darks = []
    for s in range(5):
        r = np.random.default_rng(s)
        frame = np.clip(r.normal(6, 2.5, (h, w, 3)), 0, 255)
        for x, y in points:
            frame[y, x] = [230, 200, 180]
        darks.append(frame)
    darks = np.stack(darks).astype(np.uint8)

    mask = java_style_detect(darks)
    ys, xs = np.nonzero(mask)
    found = {(int(x), int(y)) for x, y in zip(xs, ys)}
    for p in points:
        if p not in found:
            errors.append(f"算法漏检坏点 {p}")

    # 误检：真实坏点附近以外不应有点
    near = {(x + dx, y + dy) for x, y in points for dx in range(-2, 3) for dy in range(-2, 3)}
    false_positives = found - near
    if len(false_positives) > 5:
        errors.append(f"误检过多: {len(false_positives)} 个")

    # 修复准确度
    clean = np.clip(rng.normal(110, 20, (h, w, 3)), 0, 255)
    photo = clean.copy()
    for x, y in points:
        photo[y, x] = [250, 250, 250]
    fixed = java_style_repair(np.clip(photo, 0, 255), mask)
    before = float(np.mean([np.abs(photo[y, x] - clean[y, x]).mean() for x, y in points]))
    after = float(np.mean([np.abs(fixed[y, x] - clean[y, x]).mean() for x, y in points]))
    if after > before * 0.2:
        errors.append(f"修复效果不足: {before:.1f} -> {after:.1f}")

    print(f"  检测坏点: 已知 {len(points)} 个，命中 "
          f"{sum(1 for p in points if p in found)} 个，缓存点总数 {len(found)}")
    print(f"  修复误差: {before:.1f} -> {after:.1f} (0-255)")
    return errors


def main() -> int:
    print("[1/3] XML")
    xml_errors = check_xml()
    print(f"  {'OK' if not xml_errors else 'FAIL'} ({len(list(ROOT.rglob('*.xml')))} files)")

    print("[2/3] Java 括号平衡")
    java_errors = check_java_balance()
    print(f"  {'OK' if not java_errors else 'FAIL'}")

    print("[3/3] 算法复刻测试")
    algo_errors = test_algorithm()

    errors = xml_errors + java_errors + algo_errors
    if errors:
        print("\n发现问题:")
        for e in errors:
            print("  -", e)
        return 1
    print("\n全部通过（注意：这不能替代真正的 Gradle 编译）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
