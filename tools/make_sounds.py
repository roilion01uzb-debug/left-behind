#!/usr/bin/env python3
"""Synthesises the mod's original sound effects and ambience (no samples used).

Usage: python3 tools/make_sounds.py
Needs numpy, scipy, soundfile and ffmpeg. Writes .ogg files into
src/main/resources/assets/leftbehind/sounds/.
"""
import os
import subprocess
import tempfile

import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/assets/leftbehind/sounds")


# ------------------------------------------------------------------ helpers
def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def noise(seconds, rng):
    return rng.standard_normal(int(SR * seconds))


def butter(x, kind, freq, order=4):
    sos = signal.butter(order, freq, btype=kind, fs=SR, output="sos")
    return signal.sosfiltfilt(sos, x)


def lp(x, f, order=4):
    return butter(x, "lowpass", f, order)


def hp(x, f, order=4):
    return butter(x, "highpass", f, order)


def bp(x, lo, hi, order=2):
    return butter(x, "bandpass", [lo, hi], order)


def fade(x, fade_in=0.01, fade_out=0.05):
    x = x.copy()
    n_in, n_out = int(SR * fade_in), int(SR * fade_out)
    if n_in:
        x[:n_in] *= np.linspace(0, 1, n_in) ** 2
    if n_out:
        x[-n_out:] *= np.linspace(1, 0, n_out) ** 2
    return x


def normalize(x, peak=0.85):
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


def reverb(x, seconds=2.5, mix=0.35, rng=None, tone=3500, tail=True):
    """Convolution reverb with a synthetic impulse response (decaying filtered noise)."""
    rng = rng or np.random.default_rng(7)
    n = int(SR * seconds)
    ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 7, n))
    ir = lp(ir, tone)
    ir[: int(SR * 0.004)] = 0
    wet = signal.fftconvolve(x, ir)[: len(x) + (n if tail else 0)]
    dry = np.pad(x, (0, len(wet) - len(x)))
    wet = wet / (np.max(np.abs(wet)) + 1e-9) * np.max(np.abs(x))
    return dry * (1 - mix) + wet * mix


def place(buf, sound, at_seconds, gain=1.0):
    i = int(SR * at_seconds)
    end = min(len(buf), i + len(sound))
    if end > i:
        buf[i:end] += sound[: end - i] * gain


def loopable(x, cross=1.0):
    """Crossfades the tail into the head so the sound can repeat without a click."""
    n = int(SR * cross)
    head, tail = x[:n].copy(), x[-n:].copy()
    w = np.linspace(0, 1, n)
    out = x[:-n].copy()
    out[:n] = head * w + tail * (1 - w)
    return out


def bell(freq, seconds, rng, bright=1.0):
    """An inharmonic struck-bar tone, like a music box tine."""
    tt = t(seconds)
    partials = [(1.0, 1.0, 3.0), (2.76, 0.42 * bright, 5.0), (5.4, 0.2 * bright, 8.0), (8.93, 0.08 * bright, 12.0)]
    out = np.zeros_like(tt)
    for ratio, amp, decay in partials:
        detune = 1 + rng.uniform(-0.002, 0.002)
        out += amp * np.sin(2 * np.pi * freq * ratio * detune * tt) * np.exp(-decay * tt)
    out[: int(SR * 0.002)] *= np.linspace(0, 1, int(SR * 0.002))
    return out


def thump(seconds, f0, f1, decay, rng=None, click=0.0):
    tt = t(seconds)
    freq = f1 + (f0 - f1) * np.exp(-tt * 30)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    out = np.sin(phase) * np.exp(-tt * decay)
    if click and rng is not None:
        out += click * lp(rng.standard_normal(len(tt)), 1800) * np.exp(-tt * 90)
    return out


