"""Generates ChessCraft textures, models, blockstates, loot tables and GUI sprites.

Run from the project root:  python tools/gen_assets.py
Everything is procedural and deterministic, so re-running reproduces identical files.
"""
import json
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import gui_pieces

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "chesscraft")
DATA = os.path.join(RES, "data", "chesscraft")
NS = "chesscraft"

COLORS = ["white", "black"]
TYPES = ["pawn", "knight", "bishop", "rook", "queen", "king"]


def out(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def write_json(path, obj):
    with open(out(path), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


# --------------------------------------------------------------------------- noise

class Noise:
    """Smooth 2D value noise with a fixed seed."""

    def __init__(self, seed, size=64):
        rnd = random.Random(seed)
        self.size = size
        self.grid = [[rnd.random() for _ in range(size)] for _ in range(size)]

    def at(self, x, y):
        s = self.size
        x0, y0 = int(math.floor(x)), int(math.floor(y))
        fx, fy = x - x0, y - y0
        fx = fx * fx * (3 - 2 * fx)
        fy = fy * fy * (3 - 2 * fy)
        g = self.grid
        a = g[y0 % s][x0 % s]
        b = g[y0 % s][(x0 + 1) % s]
        c = g[(y0 + 1) % s][x0 % s]
        d = g[(y0 + 1) % s][(x0 + 1) % s]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy

    def fbm(self, x, y, octaves=3):
        v, amp, tot = 0.0, 1.0, 0.0
        for _ in range(octaves):
            v += self.at(x, y) * amp
            tot += amp
            x, y, amp = x * 2.03, y * 2.03, amp * 0.5
        return v / tot


def clamp8(v):
    return max(0, min(255, int(round(v))))


def mix(c1, c2, t):
    return tuple(clamp8(a + (b - a) * t) for a, b in zip(c1, c2))


def wood(w, h, light, dark, seed, vertical=True, rings=7.0, contrast=1.0):
    """Wood grain: stretched noise bands between a light and a dark tone."""
    n = Noise(seed)
    m = Noise(seed + 101)
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            a, b = (x, y) if vertical else (y, x)
            along = b / max(w, h)
            across = a / max(w, h)
            warp = n.fbm(across * 3.0, along * 0.6) * 2.2
            band = 0.5 + 0.5 * math.sin((across * rings + warp) * math.pi * 2)
            band = band ** 2.2
            fleck = m.fbm(across * 18, along * 2.5)
            t = min(1.0, max(0.0, (band * 0.7 + fleck * 0.3) * contrast))
            px[x, y] = mix(light, dark, t) + (255,)
    return img


def metal(w, h, top, bottom, seed):
    n = Noise(seed)
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = y / max(1, h - 1)
            c = mix(top, bottom, t)
            s = (n.fbm(x * 0.6, y * 0.6) - 0.5) * 26
            px[x, y] = tuple(clamp8(v + s) for v in c) + (255,)
    return img


def felt(w, h, base, seed):
    n = Noise(seed)
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            s = (n.fbm(x * 1.7, y * 1.7) - 0.5) * 30
            px[x, y] = tuple(clamp8(v + s) for v in base) + (255,)
    return img


# --------------------------------------------------------------------------- palettes

PIECE_STYLE = {
    "white": {
        "body": ((232, 219, 188), (190, 168, 126)),
        "accent": ((255, 226, 128), (176, 128, 40)),
        "detail": (52, 36, 26),
        "cut": (150, 132, 104),
        "felt": (38, 92, 58),
    },
    "black": {
        "body": ((74, 52, 42), (24, 16, 14)),
        "accent": ((255, 220, 120), (168, 118, 34)),
        "detail": (236, 222, 186),
        "cut": (16, 10, 8),
        "felt": (38, 92, 58),
    },
}

LIGHT_SQ = ((246, 226, 192), (222, 190, 146))
DARK_SQ = ((170, 112, 68), (120, 72, 40))
FRAME = ((104, 50, 28), (52, 22, 12))
TABLE = ((112, 72, 44), (54, 32, 20))
BRASS = ((255, 222, 140), (170, 120, 44))


# --------------------------------------------------------------------------- piece textures
# Piece texture: 32x32 pixels = 16x16 UV units.
# body u0-12, accent u12-14, felt u14-16 v0-8, detail u14-16 v8-16.
REGIONS = {
    "body": (0, 0, 12, 16),
    "accent": (12, 0, 14, 16),
    "felt": (14, 0, 16, 8),
    "detail": (14, 8, 16, 12),
    "cut": (14, 12, 16, 16),
}


def piece_texture(color):
    """Ivory/ebony grain with a metallic gold strip, felt and detail regions."""
    st = PIECE_STYLE[color]
    img = Image.new("RGBA", (32, 32))
    seed = 11 if color == "white" else 23
    img.paste(wood(24, 32, st["body"][0], st["body"][1], seed, vertical=True, rings=2.6, contrast=0.7), (0, 0))
    img.paste(metal(4, 32, st["accent"][0], st["accent"][1], seed + 1), (24, 0))
    img.paste(felt(4, 16, st["felt"], seed + 2), (28, 0))
    img.paste(Image.new("RGBA", (4, 8), st["detail"] + (255,)), (28, 16))
    img.paste(Image.new("RGBA", (4, 8), st["cut"] + (255,)), (28, 24))
    return img


# --------------------------------------------------------------------------- piece geometry

class Box:
    def __init__(self, frm, to, mat, faces=None, felt_bottom=False, rot=None):
        self.frm = [float(v) for v in frm]
        self.to = [float(v) for v in to]
        self.mat = mat
        self.faces = faces or ["north", "south", "east", "west", "up", "down"]
        self.felt_bottom = felt_bottom
        # Own rotation: (3x3 numpy matrix, origin) applied before the piece's yaw.
        self.rot = rot


def rot_axis(axis, deg):
    """Right-handed rotation matrix about x, y or z (matches Minecraft element rotation)."""
    a = math.radians(deg)
    c, s_ = math.cos(a), math.sin(a)
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s_], [0, s_, c]])
    if axis == "y":
        return np.array([[c, 0, s_], [0, 1, 0], [-s_, 0, c]])
    return np.array([[c, -s_, 0], [s_, c, 0], [0, 0, 1]])


