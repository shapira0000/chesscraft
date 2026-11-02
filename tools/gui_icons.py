"""White UI glyphs (tinted in game) and the round shape used for rounded buttons.
Drawn at 4x on a 64-unit grid and downsampled for smooth edges."""
import math

from PIL import Image, ImageDraw, ImageFont

S = 4
N = 64 * S
W = (255, 255, 255, 255)


def _canvas():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def _p(*xy):
    return [v * S for v in xy]


def _done(img, size=64):
    return img.resize((size, size), Image.LANCZOS)


def _arc_arrow(d, cx, cy, r, start, end, width, head_at_end=True):
    d.arc(_p(cx - r, cy - r, cx + r, cy + r), start, end, fill=W, width=width * S)
    ang = math.radians(end if head_at_end else start)
    tx, ty = cx + r * math.cos(ang), cy + r * math.sin(ang)
    direction = ang + (math.pi / 2 if head_at_end else -math.pi / 2)
    size = width * 1.9
    pts = [
        (tx + size * math.cos(direction), ty + size * math.sin(direction)),
        (tx + size * 0.9 * math.cos(direction + 2.3), ty + size * 0.9 * math.sin(direction + 2.3)),
        (tx + size * 0.9 * math.cos(direction - 2.3), ty + size * 0.9 * math.sin(direction - 2.3)),
    ]
    d.polygon([(x * S, y * S) for x, y in pts], fill=W)