def formant_noise(seconds, rng, vowels, breathy=1.0):
    """Whisper: noise pushed through slowly moving vowel formants."""
    n = int(SR * seconds)
    src = hp(rng.standard_normal(n), 300)
    seg = n // len(vowels)
    out = np.zeros(n)
    for i, (f1, f2, f3) in enumerate(vowels):
        a, b = i * seg, n if i == len(vowels) - 1 else (i + 1) * seg
        chunk = src[a:b]
        part = bp(chunk, f1 * 0.85, f1 * 1.15) * 1.0 + bp(chunk, f2 * 0.9, f2 * 1.1) * 0.7 + bp(chunk, f3 * 0.92, f3 * 1.08) * 0.35
        part += breathy * 0.15 * hp(chunk, 4000)
        env = np.hanning(len(part)) ** 0.6
        out[a:b] += part * env
    return out


def save(name, x, quality=4, peak=0.85):
    path = os.path.join(OUT, name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    x = normalize(np.asarray(x, dtype=np.float64), peak)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        sf.write(tmp.name, x.astype(np.float32), SR)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-ac", "1", "-c:a", "libvorbis",
                    "-q:a", str(quality), path], check=True)
    os.unlink(tmp.name)
    print(f"{name}.ogg  {len(x) / SR:5.1f}s  {os.path.getsize(path) / 1024:6.1f} KB")


# ------------------------------------------------------------------ ambience
def dread(seed, root):
    """A long, low room tone: detuned sub drone, slow wind, far metal."""
    rng = np.random.default_rng(seed)
    seconds = 22
    tt = t(seconds)
    drone = np.zeros_like(tt)
    for ratio, amp in ((1, 1.0), (1.007, 0.8), (1.5, 0.35), (2.01, 0.25), (0.5, 0.5)):
        lfo = 0.6 + 0.4 * np.sin(2 * np.pi * rng.uniform(0.03, 0.09) * tt + rng.uniform(0, 6))
        drone += amp * lfo * np.sin(2 * np.pi * root * ratio * tt + rng.uniform(0, 6))
    wind = bp(noise(seconds, rng), 180, 700)
    wind *= 0.5 + 0.5 * np.sin(2 * np.pi * 0.05 * tt + 1.0) ** 2
    wind *= 0.6
    pings = np.zeros_like(tt)
    for _ in range(4):
        f = rng.choice([311, 415, 466, 622, 740]) * rng.uniform(0.98, 1.02)
        place(pings, bell(f, 5.0, rng, bright=1.6) * rng.uniform(0.15, 0.3), rng.uniform(1, seconds - 6))
    mix = drone * 0.5 + wind * 0.35 + reverb(pings, 4.0, 0.8, rng, tail=False) * 0.5
    mix = lp(mix, 5000)
    return loopable(mix, 2.0)


def wind_howl(seed):
    rng = np.random.default_rng(seed)
    seconds = 10
    tt = t(seconds)
    src = noise(seconds, rng)
    out = np.zeros_like(tt)
    for k in range(6):  # sweep a resonant band by cross-fading fixed bands
        centre = 280 + 130 * k
        band = bp(src, centre * 0.9, centre * 1.1)
        weight = np.clip(1 - np.abs((2.5 + 2.4 * np.sin(2 * np.pi * 0.11 * tt + 0.8)) - k), 0, 1)
        out += band * weight
    out *= 0.4 + 0.6 * np.sin(np.pi * tt / seconds) ** 2
    return fade(reverb(out, 2.0, 0.3, rng, tail=False), 1.0, 1.5)


def static_burst(seed):
    rng = np.random.default_rng(seed)
    seconds = 1.3
    tt = t(seconds)
    x = hp(noise(seconds, rng), 900)
    gate = (signal.square(2 * np.pi * 23 * tt + rng.uniform(0, 6), duty=0.6) + 1) / 2
    gate = np.maximum(gate, 0.15)
    x *= gate
    x += 0.25 * np.sin(2 * np.pi * 50 * tt) + 0.1 * np.sin(2 * np.pi * 1000 * tt) * (tt < 0.12)
    return fade(x, 0.002, 0.04)