def disc(y0, y1, r, mat, felt_bottom=False):
    """Octagonal disc centred on (8, 8) approximated by stacked boxes without overlaps."""
    c = 8.0
    boxes = []
    if r < 1.5:
        boxes.append(Box([c - r, y0, c - r], [c + r, y1, c + r], mat, felt_bottom=felt_bottom))
        return boxes
    steps = 2 if r >= 4 else 1
    b = 1.0 if r >= 2.5 else 0.5
    inner = r - steps * b
    # Core spans the full width along x.
    boxes.append(Box([c - r, y0, c - inner], [c + r, y1, c + inner], mat, felt_bottom=felt_bottom))
    for s in range(1, steps + 1):
        half = r - s * b          # half-width along x of this strip
        z0 = r - (steps - s + 1) * b
        z1 = r - (steps - s) * b
        # Strips toward north (-z) and south (+z); faces toward the core are hidden.
        boxes.append(Box([c - half, y0, c - z1], [c + half, y1, c - z0], mat,
                         faces=["north", "east", "west", "up", "down"], felt_bottom=felt_bottom))
        boxes.append(Box([c - half, y0, c + z0], [c + half, y1, c + z1], mat,
                         faces=["south", "east", "west", "up", "down"], felt_bottom=felt_bottom))
    return boxes


def base(r_foot):
    return (disc(0, 0.75, r_foot, "body", felt_bottom=True)
            + disc(0.75, 1.5, r_foot - 0.5, "body")
            + disc(1.5, 2.0, r_foot - 1.25, "accent"))


def layers(spec):
    boxes = []
    for y0, y1, r, mat in spec:
        boxes += disc(y0, y1, r, mat)
    return boxes


def square(y0, y1, w, mat, felt_bottom=False, faces=None):
    """Square tier of width w centred on (8, 8)."""
    h = w / 2
    return Box([8 - h, y0, 8 - h], [8 + h, y1, 8 + h], mat, faces=faces, felt_bottom=felt_bottom)


def pawn():
    """Pawn fitted to the user's reference image (tier widths and heights measured from it, then
    scaled to 12px tall): base, gold plinth, step, column with a gold band, gold plate, cube head."""
    widths = [7.14, 6.51, 4.62, 2.94, 2.94, 4.41, 3.57]
    heights = [1.259, 1.630, 1.517, 2.289, 1.390, 1.139, 2.776]
    mats = ["body", "accent", "body", "body", "accent", "accent", "body"]
    k = 0.77  # whole-piece scale so the pawn sits right among the other references
    boxes, y = [], 0.0
    for w, h, m in zip(widths, heights, mats):
        boxes.append(square(round(y, 3), round(y + h * k, 3), round(w * k, 3), m))
        y += h * k
    return boxes


# The reference front views read wider than their hero (isometric) views; 0.8 matches the latter.
SLIM = 0.8


def stack(spec, scale, y=0.0):
    """Square tiers from a list of (height_px, width_px, material) measured bottom-up on a
    reference front view; returns (boxes, top_y)."""
    boxes = []
    for h_px, w_px, mat in spec:
        h = h_px * scale
        boxes.append(square(round(y, 3), round(y + h, 3), round(w_px * scale * SLIM, 3), mat))
        y += h
    return boxes, y


def octagon_layer(y0, y1, r, mat):
    """Thin round-ish slice of radius r (an octagon from three boxes), used for spheres."""
    if r < 0.35:
        return [square(y0, y1, 2 * r, mat)]
    b = r * 0.3
    return [
        Box([8 - r, y0, 8 - r + b], [8 + r, y1, 8 + r - b], mat),
        Box([8 - r + b, y0, 8 - r], [8 + r - b, y1, 8 - r + b], mat, faces=["north", "east", "west", "up", "down"]),
        Box([8 - r + b, y0, 8 + r - b], [8 + r - b, y1, 8 + r], mat, faces=["south", "east", "west", "up", "down"]),
    ]


def ball(y0, r, mat, layers=10):
    """Sphere of radius r resting on y0, built from thin octagonal slices."""
    boxes = []
    dy = 2 * r / layers
    for i in range(layers):
        mid = -r + (i + 0.5) * dy
        rr = math.sqrt(max(0.0, r * r - mid * mid))
        boxes += octagon_layer(round(y0 + i * dy, 3), round(y0 + (i + 1) * dy, 3), round(rr, 3), mat)
    return boxes


