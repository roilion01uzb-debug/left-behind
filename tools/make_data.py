#!/usr/bin/env python3
"""Writes the mod's data files: sound list, English text, advancements, the "removed" damage type and screen textures."""
import json
import os

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "leftbehind")
DATA = os.path.join(ROOT, "data")


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------- sounds
SOUNDS = {
    "ambient.dread": (["ambient/dread_1", "ambient/dread_2", "ambient/dread_3"], "Something hums, far below", True),
    "ambient.wind_howl": (["ambient/wind_howl"], "Wind howls", True),
    "ambient.static": (["ambient/static"], "Static", False),
    "ambient.knock": (["ambient/knock"], "Knocking", False),
    "ambient.page": (["ambient/page"], "Page turns", False),
    "herobrine.presence": (["herobrine/presence"], "A presence", False),
    "herobrine.heartbeat": (["herobrine/heartbeat"], "Your heart pounds", False),
    "herobrine.sting": (["herobrine/sting"], "He sees you", False),
    "herobrine.chase": (["herobrine/chase"], "Something is coming", True),
    "herobrine.vanish": (["herobrine/vanish"], "Something vanishes", False),
    "herobrine.whisper": (["herobrine/whisper_1", "herobrine/whisper_2", "herobrine/whisper_3"], "Whispering", False),
    "herobrine.breath": (["herobrine/breath"], "Breathing behind you", False),
    "herobrine.kill": (["herobrine/kill"], "Removed", False),
    "steve.breach": (["steve/breach"], "Steve remembers", False),
    "steve.panic_breath": (["steve/panic_breath"], "Steve gasps", False),
    "steve.mutter": (["steve/mutter_1", "steve/mutter_2"], "Steve mutters", False),
    "steve.talk": (["steve/talk_1", "steve/talk_2", "steve/talk_3", "steve/talk_4"], "Steve speaks", False),
    "ghost.appear": (["ghost/appear"], "Someone is here", False),
    "ghost.hum": (["ghost/hum"], "A faint hum", False),
    "memory.fragment": (["memory/fragment"], "A memory surfaces", False),
    "memory.theme": (["memory/theme"], "An old music box", True),
}

# ---------------------------------------------------------------- advancements
# id: (parent, icon, frame, hidden, title, description)
ADVANCEMENTS = {
    "root": (None, "minecraft:wooden_axe", "task", False, "Left Behind", "Someone was here before you. He still is."),
    "talk": ("root", "minecraft:oak_sign", "task", False, "Small Talk", "Talk to Steve"),
    "journal": ("talk", "minecraft:book", "task", False, "Written Down", "Steve gives you a journal, because he can't keep one"),
    "page": ("journal", "minecraft:paper", "task", False, "Someone Was Here", "Read a torn page"),
    "all_pages": ("page", "minecraft:writable_book", "challenge", False, "Last Entry", "Read every torn page"),
    "gift": ("root", "minecraft:bread", "task", False, "People Used to Be Kind", "Give Steve something to eat"),
    "trusted": ("gift", "minecraft:golden_apple", "goal", False, "Not Like the Others", "Earn Steve's trust"),
    "act_2": ("root", "minecraft:clock", "goal", False, "Act II: Familiar Things", "The things you do have started to mean something to him"),
    "ghost": ("act_2", "minecraft:white_stained_glass", "task", False, "They Don't Walk", "See one of the ones who were removed"),
    "rose": ("ghost", "minecraft:poppy", "task", False, "Roses", "Give Steve a flower that used to have another name"),
    "relic": ("act_2", "minecraft:mossy_cobblestone", "task", False, "Things I Did Not Build", "Find something in the world that nobody built"),
    "zero": ("relic", "minecraft:obsidian", "goal", False, "The Middle", "Stand where everything is counted from"),
    "watched": ("act_2", "minecraft:spyglass", "task", False, "He Watches Back", "Notice the one who is watching"),
    "spoke": ("watched", "minecraft:name_tag", "challenge", True, "One Question", "Get close enough to ask him something"),
    "act_3": ("act_2", "minecraft:ender_eye", "goal", False, "Act III: The Observer", "Steve has looked him in the eye"),
    "breach": ("act_3", "minecraft:cracked_stone_bricks", "task", False, "Eye Contact", "Be there when Steve remembers"),
    "witness": ("breach", "minecraft:dirt", "task", False, "From a Distance", "Follow Steve and see what the memory does to him"),
    "stay": ("witness", "minecraft:campfire", "goal", False, "You Stayed", "Stay with Steve until it passes"),
    "morning": ("breach", "minecraft:sunflower", "task", False, "The Morning After", "He doesn't remember last night. Your house does."),
    "hunted": ("act_3", "minecraft:skeleton_skull", "task", False, "He Sees You", "Make him notice you"),
    "removed": ("hunted", "minecraft:wither_skeleton_skull", "task", True, "Removed", "He reached you"),
    "saved": ("hunted", "minecraft:shield", "challenge", True, "Not Them", "Steve takes your place"),
    "act_4": ("act_3", "minecraft:glass", "goal", False, "Act IV: What Is Left", "Nobody can pretend it isn't happening any more"),
    "memories": ("act_4", "minecraft:nether_star", "challenge", False, "Everything He Remembers", "Help Steve recover every memory"),
    "angry": ("root", "minecraft:flint_and_steel", "task", True, "You Know What You Did", "Push Steve too far"),
}