def knock(seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 1.8))
    for i, at in enumerate((0.05, 0.42, 0.79)):
        body = bp(rng.standard_normal(int(SR * 0.25)), 120, 420) * np.exp(-t(0.25) * 38)
        body += 0.8 * thump(0.25, 190, 95, 30)
        place(out, body, at, 1.0 - 0.12 * i)
    return fade(reverb(out, 0.9, 0.3, rng, tone=1800, tail=False), 0.001, 0.2)


def page_rustle(seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 0.7))
    for at, length in ((0.0, 0.18), (0.16, 0.28), (0.4, 0.2)):
        r = hp(rng.standard_normal(int(SR * length)), 2500)
        r *= np.hanning(len(r)) * (0.6 + 0.4 * rng.random(len(r)))
        place(out, r, at)
    return fade(out, 0.002, 0.1)


# ------------------------------------------------------------------ Herobrine
def presence(seed):
    """Something is watching: a thin high cluster over a slow sub pulse."""
    rng = np.random.default_rng(seed)
    seconds = 11
    tt = t(seconds)
    high = sum(np.sin(2 * np.pi * f * tt + rng.uniform(0, 6)) * a for f, a in
               ((2093, 0.5), (2105, 0.5), (3136, 0.25), (3151, 0.2), (4186, 0.08)))
    high *= 0.5 + 0.5 * np.sin(2 * np.pi * 0.07 * tt) ** 2
    sub = np.sin(2 * np.pi * 37 * tt) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.5 * tt)) ** 4
    air = bp(noise(seconds, rng), 5000, 9000) * 0.06
    mix = high * 0.16 + sub * 0.7 + air
    return fade(reverb(mix, 3.0, 0.4, rng, tail=False), 2.0, 3.0)


def heartbeat():
    out = np.zeros(int(SR * 0.9))
    place(out, thump(0.3, 85, 42, 16), 0.0, 1.0)
    place(out, thump(0.3, 70, 38, 20), 0.27, 0.75)
    return fade(lp(out, 220), 0.002, 0.1)


def sting(seed):
    """He sees you: a hard dissonant hit that falls away."""
    rng = np.random.default_rng(seed)
    seconds = 3.2
    tt = t(seconds)
    drop = 1 - 0.07 * np.clip(tt / seconds, 0, 1)
    hit = np.zeros_like(tt)
    for f in (98, 104, 147, 156, 233, 247, 370):
        phase = 2 * np.pi * np.cumsum(f * drop) / SR
        hit += signal.sawtooth(phase + rng.uniform(0, 6)) * rng.uniform(0.6, 1.0)
    hit = lp(hit, 2600) * np.exp(-tt * 1.4)
    crack = hp(noise(seconds, rng), 1500) * np.exp(-tt * 28) * 2.5
    boom = thump(seconds, 120, 34, 2.2) * 2.2
    mix = hit * 0.5 + crack + boom
    return fade(reverb(np.tanh(mix * 0.6), 2.5, 0.35, rng, tail=False), 0.0, 0.8)


def chase(seed):
    """A driving pulse that never resolves; loops while he hunts."""
    rng = np.random.default_rng(seed)
    bpm = 152
    beat = 60 / bpm
    bars = 8
    seconds = beat * 4 * bars + 1.0
    out = np.zeros(int(SR * seconds))
    for i in range(int(4 * bars) + 2):
        place(out, thump(0.28, 110, 44, 15, rng, click=0.3), i * beat, 1.0 if i % 4 == 0 else 0.7)
        if i % 2 == 1:
            tick = hp(rng.standard_normal(int(SR * 0.05)), 5000) * np.exp(-t(0.05) * 90)
            place(out, tick, i * beat + beat / 2, 0.25)
    tt = np.arange(len(out)) / SR
    drone = np.zeros_like(tt)
    for f in (55, 58.3, 82.4, 87.3, 116.5):
        drone += signal.sawtooth(2 * np.pi * f * tt + rng.uniform(0, 6))
    drone = lp(drone, 500 + 300 * np.sin(2 * np.pi * tt / (beat * 16)) ** 2 if False else 650) * 0.12
    drone *= 0.7 + 0.3 * np.sin(2 * np.pi * tt / (beat * 2)) ** 2
    scrape = bp(rng.standard_normal(len(tt)), 2200, 3400) * 0.03 * (0.5 + 0.5 * np.sin(2 * np.pi * 0.35 * tt))
    return loopable(np.tanh((out + drone + scrape) * 1.1), 1.0)