def corner_blocks(y0, height, outer, size, mat):
    """Four square blocks of the given size sitting in the corners of an outer square."""
    out = []
    for sx in (-1, 1):
        for sz in (-1, 1):
            cx, cz = 8 + sx * (outer - size) / 2, 8 + sz * (outer - size) / 2
            out.append(Box([round(cx - size / 2, 3), round(y0, 3), round(cz - size / 2, 3)],
                           [round(cx + size / 2, 3), round(y0 + height, 3), round(cz + size / 2, 3)], mat))
    return out


def rook():
    """Measured from the rook reference (front view, px): base, gold plinth, step, column with
    corner posts, gold band, thin lip and a crown with four corner merlons."""
    total_px = 42 + 41 + 40 + 100 + 40 + 8 + 50 + 35
    sc = 13.0 / total_px
    boxes, y = stack([(42, 252, "body"), (41, 222, "accent"), (40, 180, "body")], sc)
    col_h, col_w = 100 * sc, 126 * sc * SLIM
    boxes.append(square(round(y, 3), round(y + col_h, 3), round(col_w * 0.86, 3), "body"))
    boxes += corner_blocks(y, col_h, col_w, col_w * 0.24, "body")
    y += col_h
    more, y = stack([(40, 136, "accent"), (8, 140, "body"), (50, 182, "body")], sc, y)
    boxes += more
    boxes += corner_blocks(y, 35 * sc, 182 * sc * SLIM, (182 - 80) / 2 * sc * SLIM, "body")
    return boxes


def knight():
    """Traced from the knight reference (LEFT view, px grid): base tiers, a straight neck with a
    smooth 45-degree throat, a long head and muzzle with a sloped nose, ear tuft, stepped mane and
    recessed eyes. Widths come from the FRONT view. The horse faces north (-z)."""
    fs = 14.0 / 374  # front-view scale for widths
    ss = 14.0 / 390  # side-view scale; base bottom at y=921 px, centre x=955 px
    boxes, base_top = stack([(45, 222, "body"), (23, 190, "accent"), (27, 172, "body"), (25, 150, "body"),
                             (38, 135, "accent")], ss)

    def h(py):
        return round((921 - py) * ss, 3)

    def z(px):
        return round(8 + (px - 955) * ss * KNIGHT_DEPTH, 3)

    def width(w_px):
        return w_px * fs * SLIM / 2

    def part(z0, z1, ytop, ybot, w_px, mat="body"):
        w = width(w_px)
        bottom = max(base_top, h(ybot))
        boxes.append(Box([round(8 - w, 3), round(bottom, 3), z(z0)], [round(8 + w, 3), h(ytop), z(z1)], mat))

    def slope(p1, p2, thick_px, w_px, inward):
        """Plate whose outer face runs along the side-view line p1->p2 (px), filling inward."""
        a = np.array([8.0, h(p1[1]), z(p1[0])])
        b = np.array([8.0, h(p2[1]), z(p2[0])])
        d = b - a
        length = float(np.linalg.norm(d))
        ang = math.degrees(math.atan2(d[1], d[2]))       # angle of the line in the (z, y) plane
        n = np.array([0.0, inward[1], inward[0]])
        n = n / np.linalg.norm(n)
        thick = thick_px * ss
        centre = (a + b) / 2 + n * thick / 2
        w = width(w_px)
        boxes.append(Box([8 - w, centre[1] - thick / 2, centre[2] - length / 2],
                         [8 + w, centre[1] + thick / 2, centre[2] + length / 2],
                         "body", rot=(rot_axis("x", -ang), centre)))

    part(960, 1020, 600, 762, 100)                       # neck
    slope((888, 752), (962, 678), 60, 100, (1, -1))      # smooth throat from collar to jaw
    part(885, 1010, 585, 690, 100)                       # head
    part(828, 885, 640, 690, 80)                         # muzzle
    slope((828, 640), (885, 585), 50, 80, (1, -1))       # sloped nose
    part(840, 875, 688, 705, 74)                         # lower lip
    part(900, 1010, 568, 585, 92)                        # top of the head
    part(925, 965, 530, 568, 40)                         # ear tuft
    part(965, 1015, 575, 600, 56)                        # mane
    part(1010, 1045, 600, 640, 56)
    part(1020, 1068, 645, 690, 56)
    part(1040, 1080, 700, 740, 56)
    part(1015, 1045, 690, 760, 56)
    # Recessed eyes on both cheeks.
    half = width(100)
    ey0, ey1, ez0, ez1 = h(648), h(617), z(897), z(927)
    boxes.append(Box([round(8 - half - 0.12, 3), ey0, ez0], [round(8 - half + 0.3, 3), ey1, ez1], "cut"))
    boxes.append(Box([round(8 + half - 0.3, 3), ey0, ez0], [round(8 + half + 0.12, 3), ey1, ez1], "cut"))
    return boxes


