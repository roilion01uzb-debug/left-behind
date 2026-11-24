"""
Records every line Original Steve (and Herobrine) can say, with emotion.

Uses the Kokoro-82M voice model (Apache-2.0), run locally through kokoro-onnx:

    pip install kokoro-onnx soundfile scipy
    curl -LO https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
    curl -LO https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
    python3 tools/make_voice.py --models <folder with those two files>

Steve's timbre is always the "am_michael" voice. Emotion comes from mixing the prosody half of the
style vector with other voices, from speed, pauses between sentences, breaths, and a little processing.

Output:
    src/main/resources/assets/leftbehind_voice/sounds/<speaker>/<hash>.ogg
    src/main/resources/assets/leftbehind_voice/sounds.json
    src/main/resources/data/leftbehind/voice/index.json   (hash -> length in ticks, read by the mod)

A line is found again in game by the FNV-1a hash of its exact text (see dev.leftbehind.talk.VoiceIndex).
Lines that are built at runtime (with a taught word, an answer, a number) have no recording and stay text only.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import soundfile as sf
from scipy import signal
from scipy.linalg import solve_toeplitz

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src/main/java/dev/leftbehind")
TALK = os.path.join(ROOT, "src/main/resources/data/leftbehind/talk/steve.json")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/leftbehind_voice")
INDEX = os.path.join(ROOT, "src/main/resources/data/leftbehind/voice/index.json")
SR = 24000


def fnv(text):
    h = 0x811C9DC5
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return "%08x" % h


# ---- which lines exist -------------------------------------------------------------------------

DYNAMIC = ("{word}", "{Word}", "{def}", "{answer}", "{teacher}")
NOT_SPOKEN_KEYS = {"match", "id", "type", "if", "do", "special", "flag", "_about", "quiet"}


def fill_without_name(line):
    """Same as Talk.fill when Steve doesn't know the player's name. The recording never says a name."""
    line = line.replace(", {name}", "").replace(" {name}", "").replace("{name}, ", "").replace("{name}. ", "").replace("{name}", "you")
    line = line.replace(" ,", ",").replace("  ", " ").strip()
    return line[:1].upper() + line[1:] if line else line


def talk_lines():
    """(text, context) for every line in steve.json. Context is the intent/question/story/topic id."""
    data = json.load(open(TALK))
    out = []

    def walk(node, ctx, key):
        if isinstance(node, str):
            if key in NOT_SPOKEN_KEYS or any(d in node for d in DYNAMIC):
                return
            out.append((fill_without_name(node), ctx))
        elif isinstance(node, list):
            for x in node:
                walk(x, ctx, key)
        elif isinstance(node, dict):
            here = node.get("id", ctx) if isinstance(node.get("id"), str) else ctx
            for k, v in node.items():
                if k in NOT_SPOKEN_KEYS:
                    continue
                walk(v, here, k)

    for section in ("intents", "questions", "stories"):
        for entry in data[section]:
            walk(entry, entry["id"], "")
    for topic, variants in data["topics"].items():
        walk(variants, "topic_" + topic, "")
    walk(data["fallback"], "fallback", "")
    for word, line in data["old_words"].items():
        walk(line, "old_word", "")
    walk(data["unknown_words"], "unknown_word", "")
    return out


JAVA_FILES = ["story/Reactions.java", "entity/OriginalSteve.java", "story/StoryDirector.java", "story/Relics.java",
              "entity/Herobrine.java", "story/Dialogue.java", "talk/Talk.java", "story/Scenes.java", "story/Caves.java",
              # 0.6: Steve at work, what he understands, what he learns, and Herobrine learning you.
              "story/Observation.java", "entity/SteveWork.java", "talk/Mind.java", "story/Stalker.java", "story/Structures.java",
              "story/HerobrineMind.java"]
NOT_LINES = {
    "Steve was taken (sanity 0)", "Black Steve",
    "Type in chat to talk to Steve.", "He is waiting. Type your question.", "Original Steve has arrived.", "Steve remembers.",
    "My name is ...", "He is furious with you.", "He hasn't forgiven you.", "He is getting used to you.", "He is glad you're here.",
    "He keeps glancing over your shoulder.", "He keeps losing the end of his sentences.", "He seems calm.", "He trusts you.",
    "He would do anything for you.", "He doesn't know what to make of you yet.",
    "His hands won't stop moving. He is not really looking at you.", "I don't know you yet.",
    "I haven't watched you long enough to say more.",
    # 0.6: words on signs, and log text.
    "you are", "not the", "first one", "count them", "it is quiet", "in here", "this was", "my room",
    "IIII IIII", "IIII II", "Placed {} at {}", "Steve works: {}",
    # 0.6: examples quoted in comments.
    "I found diamonds.", "I like diamonds.", "Where is iron?", "Light it up.", "Stop working", "Never mind", "What are you doing?",
    "Right here. Under your feet, nearly", "Do you want one?", "Do you want them?",
}
HEROBRINE_SOURCES = {"entity/Herobrine.java", "story/Dialogue.java", "story/Stalker.java", "story/Structures.java", "story/HerobrineMind.java"}
# Lines from these files take this context's mood (Steve talking quietly to someone on a hill, or half asleep).
FILE_CONTEXT = {"story/Scenes.java": "scene", "entity/SteveWork.java": "work", "talk/Mind.java": "work"}


def java_lines():
    """(speaker, text) for every string literal in the story code that looks like something said out loud."""
    lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
    out = []
    for f in JAVA_FILES:
        src = open(os.path.join(JAVA, f)).read()
        for m in lit.finditer(src):
            s = m.group(1).replace('\\"', '"').replace("\\'", "'")
            if s in NOT_LINES or not re.search(r"[A-Za-z]", s) or "*" in s or "{" in s or "%" in s:
                continue
            if s != s.strip() or s.startswith("/") or ":" in s[:14] or "_" in s:
                continue
            ctx = FILE_CONTEXT.get(f, "")
            if s[0].isupper() or s.startswith("..."):
                if " " in s or re.search(r"[.!?]$", s):
                    out.append(("steve", s, ctx))
            elif f in HEROBRINE_SOURCES and " " in s and s == s.lower() and s[0].isalpha():
                out.append(("herobrine", s, ctx))
    return out


# ---- how he feels saying it ----------------------------------------------------------------------

CONTEXT_MOOD = {
    "joke": "warm", "laugh": "warm", "thanks": "warm", "kind": "warm", "nice_to_meet": "warm", "greet": "warm", "back": "warm",
    "likes": "warm", "game": "warm", "build": "warm", "give": "warm", "feel_happy": "warm",
    "insult": "cold", "shut_up": "cold", "go_away": "cold",
    "him": "scared", "are_you_herobrine": "scared", "scared": "scared", "q_him": "scared",
    "scene": "hushed", "who_with": "hushed",
    "lonely": "sad", "what_happened": "sad", "remember": "sad", "death": "sad", "i_died": "sad", "the_others": "sad",
    "feel_sad": "sad", "topic_past": "sad", "topic_others": "sad", "q_alone": "sad", "s_first_night": "sad",
}
ANGRY = ("get out", "my house", "leave me alone", "you know what you did", "don't want to talk", "your world", "here first",
         "stay away", "leave them alone", "you're not me", "can't have this", "not mine. not mine", "my door", "who put", "why would you do that")
PANIC = ("run", "behind you", "fire", "water", "closer", "stay back", "go away", "get back", "not you too", "don't touch me",
         "please!", "hey!", "won't", "no, no", "who said that", "roll", "don't let him")
SCARED = ("him", "he's", "he is", "his ", "eyes", "not again", "not real", "reset", "behind", "afraid", "scared",
          "don't point", "nobody there", "who is that", "something behind", "hear that", "see us", "with my face", "please")
HUSHED = ("dark", "night", "quiet", "edges", "watching", "the middle", "stops", "listen", "don't go", "wait.", "goodnight",
          "stand still", "very still", "something is wrong")
SAD = ("remember", "forgot", "forget", "used to", "alone", "nobody", "gone", "removed", "missing", "anymore", "any more",
       "stayed", "lost", "first night", "waiting", "wait here", "once", "there were more", "never", "worst", "hurt", "again?",
       "came back", "were all", "pieces", "bits of", "before", "tired")
WARM = ("thank", "kind", "friend", "glad", "like when", "i like", "nice", "good", "hello", "hey", "hi.", "morning", "haha",
        "heh", "sweet", "warm", "love", "great", "lead the way", "i'll come", "keep up", "fun", "pretty", "shiny")


# Lines whose words fool the keyword rules (a rose on "my door" is not anger).
MOOD_OVERRIDE = {
    "Swim up! Now!": "panic",
    "Up! Go up! You need air!": "panic",
    "Watch out!": "panic",
    "Arrows! Get behind something!": "panic",
    "Stay away from the tall trees.": "hushed",
    "This one's stubborn.": "calm",
    "I like this part. It's quiet work.": "warm",
    "Iron. This is where it starts getting good.": "warm",
    "This is all I could find. Here.": "sad",
    "Spider. They climb. Walls don't help.": "scared",
    "This was my first night. I dug down because up there, everything could see me.": "sad",
    "Stone. Stone tools are better. I remember that much.": "calm",
    "I'm getting cobblestone. Lots of it. You can never have enough.": "warm",
    "I'll get some wood. Trees don't mind. I asked one once.": "warm",
    "One more. You can never have one too many.": "warm",
    "Okay. Forget it.": "calm",
    "I'll remember. If I find any, it's yours.": "warm",
    "Here. You like these. I remembered.": "warm",
    "I haven't forgotten what you asked.": "warm",
    "A bed. So this is home now.": "warm",
    "Not yet. Make one where I can see you do it. I learn by watching.": "curious",
    "I've never made that. Make one where I can see, and I'll learn.": "curious",
    "Don't count them. He likes it when you count them.": "scared",
    "Seven. There's always seven. Until there isn't.": "hushed",
    "He knows where you walk. He learns people. He learned me.": "scared",
    "He was standing right there. Where you always go.": "scared",
    "Don't open it. Someone wanted it closed. Maybe it was me.": "scared",
    "I know where that goes. I don't want to. I know.": "scared",
    "Don't look over there. Look at me. Okay. Okay.": "scared",
    "Did you see someone? Over there. ...No. Never mind. Trees.": "scared",
    "Was someone standing there? Just now?": "scared",
    "The tall one. Look at your feet. Look at your feet.": "scared",
    "Not the green one. Hit it and step back. Hit and step back.": "scared",
    "I can't see you. Say something. Say anything.": "scared",
    "I don't like thunder. Something always comes with it.": "hushed",
    "You opened it. ...It's empty. Why is it empty? Where did it go?": "scared",
    "Stairs. Down. I dug those. Didn't I?": "sad",
    "Keep them somewhere safe. Somewhere he doesn't go.": "hushed",
    "We should go in. It's not safe in the open.": "hushed",
    "Oh. Oh, these are the blue ones. People used to fight over these.": "curious",
    "Leave it. Please. It's quiet when it's closed.": "scared",
    "A rose. Rana put those on things she wanted to keep. My door had three.": "warm",
    "She said a rose means this one stays. I stayed. She didn't get to.": "sad",
    "She was my first friend. Then one day the list didn't have her name on it. Just like that.": "sad",
    "Rana put roses on everything she wanted to keep. Doors. Chests. My roof.": "warm",
    "Who said that? ...you didn't say that. Right.": "scared",
    "There are two of you. No. One. One of you.": "scared",
    "He was standing right there. A moment ago.": "scared",
    "Can you put a torch down? Here. Right here.": "scared",
    "One, two, three, four. One, two, three... four. Still four.": "hushed",
    "You are in the way. ...not you. I didn't mean you. I didn't mean anything.": "scared",
    "Not now. I'm talking to them.": "hushed",
    "Stop whispering. I can hear you. Both of you.": "scared",
    "...who are you? Where is he? Where did he go?": "scared",
    "Somebody lit the way. ...it was me. I think it was me. A long time ago.": "sad",
    "Those are mine. From before. I don't remember putting them there.": "hushed",
}


def mood(text, ctx, speaker):
    if speaker == "herobrine":
        return "herobrine"
    if text in MOOD_OVERRIDE:
        return MOOD_OVERRIDE[text]
    t = " " + text.lower() + " "
    shouting = bool(re.search(r"\b(?!I\b|TNT\b)[A-Z]{2,}\b", text))
    if any(w in t for w in ANGRY):
        return "angry"
    if (shouting and len(text) < 80) or (text.count("!") >= 1 and any(w in t for w in PANIC)):
        return "panic"
    if ctx in CONTEXT_MOOD:
        return CONTEXT_MOOD[ctx]
    if any(w in t for w in SCARED):
        return "scared"
    if any(w in t for w in HUSHED):
        return "hushed"
    if any(w in t for w in SAD):
        return "sad"
    if any(w in t for w in WARM) or "!" in text:
        return "warm"
    if text.endswith("?"):
        return "curious"
    return "calm"


PRESETS = {
    #            prosody mix                                     speed  gap   gain  breathy inhale sigh
    "calm":      ({"am_michael": 1.0},                           0.93, 0.38, -20, 0.00, 0.00, 0.00),
    "curious":   ({"am_michael": 0.85, "af_heart": 0.15},        0.95, 0.32, -20, 0.00, 0.10, 0.00),
    "warm":      ({"am_michael": 0.8, "af_heart": 0.2},          0.97, 0.30, -19, 0.00, 0.00, 0.00),
    "sad":       ({"am_michael": 0.5, "am_echo": 0.5},           0.84, 0.62, -23, 0.10, 0.00, 0.40),
    "hushed":    ({"am_michael": 0.7, "af_nicole": 0.3},         0.90, 0.48, -24, 0.30, 0.15, 0.00),
    "scared":    ({"am_michael": 0.5, "am_puck": 0.5},           1.04, 0.24, -21, 0.18, 0.45, 0.00),
    "panic":     ({"am_fenrir": 1.0},                            1.14, 0.12, -17, 0.08, 0.35, 0.00),
    "angry":     ({"am_michael": 0.4, "am_fenrir": 0.6},         0.98, 0.26, -17, 0.00, 0.20, 0.00),
    "cold":      ({"am_michael": 0.5, "am_echo": 0.5},           0.90, 0.45, -21, 0.00, 0.00, 0.15),
    "herobrine": ({"am_michael": 0.5, "am_onyx": 0.5},           0.80, 0.70, -22, 0.75, 0.00, 0.00),
    # Sound effects rather than lines: Steve muttering to himself in an episode, and Herobrine right at your ear.
    "mutter":    ({"am_michael": 0.6, "am_echo": 0.4},           1.02, 0.30, -25, 0.45, 0.00, 0.00),
    "ear":       ({"am_michael": 0.5, "am_onyx": 0.5},           0.82, 0.55, -24, 0.97, 0.00, 0.00),
}


# ---- making the sound --------------------------------------------------------------------------

_k = None
_voices = None


def init(models):
    global _k, _voices
    from kokoro_onnx import Kokoro
    _k = Kokoro(os.path.join(models, "kokoro-v1.0.onnx"), os.path.join(models, "voices-v1.0.bin"))
    _voices = np.load(os.path.join(models, "voices-v1.0.bin"))


def style(prosody):
    s = _voices["am_michael"].copy()
    s[:, :, 128:] = sum(w * _voices[n][:, :, 128:] for n, w in prosody.items())
    return s


def spoken_text(sentence):
    """What the voice model reads: shouted words in normal case (it spells some capitals out), no stage marks."""
    s = re.sub(r"\b(?!I\b|TNT\b)([A-Z]{2,})\b", lambda m: m.group(1).lower(), sentence)
    s = s.replace("TNT", "T N T")
    s = s.strip(" .") if s.strip(" .") else s
    if not re.search(r"[.!?]$", s):
        s += "."
    return s[0].upper() + s[1:]


def sentences(text):
    """Splits into pieces with the pause that should follow each. '...' is a longer, thinking pause."""
    parts = []
    for chunk in re.split(r"(?<=[.!?])\s+", text.strip()):
        for i, piece in enumerate(re.split(r"\.\.\.\s+", chunk)):
            if piece.strip(" ."):
                parts.append(piece)
    return parts


def lpc_whisper(x, order=22):
    """Replaces the voiced buzz with breath, keeping the mouth shapes: a real whisper, not a filter."""
    frame, hop = 480, 120
    win = np.hanning(frame)
    out = np.zeros(len(x) + frame)
    rng = np.random.default_rng(len(x))
    for start in range(0, len(x) - frame, hop):
        seg = x[start:start + frame] * win
        r = np.correlate(seg, seg, "full")[frame - 1:frame + order]
        if r[0] < 1e-9:
            continue
        try:
            a = solve_toeplitz(r[:order], r[1:order + 1])
        except Exception:
            continue
        err = r[0] - np.dot(a, r[1:order + 1])
        g = np.sqrt(max(err, 1e-12) / frame)
        exc = rng.standard_normal(frame) * g
        y = signal.lfilter([1.0], np.concatenate(([1.0], -a)), exc)
        out[start:start + frame] += y * win
    out = out[:len(x)] * (hop / frame * 2.6)
    return out


def breath(kind, seconds, level, rng):
    n = int(SR * seconds)
    x = rng.standard_normal(n)
    lo, hi = (700, 5200) if kind == "in" else (250, 2800)
    b, a = signal.butter(2, [lo / (SR / 2), hi / (SR / 2)], "band")
    x = signal.lfilter(b, a, x)
    t = np.linspace(0, 1, n)
    env = (t ** 1.6) * np.clip((1 - t) / 0.12, 0, 1) if kind == "in" else np.clip(t / 0.08, 0, 1) * np.exp(-t * 3.2)
    x = x * env
    return x / (np.sqrt(np.mean(x ** 2)) + 1e-9) * level


def reverb(x, seconds=1.4, mix=0.32, rng=None):
    rng = rng or np.random.default_rng(7)
    n = int(SR * seconds)
    ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 7, n))
    ir = signal.lfilter(*signal.butter(2, 3500 / (SR / 2)), ir)
    wet = np.zeros(len(x) + n)
    conv = signal.fftconvolve(x, ir)
    wet[:len(conv)] = conv[:len(wet)]
    wet /= np.max(np.abs(wet)) + 1e-9
    dry = np.concatenate((x, np.zeros(n)))
    return dry * (1 - mix) + wet * mix * np.max(np.abs(x))


def pitch_down(x, semitones):
    with tempfile.TemporaryDirectory() as tmp:
        a, b = os.path.join(tmp, "a.wav"), os.path.join(tmp, "b.wav")
        sf.write(a, x, SR)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", a, "-af", f"rubberband=pitch={2 ** (-semitones / 12):.4f}:formant=shifted", b], check=True)
        y, _ = sf.read(b, dtype="float32")
    return y


def render(text, emotion, seed):
    prosody, speed, gap, gain_db, breathy, inhale, sigh = PRESETS[emotion]
    rng = np.random.default_rng(seed)
    st = style(prosody)
    pieces = sentences(text)
    audio = []
    for i, piece in enumerate(pieces):
        jitter = 1.0 + rng.uniform(-0.03, 0.03)
        a, _ = _k.create(spoken_text(piece), voice=st, speed=speed * jitter, lang="en-us")
        audio.append(a.astype(np.float64))
        if i < len(pieces) - 1:
            pause = gap * rng.uniform(0.85, 1.2)
            if "..." in text and (piece.endswith("...") or text.find("... ") >= 0):
                pause += 0.18
            audio.append(np.zeros(int(SR * pause)))
    x = np.concatenate(audio)
    if text.startswith("..."):
        x = np.concatenate((np.zeros(int(SR * 0.3)), x))
    voice_rms = np.sqrt(np.mean(x ** 2)) + 1e-9
    if breathy > 0:
        w = lpc_whisper(x)
        w = w / (np.sqrt(np.mean(w ** 2)) + 1e-9) * voice_rms
        x = x * (1 - breathy) + w * breathy
    if emotion == "sad":
        x = signal.lfilter(*signal.butter(2, 5200 / (SR / 2)), x)
    if emotion == "angry":
        x = np.tanh(x / voice_rms * 0.55) * voice_rms / 0.55 * 0.6 + x * 0.4
    if emotion == "herobrine":
        x = pitch_down(x.astype(np.float32), 3).astype(np.float64)
        x = reverb(x, rng=rng)
    if emotion == "ear":
        # Close and dry: no room, just breath, a little too low to be Steve.
        x = pitch_down(x.astype(np.float32), 2).astype(np.float64)
        x = signal.lfilter(*signal.butter(2, 180 / (SR / 2), "high"), x)
    if emotion == "mutter":
        x = signal.lfilter(*signal.butter(2, 3800 / (SR / 2)), x)
    lead = []
    if inhale and rng.random() < inhale:
        lead = [breath("in", rng.uniform(0.28, 0.42), voice_rms * 0.10, rng), np.zeros(int(SR * 0.08))]
    elif sigh and rng.random() < sigh:
        lead = [breath("out", rng.uniform(0.5, 0.75), voice_rms * 0.09, rng), np.zeros(int(SR * 0.15))]
    x = np.concatenate(lead + [x, np.zeros(int(SR * 0.12))])
    rms = np.sqrt(np.mean(x[np.abs(x) > 0.002] ** 2)) + 1e-9
    x = x / rms * (10 ** (gain_db / 20))
    peak = np.max(np.abs(x))
    if peak > 0.95:
        x = x / peak * 0.95
    fade = int(SR * 0.01)
    x[:fade] *= np.linspace(0, 1, fade)
    x[-fade:] *= np.linspace(1, 0, fade)
    return x.astype(np.float32)


def encode(x, path):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "a.wav")
        sf.write(wav, x, SR)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-ac", "1", "-c:a", "libvorbis", "-q:a", "2", path], check=True)


def job(item):
    speaker, text, emotion, path = item
    x = render(text, emotion, int(fnv(speaker + text), 16))
    if path.endswith(".ogg"):
        encode(x, path)
    else:
        sf.write(path, x, SR)
    return speaker, text, emotion, len(x) / SR


# ---- putting it together -----------------------------------------------------------------------

def collect():
    lines = {}
    for text, ctx in talk_lines():
        if not re.search(r"[A-Za-z]", text):
            continue
        lines.setdefault(("steve", text), mood(text, ctx, "steve"))
    for speaker, text, ctx in java_lines():
        lines.setdefault((speaker, text), mood(text, ctx, speaker))
    return lines


DEMO = [
    ("calm", "I learn by watching. If you do something enough times, I'll try it too."),
    ("warm", "Hey. It's you. I was hoping you'd come over."),
    ("curious", "What are you building? Can I watch?"),
    ("sad", "There used to be more people. I think. ...I keep forgetting something important."),
    ("hushed", "Night. I don't sleep. I just wait. Stay close to the light, alright?"),
    ("scared", "Don't look at him. If you look long enough, he looks back. And then he doesn't stop."),
    ("panic", "RUN! Don't let him touch you!"),
    ("angry", "Get out of my house. This was my house first."),
    ("herobrine", "you are not the first one he followed"),
]


# Mod sound effects made of words. They replace the old wordless synth noises (Temur, 2026-11-24:
# "change the weird creepy cannibal sound"). Each lands at assets/leftbehind/sounds/<path>.ogg.
EFFECTS = [
    ("herobrine/whisper_1", "ear", "turn around"),
    ("herobrine/whisper_2", "ear", "i'm right behind you"),
    ("herobrine/whisper_3", "ear", "he doesn't remember you"),
    ("herobrine/whisper_4", "ear", "don't look at him"),
    ("herobrine/whisper_5", "ear", "you're not the first one"),
    ("herobrine/whisper_6", "ear", "he let you come back"),
    ("herobrine/whisper_7", "ear", "behind you"),
    ("herobrine/whisper_8", "ear", "count them. he counts them"),
    ("steve/mutter_1", "mutter", "Not mine. Not mine. Not mine."),
    ("steve/mutter_2", "mutter", "One. Two. One. Two. One."),
    ("steve/mutter_3", "mutter", "It was right here. It was right here."),
    ("steve/mutter_4", "mutter", "Don't reset. Don't reset. Please don't."),
    ("steve/mutter_5", "mutter", "The walls were black. The walls were black."),
    ("steve/mutter_6", "mutter", "I didn't break it. I didn't. I didn't."),
]
MOD_SOUNDS = os.path.join(ROOT, "src/main/resources/assets/leftbehind/sounds")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--demo", help="write a demo of each emotion into this folder instead")
    ap.add_argument("--list", action="store_true", help="only print the lines and their moods")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--effects", action="store_true", help="only make the word sound effects (EFFECTS)")
    ap.add_argument("--all", action="store_true", help="record every line again, not only new ones")
    args = ap.parse_args()

    if args.effects:
        items = [("herobrine" if m == "ear" else "steve", t, m, os.path.join(MOD_SOUNDS, p + ".ogg")) for p, m, t in EFFECTS]
        with ProcessPoolExecutor(args.jobs, initializer=init, initargs=(args.models,)) as pool:
            for speaker, text, m, seconds in pool.map(job, items):
                print(f"{m:7s} {seconds:4.1f}s {text}")
        return

    if args.list:
        lines = collect()
        for (speaker, text), m in sorted(lines.items(), key=lambda kv: (kv[1], kv[0])):
            print(f"{m:9s} {speaker:9s} {text}")
        counts = {}
        for m in lines.values():
            counts[m] = counts.get(m, 0) + 1
        print(len(lines), counts, file=sys.stderr)
        return

    if args.demo:
        os.makedirs(args.demo, exist_ok=True)
        items = [("herobrine" if m == "herobrine" else "steve", t, m, os.path.join(args.demo, f"{i:02d}_{m}.wav")) for i, (m, t) in enumerate(DEMO)]
    else:
        lines = collect()
        hashes = {}
        for speaker, text in lines:
            h = fnv(text)
            if (speaker, h) in hashes and hashes[(speaker, h)] != text:
                sys.exit(f"hash collision: {text!r} / {hashes[(speaker, h)]!r}")
            hashes[(speaker, h)] = text
        for speaker in ("steve", "herobrine"):
            os.makedirs(os.path.join(ASSETS, "sounds", speaker), exist_ok=True)
        old = {}
        if not args.all and os.path.exists(INDEX):
            old = json.load(open(INDEX))
        items, results = [], []
        for (speaker, text), m in lines.items():
            h = fnv(text)
            path = os.path.join(ASSETS, "sounds", speaker, h + ".ogg")
            if h in old.get(speaker, {}) and os.path.exists(path):
                results.append((speaker, text, m, old[speaker][h] / 20))  # recorded before, keep it
            else:
                items.append((speaker, text, m, path))
        print(f"{len(items)} new lines to record, {len(results)} kept", flush=True)

    with ProcessPoolExecutor(args.jobs, initializer=init, initargs=(args.models,)) as pool:
        if args.demo:
            results = []
        for n, r in enumerate(pool.map(job, items, chunksize=4)):
            results.append(r)
            if n % 50 == 0:
                print(f"{n}/{len(items)}", flush=True)

    if args.demo:
        return

    keep = {os.path.join(ASSETS, "sounds", s, fnv(t) + ".ogg") for s, t, _, _ in results}
    for speaker in ("steve", "herobrine"):
        folder = os.path.join(ASSETS, "sounds", speaker)
        for f in os.listdir(folder):
            if os.path.join(folder, f) not in keep:
                os.remove(os.path.join(folder, f))
    sounds, index = {}, {"steve": {}, "herobrine": {}}
    for speaker, text, m, seconds in sorted(results):
        h = fnv(text)
        sounds[f"{speaker}.{h}"] = {"sounds": [{"name": f"leftbehind_voice:{speaker}/{h}", "attenuation_distance": 40}]}
        index[speaker][h] = int(np.ceil(seconds * 20))
    json.dump(sounds, open(os.path.join(ASSETS, "sounds.json"), "w"), indent=1, sort_keys=True)
    os.makedirs(os.path.dirname(INDEX), exist_ok=True)
    json.dump(index, open(INDEX, "w"), indent=1, sort_keys=True)
    total = sum(r[3] for r in results)
    print(f"{len(results)} lines, {total / 60:.1f} minutes of speech")


if __name__ == "__main__":
    main()