def vanish(seed):
    rng = np.random.default_rng(seed)
    seconds = 1.3
    tt = t(seconds)
    swell = bp(noise(seconds, rng), 400, 3000) * (tt / seconds) ** 3
    swell[int(SR * 1.05):] = 0
    out = swell.copy()
    place(out, thump(0.25, 90, 36, 18), 1.05, 1.6)
    return fade(out, 0.0, 0.02)


def whisper(seed, vowels, seconds):
    rng = np.random.default_rng(seed)
    w = formant_noise(seconds, rng, vowels)
    w = reverb(w, 1.6, 0.35, rng, tone=5000, tail=True)
    return fade(w, 0.02, 0.5)


def breath_behind(seed):
    rng = np.random.default_rng(seed)
    seconds = 2.6
    tt = t(seconds)
    x = bp(noise(seconds, rng), 250, 1600) * np.sin(np.pi * tt / seconds) ** 1.5
    x += 0.3 * bp(noise(seconds, rng), 2500, 5000) * np.sin(np.pi * tt / seconds) ** 3
    return fade(x, 0.3, 0.6)


def kill(seed):
    rng = np.random.default_rng(seed)
    seconds = 2.4
    tt = t(seconds)
    crunch = np.tanh(lp(noise(seconds, rng), 1400) * 6) * np.exp(-tt * 16)
    sub = thump(seconds, 150, 28, 1.8) * 2.0
    ring = np.sin(2 * np.pi * 5200 * tt) * 0.05 * np.exp(-tt * 1.2)
    return fade(crunch + sub + ring, 0.0, 0.6)


# ------------------------------------------------------------------ Steve
def breach(seed):
    """A memory forcing its way back: ringing ears, a reversed swell, then a drop."""
    rng = np.random.default_rng(seed)
    seconds = 5.5
    tt = t(seconds)
    ring = np.sin(2 * np.pi * 6100 * tt) * 0.12 * np.clip(tt / 2.5, 0, 1) * (tt < 4.2)
    swell = reverb(bell(233, 3.5, rng, 2.0) + bell(247, 3.5, rng, 2.0), 3.0, 0.9, rng, tail=False)[::-1]
    out = ring.copy()
    place(out, swell, 0.7, 0.9)
    for at in (2.9, 3.3, 3.55, 3.75, 3.9, 4.0, 4.08):  # stutter as it arrives
        place(out, static_burst(seed + int(at * 100))[: int(SR * 0.06)], at, 0.35)
    place(out, thump(1.3, 130, 30, 3.0) * 2.0, 4.2)
    return fade(out, 0.05, 0.4)


def panic_breath(seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 3.6))
    at = 0.0
    for _ in range(5):
        dur_in, dur_out = rng.uniform(0.22, 0.3), rng.uniform(0.3, 0.42)
        inhale = bp(rng.standard_normal(int(SR * dur_in)), 1400, 4200) * np.linspace(0.1, 1, int(SR * dur_in)) ** 2
        exhale = bp(rng.standard_normal(int(SR * dur_out)), 500, 2200) * np.linspace(1, 0, int(SR * dur_out)) ** 1.5
        tremble = 0.75 + 0.25 * np.sin(2 * np.pi * 17 * t(dur_out))[: len(exhale)]
        place(out, inhale, at, 0.7)
        place(out, exhale * tremble, at + dur_in + 0.02, 1.0)
        at += dur_in + dur_out + rng.uniform(0.05, 0.12)
    return fade(out, 0.01, 0.2)