def bishop():
    """Measured from the bishop reference: base, gold plinth, step, shaft, gold band and neck; the
    mitre is a block whose front-top edge is cut at 45 degrees, with a recessed slit parallel to the
    cut on both sides, a narrower cap and a knob on top."""
    total_px = 40 + 36 + 37 + 74 + 42 + 25 + 99 + 36
    sc = 13.5 / total_px
    boxes, y = stack([(40, 210, "body"), (36, 165, "accent"), (37, 120, "body"), (74, 84, "body"),
                      (42, 118, "accent"), (25, 96, "body")], sc)
    w = 126 * sc * SLIM
    hw = w / 2
    h0, h1 = y, y + 99 * sc
    cut = (h1 - h0) * 0.55               # vertical (and horizontal) size of the 45-degree cut
    zf, zb = 8 - hw, 8 + hw              # front (north) and back faces
    ym, zm = h1 - cut, zf + cut
    boxes.append(Box([8 - hw, h0, zf], [8 + hw, ym, zb], "body"))          # lower block
    boxes.append(Box([8 - hw, ym, zm], [8 + hw, h1, zb], "body"))          # upper back block
    # Rotated square whose outer edge is the cut line from (zf, ym) to (zm, h1).
    side = cut * math.sqrt(2)
    mid = np.array([8.0, (ym + h1) / 2, (zf + zm) / 2])
    inward = np.array([0.0, -1.0, 1.0]) / math.sqrt(2)
    centre = mid + inward * side / 2
    half = side / 2 - 0.001
    boxes.append(Box([8 - hw + 0.002, centre[1] - half, centre[2] - half], [8 + hw - 0.002, centre[1] + half, centre[2] + half],
                     "body", rot=(rot_axis("x", 45), centre)))
    # Slit parallel to the cut, on both side faces.
    slit_c = mid + inward * cut * 0.62
    length, width = cut * 1.05, 0.5
    for x0, x1 in ((8 - hw - 0.1, 8 - hw + 0.4), (8 + hw - 0.4, 8 + hw + 0.1)):
        boxes.append(Box([x0, slit_c[1] - width / 2, slit_c[2] - length / 2], [x1, slit_c[1] + width / 2, slit_c[2] + length / 2],
                         "cut", rot=(rot_axis("x", -45), slit_c)))
    # Cap and knob, centred over the upper back block.
    cz = (zm + zb) / 2
    cap_w, cap_h = 67 * sc * SLIM, 14 * sc
    knob_w = 42 * sc * SLIM
    boxes.append(Box([8 - cap_w / 2, h1, cz - cap_w / 2], [8 + cap_w / 2, h1 + cap_h, cz + cap_w / 2], "body"))
    top = h1 + cap_h
    boxes.append(Box([8 - knob_w / 2, top, cz - knob_w / 2], [8 + knob_w / 2, top + 30 * sc, cz + knob_w / 2], "body"))
    return boxes


def queen():
    """Measured from the queen reference: base, step, gold plinth, step, tapering shaft, rings and
    gold band, crown platform with four corner prongs, a gold block and a round ball on top."""
    spec = [(38, 226, "body"), (33, 205, "body"), (38, 150, "accent"), (33, 122, "body"),
            (34, 81, "body"), (35, 76, "body"), (34, 71, "body"),
            (13, 101, "body"), (35, 111, "accent"), (11, 82, "body"), (20, 105, "body"), (21, 134, "body")]
    total_px = sum(t[0] for t in spec) + 22 + 44
    sc = 15.5 / total_px
    boxes, y = stack(spec, sc)
    boxes += corner_blocks(y, 22 * sc, 134 * sc * SLIM, 26 * sc * SLIM, "body")
    boxes.append(square(round(y, 3), round(y + 22 * sc, 3), round(60 * sc * SLIM, 3), "accent"))
    boxes += ball(round(y + 22 * sc, 3), 22 * sc * 0.9, "body", layers=12)
    return boxes


def king():
    """No reference was given: designed in the same style as the queen, crowned with a gold cross."""
    spec = [(42, 260, "body"), (40, 228, "accent"), (34, 200, "body"), (34, 140, "body"),
            (37, 90, "body"), (37, 84, "body"), (36, 78, "body"),
            (14, 110, "body"), (38, 122, "accent"), (12, 88, "body"), (40, 146, "body"),
            (14, 156, "accent"), (22, 112, "body")]
    total_px = sum(t[0] for t in spec) + 62
    sc = 16.0 / total_px
    boxes, y = stack(spec, sc)
    post_w, post_h = 28 * sc * SLIM, 62 * sc
    boxes.append(square(round(y, 3), round(y + post_h, 3), round(post_w, 3), "accent"))
    arm_y = y + post_h * 0.48
    arm_w, arm_h, arm_d = 84 * sc * SLIM, 20 * sc, 22 * sc * SLIM
    boxes.append(Box([round(8 - arm_w / 2, 3), round(arm_y, 3), round(8 - arm_d / 2, 3)],
                     [round(8 + arm_w / 2, 3), round(arm_y + arm_h, 3), round(8 + arm_d / 2, 3)], "accent"))
    return boxes


KNIGHT_DEPTH = 1.0


def with_slim(fn, k):
    """Builds a piece with SLIM scaled by k (per-piece width match against its reference)."""
    def build():
        global SLIM
        old = SLIM
        SLIM = old * k
        try:
            return fn()
        finally:
            SLIM = old
    build.__name__ = fn.__name__
    return build


# Width factors measured by comparing each render's height/width ratio with its reference image.
SHAPES = {"pawn": pawn, "knight": with_slim(knight, 0.885), "bishop": with_slim(bishop, 0.98),
          "rook": with_slim(rook, 1.063), "queen": with_slim(queen, 0.888), "king": with_slim(king, 0.9)}


FACE_SEED = {"north": 1, "south": 2, "east": 3, "west": 4, "up": 5, "down": 6}


def face_dims(box, face):
    (x0, y0, z0), (x1, y1, z1) = box.frm, box.to
    if face in ("north", "south"):
        return x1 - x0, y1 - y0
    if face in ("east", "west"):
        return z1 - z0, y1 - y0
    return x1 - x0, z1 - z0