def icon(name):
    img, d = _canvas()
    if name == "bot":
        d.rounded_rectangle(_p(12, 18, 52, 52), radius=9 * S, fill=W)
        d.ellipse(_p(20, 28, 29, 37), fill=(0, 0, 0, 0))
        d.ellipse(_p(35, 28, 44, 37), fill=(0, 0, 0, 0))
        d.rounded_rectangle(_p(23, 42, 41, 46), radius=2 * S, fill=(0, 0, 0, 0))
        d.rectangle(_p(30.5, 8, 33.5, 18), fill=W)
        d.ellipse(_p(27, 4, 37, 13), fill=W)
        d.rounded_rectangle(_p(6, 28, 12, 42), radius=2 * S, fill=W)
        d.rounded_rectangle(_p(52, 28, 58, 42), radius=2 * S, fill=W)
    elif name == "friend":
        d.ellipse(_p(10, 10, 28, 28), fill=W)
        d.rounded_rectangle(_p(4, 32, 34, 56), radius=12 * S, fill=W)
        d.ellipse(_p(36, 14, 52, 30), fill=W)
        d.rounded_rectangle(_p(31, 34, 60, 56), radius=11 * S, fill=W)
    elif name == "local":
        d.rounded_rectangle(_p(6, 10, 58, 44), radius=5 * S, fill=W)
        d.rounded_rectangle(_p(11, 15, 53, 39), radius=2 * S, fill=(0, 0, 0, 0))
        d.rectangle(_p(28, 44, 36, 52), fill=W)
        d.rounded_rectangle(_p(18, 51, 46, 56), radius=2 * S, fill=W)
        d.ellipse(_p(19, 20, 27, 28), fill=W)
        d.ellipse(_p(37, 20, 45, 28), fill=W)
    elif name == "watch":
        d.ellipse(_p(4, 16, 60, 48), fill=W)
        d.ellipse(_p(9, 20, 55, 44), fill=(0, 0, 0, 0))
        d.ellipse(_p(22, 22, 42, 42), fill=W)
        d.ellipse(_p(28, 27, 34, 33), fill=(0, 0, 0, 0))
    elif name in ("clock", "rapid"):
        d.ellipse(_p(6, 8, 58, 60), outline=W, width=6 * S)
        d.line(_p(32, 34, 32, 18), fill=W, width=5 * S)
        d.line(_p(32, 34, 43, 41), fill=W, width=5 * S)
        if name == "rapid":
            d.rounded_rectangle(_p(26, 1, 38, 7), radius=2 * S, fill=W)
    elif name == "bullet":
        d.rounded_rectangle(_p(8, 22, 44, 42), radius=3 * S, fill=W)
        d.pieslice(_p(30, 22, 58, 42), -90, 90, fill=W)
        d.rectangle(_p(18, 22, 21, 42), fill=(0, 0, 0, 0))
    elif name == "blitz":
        d.polygon(_p(36, 2, 12, 36, 30, 36, 24, 62, 52, 24, 34, 24, 44, 2), fill=W)
    elif name == "classical":
        d.polygon(_p(14, 6, 50, 6, 32, 32), fill=W)
        d.polygon(_p(14, 58, 50, 58, 32, 32), fill=W)
        d.rectangle(_p(10, 3, 54, 8), fill=W)
        d.rectangle(_p(10, 56, 54, 61), fill=W)
    elif name == "gear":
        cx = cy = 32
        pts = []
        for i in range(16):
            a = math.pi * 2 * i / 16
            r = 28 if i % 2 == 0 else 21
            for da in (-0.13, 0.13):
                pts.append((cx + r * math.cos(a + da), cy + r * math.sin(a + da)))
        d.polygon([(x * S, y * S) for x, y in pts], fill=W)
        d.ellipse(_p(23, 23, 41, 41), fill=(0, 0, 0, 0))
    elif name == "back":
        d.line(_p(40, 10, 18, 32, 40, 54), fill=W, width=8 * S, joint="curve")
    elif name == "chevron":
        d.line(_p(24, 12, 44, 32, 24, 52), fill=W, width=7 * S, joint="curve")
    elif name == "standard":
        for r in range(4):
            for c in range(4):
                if (r + c) % 2 == 0:
                    d.rounded_rectangle(_p(6 + c * 13, 6 + r * 13, 18 + c * 13, 18 + r * 13), radius=2 * S, fill=W)
        d.rounded_rectangle(_p(4, 4, 60, 60), radius=5 * S, outline=W, width=3 * S)
    elif name == "custom":
        d.polygon(_p(44, 6, 58, 20, 22, 56, 8, 56, 8, 42), fill=W)
        d.line(_p(38, 12, 52, 26), fill=(0, 0, 0, 0), width=3 * S)
    elif name == "flip":
        d.polygon(_p(18, 4, 4, 22, 13, 22, 13, 40, 23, 40, 23, 22, 32, 22), fill=W)
        d.polygon(_p(46, 60, 32, 42, 41, 42, 41, 24, 51, 24, 51, 42, 60, 42), fill=W)
    elif name == "undo":
        _arc_arrow(d, 34, 36, 18, 200, 450, 7, head_at_end=False)
    elif name == "rematch":
        _arc_arrow(d, 32, 32, 21, 20, 160, 7)
        _arc_arrow(d, 32, 32, 21, 200, 340, 7)
    elif name == "reset":
        _arc_arrow(d, 32, 34, 21, 300, 600, 7)
    elif name == "hint":
        d.ellipse(_p(14, 4, 50, 40), fill=W)
        d.rounded_rectangle(_p(22, 34, 42, 48), radius=3 * S, fill=W)
        d.rounded_rectangle(_p(23, 51, 41, 56), radius=2 * S, fill=W)
        d.rounded_rectangle(_p(26, 58, 38, 62), radius=2 * S, fill=W)
    elif name == "flag":
        d.rounded_rectangle(_p(10, 4, 16, 62), radius=2 * S, fill=W)
        d.polygon(_p(16, 6, 56, 6, 46, 19, 56, 32, 16, 32), fill=W)
    elif name == "draw":
        font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 54 * S)
        x0, y0, x1, y1 = d.textbbox((0, 0), "\u00bd", font=font)
        d.text(((N - (x0 + x1)) / 2, (N - (y0 + y1)) / 2), "\u00bd", font=font, fill=W)
    elif name == "copy":
        d.rounded_rectangle(_p(20, 4, 58, 46), radius=5 * S, outline=W, width=5 * S)
        d.rounded_rectangle(_p(6, 18, 44, 60), radius=5 * S, fill=W)
    elif name == "crown":
        d.polygon(_p(6, 20, 20, 34, 32, 10, 44, 34, 58, 20, 52, 50, 12, 50), fill=W)
        d.rounded_rectangle(_p(12, 52, 52, 58), radius=2 * S, fill=W)
        for x, y in ((6, 18), (32, 8), (58, 18)):
            d.ellipse(_p(x - 4, y - 4, x + 4, y + 4), fill=W)
    elif name == "close":
        d.line(_p(14, 14, 50, 50), fill=W, width=8 * S)
        d.line(_p(50, 14, 14, 50), fill=W, width=8 * S)
    elif name == "trash":
        d.rounded_rectangle(_p(8, 12, 56, 18), radius=2 * S, fill=W)
        d.rounded_rectangle(_p(24, 5, 40, 12), radius=2 * S, fill=W)
        d.polygon(_p(12, 22, 52, 22, 48, 60, 16, 60), fill=W)
        for x in (24, 32, 40):
            d.line(_p(x, 28, x, 54), fill=(0, 0, 0, 0), width=3 * S)
    elif name == "check":
        d.line(_p(8, 34, 25, 50, 56, 14), fill=W, width=9 * S, joint="curve")
    elif name == "side":
        d.ellipse(_p(6, 6, 58, 58), outline=W, width=5 * S)
        d.pieslice(_p(6, 6, 58, 58), 90, 270, fill=W)
    elif name == "play":
        d.polygon(_p(18, 8, 54, 32, 18, 56), fill=W)
    elif name == "infinity":
        d.ellipse(_p(4, 18, 34, 46), outline=W, width=6 * S)
        d.ellipse(_p(30, 18, 60, 46), outline=W, width=6 * S)
    elif name == "out":
        d.rounded_rectangle(_p(6, 30, 58, 58), radius=5 * S, outline=W, width=5 * S)
        d.polygon(_p(32, 4, 16, 22, 26, 22, 26, 40, 38, 40, 38, 22, 48, 22), fill=W)
    elif name == "review":
        d.ellipse(_p(6, 6, 42, 42), outline=W, width=7 * S)
        d.line(_p(37, 37, 58, 58), fill=W, width=9 * S)
    elif name == "star":
        pts = []
        for i in range(10):
            a = -math.pi / 2 + i * math.pi / 5
            r = 28 if i % 2 == 0 else 12
            pts.append((32 + r * math.cos(a), 34 + r * math.sin(a)))
        d.polygon([(x * S, y * S) for x, y in pts], fill=W)
    elif name == "book":
        d.polygon(_p(6, 12, 30, 18, 30, 58, 6, 52), fill=W)
        d.polygon(_p(58, 12, 34, 18, 34, 58, 58, 52), fill=W)
    elif name == "thumb":
        d.rounded_rectangle(_p(6, 28, 18, 58), radius=3 * S, fill=W)
        d.polygon(_p(22, 28, 34, 6, 42, 10, 38, 26, 56, 26, 58, 34, 52, 58, 22, 58), fill=W)
    else:
        raise ValueError(name)
    return _done(img)