def mutter(seed):
    """Low, wordless muttering: a buzzing source through wandering formants, far away."""
    rng = np.random.default_rng(seed)
    seconds = 3.4
    n = int(SR * seconds)
    tt = np.arange(n) / SR
    pitch = 98 + 9 * np.sin(2 * np.pi * 0.9 * tt) + 6 * lp(rng.standard_normal(n), 4) * 8
    phase = 2 * np.pi * np.cumsum(pitch) / SR
    src = signal.sawtooth(phase) * 0.6 + 0.25 * rng.standard_normal(n)
    vowels = [(600, 1040, 2250), (400, 1620, 2400), (500, 700, 2350), (320, 870, 2250), (640, 1190, 2390), (400, 2000, 2550)]
    rng.shuffle(vowels)
    seg = n // len(vowels)
    out = np.zeros(n)
    for i, (f1, f2, f3) in enumerate(vowels):
        a, b = i * seg, n if i == len(vowels) - 1 else (i + 1) * seg
        chunk = src[a:b]
        part = bp(chunk, f1 * 0.85, f1 * 1.15) + 0.5 * bp(chunk, f2 * 0.9, f2 * 1.1) + 0.2 * bp(chunk, f3 * 0.93, f3 * 1.07)
        syll = np.abs(np.sin(np.pi * np.linspace(0, rng.integers(1, 4), len(part)))) ** 0.7
        out[a:b] = part * syll
    out = lp(out, 2400)
    return fade(reverb(out, 1.4, 0.45, rng, tone=2200, tail=True), 0.05, 0.5)


# ------------------------------------------------------------------ ghosts and memory
A4 = 440.0


def note(semitones_from_a4):
    return A4 * 2 ** (semitones_from_a4 / 12)


def ghost_appear(seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 3.5))
    for i, semis in enumerate((7, 8, 15)):  # E5, F5, C6: a small cold cluster
        place(out, bell(note(semis), 3.0, rng, 0.7), 0.12 * i, 0.6)
    return fade(reverb(out, 3.5, 0.6, rng, tone=6000, tail=True), 0.01, 1.5)


def ghost_hum(seed):
    """Three voices holding a chord with nobody breathing."""
    rng = np.random.default_rng(seed)
    seconds = 10
    tt = t(seconds)
    out = np.zeros_like(tt)
    for semis in (-12, -5, 3):  # A3, E4, C5
        f = note(semis)
        vib = 1 + 0.004 * np.sin(2 * np.pi * rng.uniform(4.5, 5.5) * tt + rng.uniform(0, 6))
        phase = 2 * np.pi * np.cumsum(f * vib) / SR
        voice = signal.sawtooth(phase) + 0.02 * rng.standard_normal(len(tt))
        voice = bp(voice, 620, 900) + 0.6 * bp(voice, 1000, 1300) + 0.25 * bp(voice, 2500, 3100)
        out += voice * (0.6 + 0.4 * np.sin(2 * np.pi * rng.uniform(0.05, 0.12) * tt + rng.uniform(0, 6)))
    return loopable(reverb(out, 4.0, 0.6, rng, tone=3500, tail=False), 2.0)


def memory_fragment(seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 4.5))
    swell = reverb(bell(note(3), 2.5, rng, 1.2), 2.5, 0.9, rng, tail=False)[::-1]
    place(out, swell, 0.0, 0.7)
    place(out, bell(note(3), 2.5, rng), 2.4, 1.0)
    place(out, bell(note(10), 2.2, rng), 2.62, 0.6)
    return fade(reverb(out, 3.0, 0.45, rng, tone=6000, tail=True), 0.02, 1.5)