def face_uv(box, face, idx):
    mat = "felt" if (face == "down" and box.felt_bottom) else box.mat
    u0, v0, u1, v1 = REGIONS[mat]
    w, h = face_dims(box, face)
    rw, rh = u1 - u0, v1 - v0
    w, h = min(w, rw), min(h, rh)
    rnd = random.Random(idx * 31 + FACE_SEED[face])
    ox = round(rnd.uniform(0, rw - w) * 4) / 4
    oy = round(rnd.uniform(0, rh - h) * 4) / 4
    return [round(u0 + ox, 3), round(v0 + oy, 3), round(u0 + ox + w, 3), round(v0 + oy + h, 3)]


def element_json(box, idx, angle=0.0):
    faces = {}
    for f in box.faces:
        faces[f] = {"uv": face_uv(box, f, idx), "texture": "#piece"}
    if box.rot is None:
        el = {"from": [round(v, 4) for v in box.frm], "to": [round(v, 4) for v in box.to], "faces": faces}
        if angle:
            el["rotation"] = {"origin": [8, 8, 8], "axis": "y", "angle": angle}
        return el
    own, origin = box.rot
    o = np.array(origin, dtype=float)
    c = np.array([8.0, 8.0, 8.0])
    yaw = rot_axis("y", angle)
    r = yaw @ own
    # Shift the box so that rotating about its own origin reproduces "own rotation, then yaw about the centre".
    d = r.T @ (yaw @ (o - c) + c - o)
    frm = [round(v + dv, 4) for v, dv in zip(box.frm, d)]
    to = [round(v + dv, 4) for v, dv in zip(box.to, d)]
    ry = math.degrees(math.asin(max(-1.0, min(1.0, -r[2, 0]))))
    rx = math.degrees(math.atan2(r[2, 1], r[2, 2]))
    rz = math.degrees(math.atan2(r[1, 0], r[0, 0]))
    el = {"from": frm, "to": to, "faces": faces,
          "rotation": {"origin": [round(v, 4) for v in o], "x": round(rx, 4), "y": round(ry, 4), "z": round(rz, 4)}}
    return el


