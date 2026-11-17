#!/usr/bin/env python3
"""Draws the mod's original character skins from scratch (no copied pixels).

Usage: python3 tools/make_skins.py
Writes PNGs into src/main/resources/assets/leftbehind/textures/entity/ and a
paper-doll preview sheet into build/skin_preview.png.
"""
import os
import random
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/assets/leftbehind/textures/entity")

# part -> (origin x, origin y, width, height, depth) in the classic 64x64 skin layout
PARTS = {
    "head": (0, 0, 8, 8, 8), "hat": (32, 0, 8, 8, 8),
    "body": (16, 16, 8, 12, 4), "jacket": (16, 32, 8, 12, 4),
    "rarm": (40, 16, 4, 12, 4), "rsleeve": (40, 32, 4, 12, 4),
    "larm": (32, 48, 4, 12, 4), "lsleeve": (48, 48, 4, 12, 4),
    "rleg": (0, 16, 4, 12, 4), "rpants": (0, 32, 4, 12, 4),
    "lleg": (16, 48, 4, 12, 4), "lpants": (0, 48, 4, 12, 4),
}
SIDES = ("top", "bottom", "right", "front", "left", "back")


def box_face(ox, oy, w, h, d, side):
    return {
        "top": (ox + d, oy, w, d), "bottom": (ox + d + w, oy, w, d),
        "right": (ox, oy + d, d, h), "front": (ox + d, oy + d, w, h),
        "left": (ox + d + w, oy + d, d, h), "back": (ox + d + w + d, oy + d, w, h),
    }[side]


class Skin:
    def __init__(self, height=64, seed=1):
        self.img = Image.new("RGBA", (64, height), (0, 0, 0, 0))
        self.rng = random.Random(seed)
        self.extra = {}  # name -> (ox, oy, w, h, d) for extra model boxes

    def face(self, part, side):
        spec = self.extra.get(part) or PARTS[part]
        return box_face(*spec, side)

    def shade(self, color, noise):
        n = self.rng.randint(-noise, noise) if noise else 0
        return tuple(max(0, min(255, c + n)) for c in color[:3]) + ((color[3],) if len(color) > 3 else (255,))

    def px(self, part, side, x, y, color, noise=0):
        fx, fy, fw, fh = self.face(part, side)
        if 0 <= x < fw and 0 <= y < fh:
            self.img.putpixel((fx + x, fy + y), self.shade(color, noise))

    def rect(self, part, side, x0, y0, x1, y1, color, noise=6):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(part, side, x, y, color, noise)

    def fill(self, part, side, color, noise=6):
        _, _, fw, fh = self.face(part, side)
        self.rect(part, side, 0, 0, fw - 1, fh - 1, color, noise)

    def box(self, part, color, noise=6, sides=SIDES):
        for side in sides:
            self.fill(part, side, color, noise)

    def band(self, part, y0, y1, color, noise=6, sides=("right", "front", "left", "back")):
        """A horizontal band around a limb or the body."""
        for side in sides:
            _, _, fw, _ = self.face(part, side)
            self.rect(part, side, 0, y0, fw - 1, y1, color, noise)

    def darken_gradient(self, part, amount):
        """Darkens a part towards its bottom edge."""
        for side in ("right", "front", "left", "back"):
            fx, fy, fw, fh = self.face(part, side)
            for y in range(fh):
                k = 1.0 - amount * (y / max(1, fh - 1))
                for x in range(fw):
                    r, g, b, a = self.img.getpixel((fx + x, fy + y))
                    if a:
                        self.img.putpixel((fx + x, fy + y), (int(r * k), int(g * k), int(b * k), a))

    def save(self, name):
        os.makedirs(OUT, exist_ok=True)
        self.img.save(os.path.join(OUT, name + ".png"))


def limbs(s, part_r, part_l, painter):
    for part in (part_r, part_l):
        painter(part)


# ---------------------------------------------------------------- Herobrine
# Herobrine has no skin of his own: the game draws him with its own Steve skin and the mod covers the eyes
# (see HerobrineRenderer). Only the white eye texture is made here, by eyes_texture().