def theme(seed):
    """'Left Behind', a music-box waltz in A minor on a tape that has been played too often."""
    rng = np.random.default_rng(seed)
    beat = 60 / 76
    # (semitones from A4, beats). None is a rest.
    melody = [(7, 1), (0, 1), (3, 1), (2, 2), (None, 1), (7, 1), (0, 1), (5, 1), (3, 2), (None, 1),
              (8, 1), (7, 1), (3, 1), (2, 1), (0, 1), (-1, 1), (0, 3), (None, 2),
              (12, 1), (7, 1), (3, 1), (2, 2), (None, 1), (10, 1), (8, 1), (7, 1), (3, 2), (None, 1),
              (5, 1), (3, 1), (2, 1), (0, 1), (-1, 1), (-5, 1), (-12, 4)]
    bass = [-24, -24, -19, -19, -28, -28, -24, -24, -24, -24, -19, -19, -28, -29, -24, -24]
    total = sum(b for _, b in melody) * beat + 5
    out = np.zeros(int(SR * total))
    at = 0.4
    for semis, beats in melody:
        if semis is not None:
            place(out, bell(note(semis), 2.6, rng, 0.9), at + rng.uniform(-0.012, 0.012), 0.8)
        at += beats * beat
    for i, semis in enumerate(bass):
        when = 0.4 + i * 3 * beat
        if when < total - 4:
            place(out, bell(note(semis + 12), 3.0, rng, 0.4), when, 0.45)
    out = reverb(out, 3.2, 0.4, rng, tone=5000, tail=False)
    # tape wow and flutter: read the buffer back with a slowly wandering clock
    n = len(out)
    idx = np.arange(n) + 28 * np.sin(2 * np.pi * 0.31 * np.arange(n) / SR) + 3 * np.sin(2 * np.pi * 6.3 * np.arange(n) / SR)
    out = np.interp(np.clip(idx, 0, n - 1), np.arange(n), out)
    hiss = hp(rng.standard_normal(n), 3000) * 0.006
    crackle = np.zeros(n)
    for _ in range(int(total * 5)):
        crackle[rng.integers(0, n - 1)] = rng.uniform(-0.25, 0.25)
    out = lp(out, 6500) + hiss + lp(crackle, 5000)
    return fade(out, 0.05, 2.5)


WHISPERS = [
    [(400, 2000, 2550), (320, 870, 2250), (640, 1190, 2390)],
    [(500, 700, 2350), (400, 1620, 2400), (600, 1040, 2250), (320, 870, 2250)],
    [(640, 1190, 2390), (400, 2000, 2550), (500, 700, 2350), (400, 1620, 2400), (320, 870, 2250)],
]

if __name__ == "__main__":
    save("ambient/dread_1", dread(101, 43.65), quality=3, peak=0.7)
    save("ambient/dread_2", dread(102, 38.89), quality=3, peak=0.7)
    save("ambient/dread_3", dread(103, 49.0), quality=3, peak=0.7)
    save("ambient/wind_howl", wind_howl(111), quality=3, peak=0.7)
    save("ambient/static", static_burst(121), peak=0.7)
    save("ambient/knock", knock(131))
    save("ambient/page", page_rustle(141), peak=0.6)
    save("herobrine/presence", presence(201), quality=3, peak=0.75)
    save("herobrine/heartbeat", heartbeat(), peak=0.9)
    save("herobrine/sting", sting(211), peak=0.95)
    save("herobrine/chase", chase(221), quality=3, peak=0.85)
    save("herobrine/vanish", vanish(231))
    for i, vowels in enumerate(WHISPERS):
        save(f"herobrine/whisper_{i + 1}", whisper(240 + i, vowels, 1.4 + 0.5 * i), peak=0.7)
    save("herobrine/breath", breath_behind(251), peak=0.7)
    save("herobrine/kill", kill(261), peak=0.95)
    save("steve/breach", breach(301), peak=0.9)
    save("steve/panic_breath", panic_breath(311), peak=0.7)
    save("steve/mutter_1", mutter(321), peak=0.6)
    save("steve/mutter_2", mutter(322), peak=0.6)
    save("ghost/appear", ghost_appear(401), peak=0.7)
    save("ghost/hum", ghost_hum(411), quality=3, peak=0.6)
    save("memory/fragment", memory_fragment(501), peak=0.75)
    save("memory/theme", theme(511), quality=4, peak=0.75)