PIECE_DISPLAY = {
    "gui": {"rotation": [25, 210, 0], "translation": [0, -0.75, 0], "scale": [0.72, 0.72, 0.72]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.8, 0.8, 0.8]},
    "thirdperson_righthand": {"rotation": [75, 315, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 315, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 315, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 315, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}


def piece_assets():
    for color in COLORS:
        piece_texture(color).save(out(os.path.join(ASSETS, "textures", "block", f"piece_{color}.png")))
    for color in COLORS:
        for t in TYPES:
            name = f"{color}_{t}"
            boxes = SHAPES[t]()
            # Four variants: 0, 22.5, 45 and 67.5 degrees clockwise; quarter turns come from the blockstate.
            for j in range(4):
                model = {
                    "parent": "minecraft:block/block",
                    "textures": {"piece": f"{NS}:block/piece_{color}", "particle": f"{NS}:block/piece_{color}"},
                    "elements": [element_json(b, i, -22.5 * j) for i, b in enumerate(boxes)],
                }
                if j == 0:
                    model["display"] = PIECE_DISPLAY
                suffix = "" if j == 0 else f"_r{j}"
                write_json(os.path.join(ASSETS, "models", "block", f"{name}{suffix}.json"), model)
            variants = {}
            for r in range(16):
                k, j = divmod(r, 4)
                v = {"model": f"{NS}:block/{name}" + ("" if j == 0 else f"_r{j}")}
                if k:
                    v["y"] = 90 * k
                variants[f"rotation={r}"] = v
            write_json(os.path.join(ASSETS, "blockstates", f"{name}.json"), {"variants": variants})
            write_json(os.path.join(ASSETS, "items", f"{name}.json"),
                       {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
            simple_loot(name)


# --------------------------------------------------------------------------- board

LABEL_FONT = "C:/Windows/Fonts/georgiab.ttf"


def _label(ch, size, color):
    """One crisp board coordinate (serif bold) on a transparent tile, with a soft dark shadow."""
    from PIL import ImageFont
    font = ImageFont.truetype(LABEL_FONT, size)
    tile = Image.new("RGBA", (size * 2, size * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    x0, y0, x1, y1 = d.textbbox((0, 0), ch, font=font)
    pos = (size - (x0 + x1) / 2, size - (y0 + y1) / 2)
    shadow = Image.new("RGBA", tile.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((pos[0] + 1.5, pos[1] + 1.5), ch, font=font, fill=(30, 12, 4, 200))
    shadow = shadow.filter(ImageFilter.GaussianBlur(1.2))
    shadow.alpha_composite(tile)
    ImageDraw.Draw(shadow).text(pos, ch, font=font, fill=color)
    return shadow


def board_top():
    """384x384 texture for the 48x48 unit board (8 px per unit): 4-unit frame, 8x8 squares of
    5 units, gold inlay and serif coordinates A-H / 1-8 (mirrored on the far sides for Black)."""
    size, frame, sq = 384, 32, 40
    img = wood(size, size, FRAME[0], FRAME[1], 41, vertical=False, rings=5.0, contrast=0.7)
    light = wood(sq * 8, sq * 8, LIGHT_SQ[0], LIGHT_SQ[1], 42, vertical=True, rings=6.0, contrast=0.45)
    dark = wood(sq * 8, sq * 8, DARK_SQ[0], DARK_SQ[1], 43, vertical=True, rings=6.0, contrast=0.5)
    for rank in range(8):
        for file in range(8):
            # Image row 0 is north (black's side); a1 (dark) sits bottom-left.
            x = frame + file * sq
            y = frame + (7 - rank) * sq
            src = dark if (file + rank) % 2 == 0 else light
            img.paste(src.crop((file * sq, rank * sq, file * sq + sq, rank * sq + sq)), (x, y))
    d = ImageDraw.Draw(img)
    gold = (226, 182, 92, 255)
    d.rectangle([frame - 3, frame - 3, size - frame + 2, size - frame + 2], outline=gold, width=3)
    d.rectangle([2, 2, size - 3, size - 3], outline=(70, 32, 16, 255), width=3)
    color = (242, 224, 186, 255)
    for i in range(8):
        for text, cx, cy, flip in [
            ("ABCDEFGH"[i], frame + i * sq + sq // 2, size - frame // 2, False),
            ("ABCDEFGH"[i], frame + i * sq + sq // 2, frame // 2, True),
            ("12345678"[i], frame // 2, frame + (7 - i) * sq + sq // 2, False),
            ("12345678"[i], size - frame // 2, frame + (7 - i) * sq + sq // 2, True),
        ]:
            tile = _label(text, 22, color)
            if flip:
                tile = tile.rotate(180)
            img.alpha_composite(tile, (cx - tile.size[0] // 2, cy - tile.size[1] // 2))
    return img


def board_assets():
    tex = os.path.join(ASSETS, "textures", "block")
    board_top().save(out(os.path.join(tex, "board_top.png")))
    wood(32, 32, FRAME[0], FRAME[1], 44, vertical=False, rings=4.0, contrast=0.7).save(out(os.path.join(tex, "board_side.png")))
    wood(32, 32, TABLE[0], TABLE[1], 45, vertical=True, rings=3.0, contrast=0.6).save(out(os.path.join(tex, "table_wood.png")))
    metal(16, 16, BRASS[0], BRASS[1], 46).save(out(os.path.join(tex, "brass.png")))

    def u(x):
        return round((x + 16) / 48 * 16, 4)

    def side_faces(w, h):
        return {"uv": [0, 0, min(16, w / 3), min(16, h)], "texture": "#side"}

    els = []
    # Main slab: top shows the whole printed board.
    els.append({
        "from": [-16, 0, -16], "to": [32, 2.5, 32],
        "faces": {
            "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
            "down": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "north": side_faces(48, 2.5), "south": side_faces(48, 2.5),
            "east": side_faces(48, 2.5), "west": side_faces(48, 2.5),
        },
    })
    # Raised frame strips (top faces reuse the matching part of the printed board).
    strips = [
        ([-12, 2.5, -16], [28, 3.25, -12]),
        ([-12, 2.5, 28], [28, 3.25, 32]),
        ([-16, 2.5, -12], [-12, 3.25, 28]),
        ([28, 2.5, -12], [32, 3.25, 28]),
    ]
    for frm, to in strips:
        faces = {"up": {"uv": [u(frm[0]), u(frm[2]), u(to[0]), u(to[2])], "texture": "#top"}}
        for f in ("north", "south", "east", "west"):
            w = (to[0] - frm[0]) if f in ("north", "south") else (to[2] - frm[2])
            faces[f] = side_faces(w, 0.75)
        els.append({"from": frm, "to": to, "faces": faces})
    for x0, z0 in [(-16, -16), (28, -16), (-16, 28), (28, 28)]:
        faces = {f: {"uv": [0, 0, 4, 4] if f in ("up", "down") else [0, 0, 4, 1], "texture": "#brass"}
                 for f in ("north", "south", "east", "west", "up")}
        els.append({"from": [x0, 2.5, z0], "to": [x0 + 4, 3.5, z0 + 4], "faces": faces})

    board_model = {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {"top": f"{NS}:block/board_top", "side": f"{NS}:block/board_side",
                     "brass": f"{NS}:block/brass", "particle": f"{NS}:block/board_side"},
        "elements": els,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.24, 0.24, 0.24]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.12, 0.12, 0.12]},
            "fixed": {"rotation": [-90, 0, 0], "translation": [0, 0, -2], "scale": [0.3, 0.3, 0.3]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.14, 0.14, 0.14]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.14, 0.14, 0.14]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.16, 0.16, 0.16]},
            "firstperson_lefthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.16, 0.16, 0.16]},
        },
    }
    write_json(os.path.join(ASSETS, "models", "block", "chess_board.json"), board_model)
    write_json(os.path.join(ASSETS, "models", "block", "chess_board_part.json"),
               {"textures": {"particle": f"{NS}:block/board_side"}, "elements": []})

    facings = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for facing, y in facings.items():
        for part in range(9):
            model = "chess_board" if part == 4 else "chess_board_part"
            v = {"model": f"{NS}:block/{model}"}
            if part == 4 and y:
                v["y"] = y
            variants[f"facing={facing},part={part}"] = v
    write_json(os.path.join(ASSETS, "blockstates", "chess_board.json"), {"variants": variants})
    write_json(os.path.join(ASSETS, "items", "chess_board.json"),
               {"model": {"type": "minecraft:model", "model": f"{NS}:block/chess_board"}})
    write_json(os.path.join(DATA, "loot_table", "blocks", "chess_board.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:chess_board"}],
            "conditions": [
                {"condition": "minecraft:survives_explosion"},
                {"condition": "minecraft:block_state_property", "block": f"{NS}:chess_board",
                 "properties": {"part": "4"}},
            ],
        }],
    })

    # Table base: plinth, octagonal column with brass rings, capital and top plate.
    def box(frm, to, tex, faces=("north", "south", "east", "west", "up", "down")):
        fs = {}
        for f in faces:
            if f in ("up", "down"):
                uv = [frm[0], frm[2], to[0], to[2]]
            elif f in ("north", "south"):
                uv = [frm[0], 16 - to[1], to[0], 16 - frm[1]]
            else:
                uv = [frm[2], 16 - to[1], to[2], 16 - frm[1]]
            fs[f] = {"uv": [round(v, 3) for v in uv], "texture": tex}
        return {"from": frm, "to": to, "faces": fs}

    base_els = [
        box([1, 0, 1], [15, 1, 15], "#wood"),
        box([2, 1, 2], [14, 2, 14], "#wood"),
        box([4.5, 2, 4.5], [11.5, 2.75, 11.5], "#brass"),
        box([5, 2.75, 6], [11, 13, 10], "#wood"),
        box([6, 2.75, 5], [10, 13, 6], "#wood", ("north", "east", "west", "up", "down")),
        box([6, 2.75, 10], [10, 13, 11], "#wood", ("south", "east", "west", "up", "down")),
        box([4.5, 7.5, 4.5], [11.5, 8.25, 11.5], "#brass"),
        box([4, 13, 4], [12, 14, 12], "#brass"),
        box([2, 14, 2], [14, 16, 14], "#wood"),
    ]
    write_json(os.path.join(ASSETS, "models", "block", "chess_table_base.json"), {
        "parent": "minecraft:block/block",
        "textures": {"wood": f"{NS}:block/table_wood", "brass": f"{NS}:block/brass",
                     "particle": f"{NS}:block/table_wood"},
        "elements": base_els,
    })
    write_json(os.path.join(ASSETS, "blockstates", "chess_table_base.json"),
               {"variants": {"": {"model": f"{NS}:block/chess_table_base"}}})
    write_json(os.path.join(ASSETS, "items", "chess_table_base.json"),
               {"model": {"type": "minecraft:model", "model": f"{NS}:block/chess_table_base"}})
    simple_loot("chess_table_base")


def simple_loot(name):
    write_json(os.path.join(DATA, "loot_table", "blocks", f"{name}.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


# --------------------------------------------------------------------------- sprite rasterizer

FACE_NORMALS = {
    "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0),
    "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0),
}


def face_corners(box, face):
    (x0, y0, z0), (x1, y1, z1) = box.frm, box.to
    # Corners ordered so UV (0,0) maps to the first corner, (1,0) second, (1,1) third.
    if face == "north":
        return [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)]
    if face == "south":
        return [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)]
    if face == "east":
        return [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)]
    if face == "west":
        return [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)]
    if face == "up":
        return [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)]
    return [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)]