# ---------------------------------------------------------------- Rana
def rana():
    s = Skin(height=80, seed=23)
    s.extra["frog_eye"] = (0, 64, 3, 3, 3)
    skin, skin_d = (236, 196, 166), (214, 168, 140)
    hair, hair_d = (148, 52, 36), (112, 36, 26)
    hood, hood_d, hood_l = (118, 168, 62), (88, 132, 46), (150, 196, 92)
    cream = (232, 222, 196)
    brown = (110, 70, 44)
    shorts, shorts_d = (176, 64, 96), (140, 46, 74)
    boot, boot_d = (86, 128, 52), (64, 98, 40)
    white = (238, 236, 228)

    s.box("head", skin)
    s.fill("head", "top", hood)
    s.fill("head", "back", hood)
    s.rect("head", "back", 0, 6, 7, 7, hair)
    for side in ("right", "left"):
        s.fill("head", side, hood)
        front_cols = (5, 7) if side == "right" else (0, 2)
        s.rect("head", side, front_cols[0], 3, front_cols[1], 7, hair)
        s.rect("head", side, 0, 6, 7, 7, hair_d)
    s.fill("head", "bottom", skin_d)
    # face
    s.rect("head", "front", 0, 0, 7, 1, hood)
    s.rect("head", "front", 0, 1, 7, 1, hood_l, 3)
    s.rect("head", "front", 0, 2, 7, 2, hair)
    s.rect("head", "front", 2, 2, 5, 2, hair_d, 3)
    for y in range(3, 8):
        s.px("head", "front", 0, y, hair); s.px("head", "front", 7, y, hair)
    s.px("head", "front", 1, 3, hair); s.px("head", "front", 6, 3, hair)
    s.px("head", "front", 2, 4, (250, 250, 250)); s.px("head", "front", 5, 4, (250, 250, 250))
    s.px("head", "front", 1, 4, (70, 120, 60)); s.px("head", "front", 6, 4, (70, 120, 60))
    s.px("head", "front", 1, 5, (238, 160, 150)); s.px("head", "front", 6, 5, (238, 160, 150))   # blush
    s.rect("head", "front", 3, 6, 4, 6, (190, 110, 100), 2)
    # hood overlay: brim and cheeks so the hood stands off the head
    s.fill("hat", "top", hood)
    s.fill("hat", "back", hood)
    s.rect("hat", "back", 0, 6, 7, 7, (0, 0, 0, 0), 0)
    for side in ("right", "left"):
        s.rect("hat", side, 0, 0, 7, 4, hood)
        s.rect("hat", side, 0, 5, 7, 5, hood_d)
        cheek = 6 if side == "right" else 1
        s.rect("hat", side, cheek - 1, 2, cheek, 3, (232, 120, 130), 3)     # pink frog cheek
    s.rect("hat", "front", 0, 0, 7, 0, hood)
    s.rect("hat", "front", 0, 1, 7, 1, hood_d)
    # frog eyes (extra cubes on top of the hood)
    s.box("frog_eye", white, 3)
    s.rect("frog_eye", "front", 1, 1, 1, 1, (20, 20, 24), 0)
    s.px("frog_eye", "front", 1, 0, (20, 20, 24)); s.px("frog_eye", "front", 1, 2, (20, 20, 24))
    s.fill("frog_eye", "back", hood)
    s.fill("frog_eye", "bottom", hood_d)
    s.rect("frog_eye", "top", 0, 0, 2, 0, hood)

    s.box("body", hood)
    s.rect("body", "front", 2, 0, 5, 8, cream)
    s.rect("body", "front", 3, 0, 4, 0, skin_d, 3)
    s.px("body", "front", 1, 3, brown); s.px("body", "front", 6, 3, brown)       # buttons
    s.px("body", "front", 1, 6, brown); s.px("body", "front", 6, 6, brown)
    s.band("body", 9, 9, brown)
    s.rect("body", "front", 3, 9, 4, 9, (190, 190, 196), 3)                        # buckle
    s.band("body", 10, 11, shorts)
    s.rect("body", "front", 0, 10, 1, 11, hood_d); s.rect("body", "front", 6, 10, 7, 11, hood_d)
    s.fill("body", "bottom", shorts_d)

    def arm(part):
        s.box(part, skin)
        s.band(part, 0, 5, hood)
        s.band(part, 5, 5, hood_d)
        s.fill(part, "top", hood)
        s.band(part, 9, 11, white, 4)
        s.band(part, 9, 9, (206, 204, 196), 3)
        s.fill(part, "bottom", white)
    arm("rarm"); arm("larm")

    def leg(part):
        s.box(part, skin)
        s.band(part, 0, 4, shorts)
        s.band(part, 4, 4, shorts_d)
        s.band(part, 7, 7, cream, 4)
        s.band(part, 8, 11, boot)
        s.band(part, 11, 11, boot_d)
        s.fill(part, "top", shorts)
        s.fill(part, "bottom", boot_d)
    leg("rleg"); leg("lleg")
    s.save("rana")
    return s


