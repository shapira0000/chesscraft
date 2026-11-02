"""Smooth 2D chess piece sprites for the chess screen (website style: round shapes, ivory/ebony
with gold rings). Pieces are drawn on a 100x100 design grid at 8x resolution and downsampled."""
import math

from PIL import Image, ImageDraw, ImageFilter

SS = 1024  # working canvas size
U = SS / 100.0

PALETTES = {
    "white": {
        "body": (240, 233, 219), "plate": (222, 214, 200), "shade": (204, 193, 174),
        "line": (150, 132, 108), "outline": (58, 44, 34), "cut": (205, 193, 172),
    },
    "black": {
        "body": (66, 60, 58), "plate": (84, 77, 74), "shade": (44, 40, 39),
        "line": (28, 24, 23), "outline": (16, 12, 10), "cut": (34, 30, 29),
    },
}
GOLD = (236, 190, 78)
GOLD_LIGHT = (252, 218, 128)
GOLD_LINE = (176, 128, 40)


def p(x, y):
    return (x * U, y * U)


class Painter:
    def __init__(self, color):
        self.pal = PALETTES[color]
        self.img = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def rrect(self, x0, y0, x1, y1, r, fill, line=None):
        self.d.rounded_rectangle([p(x0, y0), p(x1, y1)], radius=r * U, fill=fill + (255,),
                                 outline=(line + (255,)) if line else None, width=int(0.55 * U))

    def ellipse(self, cx, cy, rx, ry, fill, line=None):
        self.d.ellipse([p(cx - rx, cy - ry), p(cx + rx, cy + ry)], fill=fill + (255,),
                       outline=(line + (255,)) if line else None, width=int(0.55 * U))

    def poly(self, pts, fill, line=None):
        pp = [p(x, y) for x, y in pts]
        self.d.polygon(pp, fill=fill + (255,))
        if line:
            self.d.line(pp + [pp[0]], fill=line + (255,), width=int(0.55 * U), joint="curve")

    def cut(self, pts, width):
        """Carves a slit (drawn in a darker body tone)."""
        self.d.line([p(x, y) for x, y in pts], fill=self.pal["cut"] + (255,), width=int(width * U))

    def gold_ring(self, x0, y0, x1, y1, r=2.2):
        self.rrect(x0, y0, x1, y1, r, GOLD, GOLD_LINE)
        h = (y1 - y0) * 0.32
        self.rrect(x0 + 1.2, y0 + 0.7, x1 - 1.2, y0 + 0.7 + h, r * 0.6, GOLD_LIGHT)

    def stem(self, y_top, hw_top, y_bot, hw_bot, power=2.2):
        """Concave flaring column between two heights."""
        n = 40
        left, right = [], []
        for i in range(n + 1):
            t = i / n
            y = y_top + (y_bot - y_top) * t
            hw = hw_top + (hw_bot - hw_top) * (t ** power)
            left.append((50 - hw, y))
            right.append((50 + hw, y))
        self.poly(left + right[::-1], self.pal["body"], self.pal["line"])

    def base(self, w=1.0):
        """Bottom plate, gold band and thin plate (as in the reference pawn)."""
        pal = self.pal
        self.rrect(50 - 35 * w, 86, 50 + 35 * w, 96, 3, pal["body"], pal["line"])
        self.rrect(50 - 34 * w, 91.6, 50 + 34 * w, 95.4, 2.4, pal["shade"])
        self.gold_ring(50 - 31 * w, 79, 50 + 31 * w, 87.5, 2.5)
        self.rrect(50 - 26 * w, 74, 50 + 26 * w, 80, 2, pal["plate"], pal["line"])

    def collar(self, y, hw_gold, hw_plate):
        self.rrect(50 - hw_plate, y + 6, 50 + hw_plate, y + 10.5, 1.6, self.pal["plate"], self.pal["line"])
        self.gold_ring(50 - hw_gold, y, 50 + hw_gold, y + 7, 2.6)

    def finish(self, size=128):
        img = self.img
        # Soft side lighting: brighter on the left, darker on the right.
        shade = Image.new("L", (SS, 1))
        for x in range(SS):
            t = x / (SS - 1)
            shade.putpixel((x, 0), int(255 * (1.07 - 0.22 * t ** 1.3) / 1.07))
        shade = shade.resize((SS, SS))
        r, g, b, a = img.split()
        r = Image.composite(Image.eval(r, lambda v: v), r, a)
        lit = Image.merge("RGB", (r, g, b))
        dark = Image.new("RGB", (SS, SS), (0, 0, 0))
        lit = Image.composite(lit, dark, shade.point(lambda v: min(255, int(v * 1.07))))
        img = Image.merge("RGBA", (*lit.split(), a))
        # Outline for contrast on both square colours.
        mask = a.filter(ImageFilter.MaxFilter(int(1.6 * U) | 1))
        out = Image.new("RGBA", (SS, SS), self.pal["outline"] + (0,))
        out.putalpha(mask.point(lambda v: 235 if v > 0 else 0))
        out.alpha_composite(img)
        # Gentle drop shadow below.
        shadow = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow)
        sd.ellipse([p(16, 92.5), p(84, 99.3)], fill=(0, 0, 0, 70))
        shadow = shadow.filter(ImageFilter.GaussianBlur(U * 1.2))
        shadow.alpha_composite(out)
        return shadow.resize((size, size), Image.LANCZOS)


def pawn(c):
    pt = Painter(c)
    pt.base(0.94)
    pt.stem(46, 9.5, 74, 21)
    pt.collar(39, 19, 13.5)
    pt.ellipse(50, 23.5, 15.5, 15.5, pt.pal["body"], pt.pal["line"])
    return pt.finish()