def advancements(lang):
    for aid, (parent, icon, frame, hidden, title, desc) in ADVANCEMENTS.items():
        lang[f"advancements.leftbehind.{aid}.title"] = title
        lang[f"advancements.leftbehind.{aid}.description"] = desc
        display = {
            "icon": {"id": icon},
            "title": {"translate": f"advancements.leftbehind.{aid}.title"},
            "description": {"translate": f"advancements.leftbehind.{aid}.description"},
            "frame": frame,
            "show_toast": True,
            "announce_to_chat": False,
            "hidden": hidden,
        }
        obj = {"display": display, "criteria": {"done": {"trigger": "minecraft:impossible"}}}
        if parent is None:
            display["background"] = "minecraft:gui/advancements/backgrounds/stone"
        else:
            obj = {"parent": f"leftbehind:story/{parent}", **obj}
        write(os.path.join(DATA, "leftbehind", "advancement", "story", f"{aid}.json"), obj)


# ---------------------------------------------------------------- screen textures
def textures():
    out = os.path.join(ASSETS, "textures", "gui")
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(7)

    # Vignette: clear in the middle, black at the edges, slightly uneven so it doesn't look like a perfect oval.
    n = 256
    y, x = np.mgrid[0:n, 0:n]
    dx, dy = (x - n / 2) / (n / 2), (y - n / 2) / (n / 2)
    r = np.sqrt(dx * dx * 0.9 + dy * dy * 1.05)
    a = np.clip((r - 0.28) / 0.85, 0, 1) ** 1.6
    a = np.clip(a + (rng.random((n, n)) - 0.5) * 0.03, 0, 1)
    img = np.zeros((n, n, 4), dtype=np.uint8)
    img[..., 3] = (a * 255).astype(np.uint8)
    Image.fromarray(img, "RGBA").save(os.path.join(out, "vignette.png"))

    # Eyes: two blank white rectangles with a soft glow, the way they look on his face.
    w, h = 256, 128
    core = Image.new("L", (w, h), 0)
    px = core.load()
    for ex in (62, 146):
        for yy in range(52, 76):
            for xx in range(ex, ex + 48):
                px[xx, yy] = 255
    glow = core.filter(ImageFilter.GaussianBlur(9))
    alpha = np.clip(np.asarray(core, dtype=np.float32) + np.asarray(glow, dtype=np.float32) * 0.9, 0, 255)
    eyes = np.zeros((h, w, 4), dtype=np.uint8)
    eyes[..., :3] = 255
    eyes[..., 3] = alpha.astype(np.uint8)
    Image.fromarray(eyes, "RGBA").save(os.path.join(out, "eyes.png"))

    # Static: four frames of grey noise with rolling bands.
    for i in range(4):
        sw, sh = 160, 90
        noise = rng.random((sh, sw))
        band = 0.55 + 0.45 * np.sin(np.linspace(0, np.pi * 5, sh) + i * 1.3)[:, None]
        tear = (rng.random((sh, 1)) > 0.93).astype(np.float32)
        grey = np.clip(noise * 255, 0, 255)
        al = np.clip((noise * 0.8 + 0.2) * band * 255 + tear * 80, 0, 255)
        st = np.zeros((sh, sw, 4), dtype=np.uint8)
        st[..., 0] = st[..., 1] = st[..., 2] = grey.astype(np.uint8)
        st[..., 3] = al.astype(np.uint8)
        Image.fromarray(st, "RGBA").save(os.path.join(out, f"static_{i}.png"))


def main():
    lang = {
        "entity.leftbehind.original_steve": "Steve",
        "entity.leftbehind.herobrine": "Herobrine",
        "entity.leftbehind.remnant": "Remnant",
        "death.attack.leftbehind.removed": "%1$s was removed",
        "death.attack.leftbehind.removed.player": "%1$s was removed",
        "death.attack.leftbehind.removed.item": "%1$s was removed",
    }
    sounds = {}
    for event, (files, subtitle, stream) in SOUNDS.items():
        key = "subtitles.leftbehind." + event
        lang[key] = subtitle
        sounds[event] = {
            "subtitle": key,
            "sounds": [{"name": "leftbehind:" + f, "stream": True} if stream else "leftbehind:" + f for f in files],
        }
    write(os.path.join(ASSETS, "sounds.json"), sounds)
    advancements(lang)
    write(os.path.join(ASSETS, "lang", "en_us.json"), lang)

    write(os.path.join(DATA, "leftbehind", "damage_type", "removed.json"),
          {"exhaustion": 0.0, "message_id": "leftbehind.removed", "scaling": "never"})
    for tag in ["bypasses_invulnerability", "bypasses_armor", "bypasses_effects", "bypasses_resistance", "bypasses_enchantments",
                "bypasses_shield", "no_knockback"]:
        write(os.path.join(DATA, "minecraft", "tags", "damage_type", f"{tag}.json"), {"values": ["leftbehind:removed"]})
    textures()
    print("data written:", len(sounds), "sound events,", len(ADVANCEMENTS), "advancements,", len(lang), "text keys")


if __name__ == "__main__":
    main()