# ---------------------------------------------------------------- Black Steve
def black_steve():
    s = Skin(height=80, seed=37)
    s.extra["hair_puff"] = (0, 64, 10, 4, 10)
    skin, skin_d = (94, 60, 44), (72, 44, 32)
    hair, hair_d, hair_l = (214, 212, 208), (176, 174, 172), (240, 240, 238)
    robe, robe_d = (150, 34, 32), (116, 24, 26)
    green, green_d = (112, 162, 60), (84, 126, 44)
    shoe = (84, 74, 70)

    s.box("head", skin)
    s.fill("head", "top", hair)
    s.fill("head", "back", hair)
    s.rect("head", "back", 1, 7, 6, 7, skin_d)
    for side in ("right", "left"):
        s.rect("head", side, 0, 0, 7, 3, hair)
        back_cols = (0, 3) if side == "right" else (4, 7)
        s.rect("head", side, back_cols[0], 4, back_cols[1], 5, hair_d)
    s.fill("head", "bottom", skin_d)
    s.rect("head", "front", 0, 0, 7, 1, hair)
    s.px("head", "front", 0, 2, hair); s.px("head", "front", 7, 2, hair)
    s.rect("head", "front", 1, 3, 2, 3, hair_d, 3); s.rect("head", "front", 5, 3, 6, 3, hair_d, 3)   # brows
    s.px("head", "front", 1, 4, (244, 244, 240)); s.px("head", "front", 6, 4, (244, 244, 240))
    s.px("head", "front", 2, 4, (34, 22, 18)); s.px("head", "front", 5, 4, (34, 22, 18))
    s.rect("head", "front", 3, 5, 4, 5, skin_d, 2)
    s.rect("head", "front", 2, 6, 5, 6, (52, 30, 24), 2)
    # white curls: overlay plus a puff of hair above the head
    s.fill("hat", "top", hair_l)
    for side in ("right", "left", "back"):
        s.rect("hat", side, 0, 0, 7, 3, hair)
        for x in range(0, 8, 2):
            s.px("hat", side, x, 4, hair_d)
    s.rect("hat", "front", 0, 0, 7, 1, hair)
    for x in (0, 2, 5, 7):
        s.px("hat", "front", x, 2, hair_d)
    s.box("hair_puff", hair, 10)
    s.fill("hair_puff", "top", hair_l, 8)
    s.fill("hair_puff", "bottom", hair_d, 6)

    s.box("body", robe)
    s.rect("body", "front", 3, 0, 4, 2, green)            # collar opening
    s.px("body", "front", 2, 0, green); s.px("body", "front", 5, 0, green)
    s.rect("body", "front", 3, 0, 4, 0, skin_d, 2)
    s.band("body", 7, 8, green)
    s.band("body", 8, 8, green_d)
    s.rect("body", "front", 3, 9, 4, 11, green, 4)        # sash ends
    s.fill("body", "bottom", robe_d)

    def arm(part):
        s.box(part, robe)
        s.band(part, 8, 9, green)
        s.band(part, 10, 11, skin)
        s.fill(part, "top", robe)
        s.fill(part, "bottom", skin_d)
    arm("rarm"); arm("larm")

    def leg(part):
        s.box(part, robe)
        s.band(part, 7, 7, robe_d)
        s.band(part, 8, 8, green)
        s.band(part, 9, 11, shoe)
        s.band(part, 11, 11, (232, 232, 228), 3)
        s.fill(part, "bottom", (60, 52, 50))
    leg("rleg"); leg("lleg")
    s.save("black_steve")
    return s