def rook(c):
    pt = Painter(c)
    pt.base(1.0)
    pt.stem(34, 15, 74, 22, power=1.6)
    pt.gold_ring(30, 28, 70, 35.5, 2.0)
    pal = pt.pal
    pt.rrect(28, 9, 72, 29, 1.6, pal["body"], pal["line"])
    for x0 in (35.5, 56.5):
        pt.d.rectangle([p(x0, 8), p(x0 + 8, 16.5)], fill=(0, 0, 0, 0))
        pt.d.line([p(x0, 16.5), p(x0 + 8, 16.5)], fill=pal["line"] + (255,), width=int(0.55 * U))
        pt.d.line([p(x0, 9), p(x0, 16.5)], fill=pal["line"] + (255,), width=int(0.55 * U))
        pt.d.line([p(x0 + 8, 9), p(x0 + 8, 16.5)], fill=pal["line"] + (255,), width=int(0.55 * U))
    pt.rrect(31, 24, 69, 27.5, 1, pal["shade"])
    return pt.finish()


def knight(c):
    pt = Painter(c)
    pt.base(1.0)
    pal = pt.pal
    pt.rrect(27, 68, 73, 75, 2, pal["plate"], pal["line"])
    head = [
        (71, 69), (74, 58), (74.5, 46), (72, 35), (67, 25), (61, 17.5), (56, 13.5),
        (54, 6), (49.5, 11.5), (46, 6.5), (42.5, 13), (35, 18), (28.5, 25), (22.5, 33),
        (18.5, 40.5), (19.5, 46), (24, 49), (30, 48), (36.5, 45.5), (42, 44.5),
        (40, 52), (34, 59), (30, 64), (29, 69),
    ]
    pt.poly(head, pal["body"], pal["line"])
    # Gold mane along the back of the neck.
    mane = [(56, 14.5), (63, 19.5), (68.5, 27), (72, 37), (73, 49), (72, 60), (70.5, 67)]
    pt.d.line([p(x, y) for x, y in mane], fill=GOLD_LINE + (255,), width=int(4.6 * U), joint="curve")
    pt.d.line([p(x - 0.6, y) for x, y in mane], fill=GOLD + (255,), width=int(3.2 * U), joint="curve")
    eye = (36.5, 24.5)
    pt.ellipse(eye[0], eye[1], 2.3, 2.0, pal["outline"])
    pt.ellipse(eye[0] - 0.6, eye[1] - 0.6, 0.7, 0.7, (255, 255, 255))
    pt.ellipse(23.5, 41.5, 1.4, 1.1, pal["line"])
    pt.cut([(25, 46), (31, 45)], 0.8)
    return pt.finish()


def bishop(c):
    pt = Painter(c)
    pt.base(0.98)
    pt.stem(48, 8.5, 74, 21)
    pt.collar(42, 18.5, 12.5)
    pal = pt.pal
    # Mitre: a rounded teardrop.
    pts = []
    for i in range(60):
        a = math.pi * 2 * i / 60
        x = 50 + 14 * math.sin(a)
        y = 30 - 15 * math.cos(a)
        if y < 30:  # upper half narrows to a point
            k = (30 - y) / 15
            x = 50 + (x - 50) * (1 - 0.55 * k ** 1.6)
            y = 30 - (30 - y) * 1.12
        pts.append((x, y))
    pt.poly(pts, pal["body"], pal["line"])
    pt.cut([(56.5, 21.5), (46.5, 33.5)], 2.2)
    pt.ellipse(50, 11.5, 4.2, 4.2, pal["body"], pal["line"])
    return pt.finish()


def queen(c):
    pt = Painter(c)
    pt.base(1.05)
    pt.stem(44, 9.5, 74, 22.5)
    pt.collar(38, 19.5, 13.5)
    pal = pt.pal
    crown = [(36.5, 39), (28.5, 14), (39, 25), (44.5, 10.5), (50, 23), (55.5, 10.5), (61, 25), (71.5, 14), (63.5, 39)]
    pt.poly(crown, pal["body"], pal["line"])
    pt.gold_ring(34, 31, 66, 37.5, 1.8)
    for x, y in [(28.5, 13), (44.5, 9.5), (55.5, 9.5), (71.5, 13)]:
        pt.ellipse(x, y, 3.3, 3.3, GOLD, GOLD_LINE)
    pt.ellipse(50, 17.5, 3.6, 3.6, GOLD, GOLD_LINE)
    return pt.finish()


def king(c):
    pt = Painter(c)
    pt.base(1.05)
    pt.stem(46, 10, 74, 22.5)
    pt.collar(40, 19.5, 13.5)
    pal = pt.pal
    cup = [(37, 41), (31, 22), (34.5, 17.5), (42, 15.5), (50, 15), (58, 15.5), (65.5, 17.5), (69, 22), (63, 41)]
    pt.poly(cup, pal["body"], pal["line"])
    pt.gold_ring(34.5, 32, 65.5, 38.5, 1.8)
    pt.rrect(46.8, 1.5, 53.2, 17, 1.2, GOLD, GOLD_LINE)
    pt.rrect(41, 5.2, 59, 11, 1.2, GOLD, GOLD_LINE)
    pt.rrect(47.8, 2.4, 49.6, 15.5, 0.6, GOLD_LIGHT)
    return pt.finish()


SHAPES = {"pawn": pawn, "knight": knight, "bishop": bishop, "rook": rook, "queen": queen, "king": king}