def render_sprite(boxes, texture, size=64, ss=4, yaw=-62.0, pitch=-15.0):
    """Orthographic z-buffer render of a piece model, supersampled for anti-aliasing."""
    tex = np.asarray(texture.convert("RGBA"), dtype=np.float32)
    th, tw = tex.shape[:2]
    W = size * ss
    color = np.zeros((W, W, 3), np.float32)
    alpha = np.zeros((W, W), np.float32)
    depth = np.full((W, W), np.inf, np.float32)

    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        return x, y, z

    light = np.array([-0.45, 0.8, -0.4])
    light /= np.linalg.norm(light)
    scale = W / 18.5
    for idx, b in enumerate(boxes):
        for face in b.faces:
            n = FACE_NORMALS[face]
            if b.rot is not None:
                n = tuple(b.rot[0] @ np.array(n, dtype=float))
            nv = view((n[0] + 8, n[1] + 8, n[2] + 8))
            if nv[2] >= 0:  # facing away from the camera (camera looks along +z)
                continue
            shade = min(1.12, 0.74 + 0.42 * max(0.0, float(np.dot(light, np.array(n, dtype=float)))))
            uv = face_uv(b, face, idx)
            corners = face_corners(b, face)
            if b.rot is not None:
                m, o = b.rot
                corners = [tuple(m @ (np.array(cc, dtype=float) - o) + o) for cc in corners]
            pts = [view(cc) for cc in corners]
            sx = np.array([W / 2 + p[0] * scale for p in pts])
            sy2 = np.array([W * 0.53 - p[1] * scale for p in pts])
            sz = np.array([p[2] for p in pts])
            u0, v0, u1, v1 = [v / 16.0 for v in uv]
            uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            for tri in ((0, 1, 2), (0, 2, 3)):
                xs, ys, zs = sx[list(tri)], sy2[list(tri)], sz[list(tri)]
                minx, maxx = int(max(0, math.floor(xs.min()))), int(min(W - 1, math.ceil(xs.max())))
                miny, maxy = int(max(0, math.floor(ys.min()))), int(min(W - 1, math.ceil(ys.max())))
                if minx > maxx or miny > maxy:
                    continue
                gx, gy = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
                d = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
                if abs(d) < 1e-9:
                    continue
                w0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / d
                w1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / d
                w2 = 1 - w0 - w1
                inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
                if not inside.any():
                    continue
                z = w0 * zs[0] + w1 * zs[1] + w2 * zs[2]
                sub = depth[miny:maxy + 1, minx:maxx + 1]
                closer = inside & (z < sub)
                if not closer.any():
                    continue
                tu = w0 * uvs[tri[0]][0] + w1 * uvs[tri[1]][0] + w2 * uvs[tri[2]][0]
                tv = w0 * uvs[tri[0]][1] + w1 * uvs[tri[1]][1] + w2 * uvs[tri[2]][1]
                px = np.clip((tu * tw).astype(int), 0, tw - 1)
                py = np.clip((tv * th).astype(int), 0, th - 1)
                rgb = tex[py, px, :3] * shade
                sub[closer] = z[closer]
                color[miny:maxy + 1, minx:maxx + 1][closer] = rgb[closer]
                alpha[miny:maxy + 1, minx:maxx + 1][closer] = 1.0

    rgba = np.dstack([np.clip(color, 0, 255), alpha * 255]).astype(np.uint8)
    img = Image.fromarray(rgba, "RGBA")
    # Dark outline for readability on both square colours.
    a = img.split()[3]
    outline = Image.new("RGBA", img.size, (20, 12, 8, 0))
    grown = a.filter(ImageFilter.MaxFilter(ss * 2 + 1))
    outline.putalpha(grown.point(lambda v: 200 if v > 0 else 0))
    outline.alpha_composite(img)
    return outline.resize((size, size), Image.LANCZOS)