# ---------------------------------------------------------------- Beast Boy
def beast_boy():
    s = Skin(height=80, seed=41)
    s.extra["hair_tuft"] = (0, 64, 8, 2, 8)
    skin, skin_d = (132, 180, 78), (102, 148, 58)
    hair, hair_d = (44, 92, 44), (30, 68, 34)
    suit, suit_d = (134, 44, 116), (102, 30, 90)
    belt, belt_l = (104, 150, 52), (150, 190, 80)

    s.box("head", skin)
    s.fill("head", "top", hair)
    s.fill("head", "back", hair)
    s.rect("head", "back", 1, 7, 6, 7, skin_d)
    for side in ("right", "left"):
        s.rect("head", side, 0, 0, 7, 2, hair)
        back_cols = (0, 3) if side == "right" else (4, 7)
        s.rect("head", side, back_cols[0], 3, back_cols[1], 5, hair_d)
    s.fill("head", "bottom", skin_d)
    s.rect("head", "front", 0, 0, 7, 1, hair)
    for x in (0, 1, 3, 6, 7):
        s.px("head", "front", x, 2, hair)
    s.rect("head", "front", 1, 3, 2, 3, hair_d, 2); s.rect("head", "front", 5, 3, 6, 3, hair_d, 2)
    s.px("head", "front", 1, 4, (244, 246, 236)); s.px("head", "front", 6, 4, (244, 246, 236))
    s.px("head", "front", 2, 4, (30, 46, 26)); s.px("head", "front", 5, 4, (30, 46, 26))
    s.rect("head", "front", 3, 5, 4, 5, skin_d, 2)
    s.rect("head", "front", 3, 6, 4, 6, (70, 104, 44), 2)
    for side in ("right", "left", "back", "front"):
        s.rect("hat", side, 0, 0, 7, 1, hair)
        for x in (0, 2, 3, 5, 7):
            s.px("hat", side, x, 2, hair_d)
    s.fill("hat", "top", hair)
    s.box("hair_tuft", hair, 10)
    s.fill("hair_tuft", "top", hair_d, 8)

    s.box("body", suit)
    s.rect("body", "front", 3, 0, 4, 0, skin_d, 2)
    s.band("body", 9, 10, belt)
    s.rect("body", "front", 3, 9, 4, 10, belt_l, 3)
    s.px("body", "front", 3, 10, suit_d); s.px("body", "front", 4, 9, suit_d)
    s.band("body", 11, 11, belt)
    s.fill("body", "bottom", suit_d)

    def arm(part):
        s.box(part, skin)
        s.band(part, 0, 3, suit)
        s.band(part, 3, 3, suit_d)
        s.fill(part, "top", suit)
        s.band(part, 7, 8, belt)
        s.band(part, 9, 11, belt_l, 5)
        s.fill(part, "bottom", belt)
    arm("rarm"); arm("larm")

    def leg(part):
        s.box(part, suit)
        s.band(part, 0, 1, belt)
        s.band(part, 6, 7, belt)
        s.band(part, 8, 11, suit_d)
        s.band(part, 11, 11, (70, 20, 62), 3)
        s.fill(part, "bottom", (70, 20, 62))
    leg("rleg"); leg("lleg")
    s.save("beast_boy")
    return s


def paper_doll(s, scale=8):
    """Front and back views laid flat, to check a skin without the game."""
    def crop(part, side):
        x, y, w, h = s.face(part, side)
        return s.img.crop((x, y, x + w, y + h))
    doll = Image.new("RGBA", (16 * 2 + 4, 34), (40, 44, 52, 255))
    for i, side in enumerate(("front", "back")):
        ox = i * 20
        swap = side == "back"
        doll.alpha_composite(crop("head", side), (ox + 4, 2))
        doll.alpha_composite(crop("hat", side), (ox + 4, 2))
        doll.alpha_composite(crop("body", side), (ox + 4, 10))
        doll.alpha_composite(crop("larm" if swap else "rarm", side), (ox, 10))
        doll.alpha_composite(crop("rarm" if swap else "larm", side), (ox + 12, 10))
        doll.alpha_composite(crop("lleg" if swap else "rleg", side), (ox + 4, 22))
        doll.alpha_composite(crop("rleg" if swap else "lleg", side), (ox + 8, 22))
    return doll.resize((doll.width * scale, doll.height * scale), Image.NEAREST)


def eyes_texture():
    Image.new("RGBA", (8, 8), (255, 255, 255, 255)).save(os.path.join(OUT, "herobrine_eyes.png"))


if __name__ == "__main__":
    skins = [rana(), black_steve(), beast_boy()]
    eyes_texture()
    sheet = Image.new("RGBA", (36 * 8 * len(skins), 34 * 8), (0, 0, 0, 255))
    for i, s in enumerate(skins):
        sheet.alpha_composite(paper_doll(s), (i * 36 * 8, 0))
    os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
    sheet.save(os.path.join(ROOT, "build/skin_preview.png"))
    print("skins written")