BADGES = {
    # name: (colour, symbol)
    "brilliant": ((38, 194, 163), "!!"),
    "great": ((116, 155, 191), "!"),
    "best": ((129, 182, 76), "star"),
    "excellent": ((129, 182, 76), "thumb"),
    "good": ((149, 183, 118), "check"),
    "book": ((168, 136, 101), "book"),
    "inaccuracy": ((247, 198, 49), "?!"),
    "mistake": ((255, 164, 89), "?"),
    "miss": ((255, 119, 105), "close"),
    "blunder": ((250, 65, 45), "??"),
}


def badge(name):
    """Round classification badge: coloured disc with a white symbol and a soft rim."""
    color, symbol = BADGES[name]
    img, d = _canvas()
    d.ellipse(_p(1, 1, 63, 63), fill=(0, 0, 0, 90))
    d.ellipse(_p(2, 2, 62, 62), fill=color + (255,))
    if len(symbol) <= 2 and not symbol.isalpha():
        font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", (34 if len(symbol) == 2 else 42) * S)
        x0, y0, x1, y1 = d.textbbox((0, 0), symbol, font=font)
        d.text(((N - (x0 + x1)) / 2, (N - (y0 + y1)) / 2), symbol, font=font, fill=W)
    else:
        glyph = icon(symbol).resize((N, N), Image.LANCZOS)
        glyph = glyph.resize((int(N * 0.6), int(N * 0.6)), Image.LANCZOS)
        img.alpha_composite(glyph, (int(N * 0.2), int(N * 0.2)))
    return _done(img)


def arrowhead():
    """White triangle pointing right, used for best-move arrows."""
    img, d = _canvas()
    d.polygon(_p(4, 4, 62, 32, 4, 60), fill=W)
    return _done(img)


ICONS = ["bot", "friend", "local", "watch", "clock", "bullet", "blitz", "rapid", "classical", "gear", "back",
         "chevron", "standard", "custom", "flip", "undo", "rematch", "reset", "hint", "flag", "draw", "copy",
         "crown", "close", "trash", "check", "side", "play", "infinity", "out", "review", "star", "book", "thumb"]


def round_shape(size=128):
    """White anti-aliased disc; quarters of it give rounded corners."""
    img = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    ImageDraw.Draw(img).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=W)
    return img.resize((size, size), Image.LANCZOS)