def sprite_assets():
    gui = os.path.join(ASSETS, "textures", "gui")
    textures = {c: piece_texture(c) for c in COLORS}
    # Screen pieces: smooth 2D website-style sprites, linearly filtered when scaled.
    for color in COLORS:
        for t in TYPES:
            path = os.path.join(gui, "pieces", f"{color}_{t}.png")
            gui_pieces.SHAPES[t](color).save(out(path))
            write_json(path + ".mcmeta", {"texture": {"blur": True}})
    wood(32, 32, LIGHT_SQ[0], LIGHT_SQ[1], 42, vertical=True, rings=3.0, contrast=0.45).save(out(os.path.join(gui, "square_light.png")))
    wood(32, 32, DARK_SQ[0], DARK_SQ[1], 43, vertical=True, rings=3.0, contrast=0.5).save(out(os.path.join(gui, "square_dark.png")))
    wood(64, 64, FRAME[0], FRAME[1], 47, vertical=False, rings=4.0, contrast=0.7).save(out(os.path.join(gui, "frame_wood.png")))

    # Rounded UI: a white disc (corners/circles) and white icons, tinted in game.
    import gui_icons
    ui = os.path.join(gui, "ui")
    gui_icons.round_shape().save(out(os.path.join(ui, "round.png")))
    write_json(os.path.join(ui, "round.png.mcmeta"), {"texture": {"blur": True, "clamp": True}})
    for name in gui_icons.BADGES:
        path = os.path.join(ui, "badges", name + ".png")
        gui_icons.badge(name).save(out(path))
        write_json(path + ".mcmeta", {"texture": {"blur": True, "clamp": True}})
    gui_icons.arrowhead().save(out(os.path.join(ui, "arrowhead.png")))
    write_json(os.path.join(ui, "arrowhead.png.mcmeta"), {"texture": {"blur": True, "clamp": True}})
    for name in gui_icons.ICONS:
        path = os.path.join(ui, "icons", name + ".png")
        gui_icons.icon(name).save(out(path))
        write_json(path + ".mcmeta", {"texture": {"blur": True, "clamp": True}})

    # Legal-move markers: a soft round dot for quiet moves and a ring for captures (anti-aliased).
    ss = 8
    dot = Image.new("RGBA", (64 * ss, 64 * ss), (0, 0, 0, 0))
    ImageDraw.Draw(dot).ellipse([20 * ss, 20 * ss, 44 * ss, 44 * ss], fill=(24, 18, 12, 110))
    dot.resize((64, 64), Image.LANCZOS).save(out(os.path.join(gui, "move_dot.png")))
    ring = Image.new("RGBA", (64 * ss, 64 * ss), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse([2 * ss, 2 * ss, 62 * ss, 62 * ss], outline=(24, 18, 12, 110), width=6 * ss)
    ring.resize((64, 64), Image.LANCZOS).save(out(os.path.join(gui, "capture_ring.png")))
    for name in ("move_dot", "capture_ring"):
        write_json(os.path.join(gui, name + ".png.mcmeta"), {"texture": {"blur": True}})

    # Mod icon: king and queen on a board corner.
    icon = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    bg = board_top().resize((128, 128), Image.NEAREST)
    icon.alpha_composite(bg)
    k = render_sprite(king(), textures["white"], size=96, yaw=-30)
    q = render_sprite(queen(), textures["black"], size=84, yaw=-30)
    icon.alpha_composite(q, (40, 26))
    icon.alpha_composite(k, (0, 14))
    icon.save(out(os.path.join(ASSETS, "icon.png")))


def lang():
    names = {
        "block.chesscraft.chess_table_base": "Chess Table Base",
        "block.chesscraft.chess_board": "Chess Board",
        "itemGroup.chesscraft": "ChessCraft",
    }
    for c in COLORS:
        for t in TYPES:
            names[f"block.chesscraft.{c}_{t}"] = f"{c.capitalize()} {t.capitalize()}"
    write_json(os.path.join(ASSETS, "lang", "en_us.json"), names)


if __name__ == "__main__":
    piece_assets()
    board_assets()
    sprite_assets()
    lang()
    print("assets generated")
