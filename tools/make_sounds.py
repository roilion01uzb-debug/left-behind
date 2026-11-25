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


def talk(seed):
    """Steve's stand-in voice: a short, soft, wordless murmur of two to four syllables."""
    rng = np.random.default_rng(seed)
    syllables = int(rng.integers(2, 5))
    seconds = 0.2 * syllables + 0.15
    n = int(SR * seconds)
    tt = np.arange(n) / SR
    pitch = 118 + 14 * np.sin(2 * np.pi * (0.8 + 0.2 * syllables) * tt + rng.uniform(0, 6)) - 10 * tt / seconds
    phase = 2 * np.pi * np.cumsum(pitch) / SR
    src = signal.sawtooth(phase) * 0.7 + 0.12 * rng.standard_normal(n)
    vowels = [(700, 1220, 2600), (400, 1900, 2550), (550, 880, 2500), (350, 800, 2300), (600, 1700, 2500)]
    rng.shuffle(vowels)
    seg = n // syllables
    out = np.zeros(n)
    for i in range(syllables):
        f1, f2, f3 = vowels[i % len(vowels)]
        a, b = i * seg, n if i == syllables - 1 else (i + 1) * seg
        chunk = src[a:b]
        part = bp(chunk, f1 * 0.85, f1 * 1.15) + 0.55 * bp(chunk, f2 * 0.9, f2 * 1.1) + 0.2 * bp(chunk, f3 * 0.93, f3 * 1.07)
        env = np.sin(np.pi * np.linspace(0, 1, len(part))) ** 0.6
        out[a:b] = part * env
    out = lp(out, 3000)
    return fade(reverb(out, 0.5, 0.15, rng, tone=2600, tail=True), 0.01, 0.12)


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


# ------------------------------------------------------------------ music (0.5: more like the theme)
def music_box(seed, melody, bass, bpm, bar=3, wow=28.0, flutter=3.0, octave=0, harmony=None, double=None,
              drop_every=0, drift=0.0, lowpass=6500, hiss=0.006, crackles=5, bass_gain=0.45, gain=0.8, space=3.2):
    """The theme's recipe with knobs: a music box on a worn tape. drift bends the whole tape down over the piece."""
    rng = np.random.default_rng(seed)
    beat = 60 / bpm
    total = sum(b for _, b in melody) * beat + 5
    out = np.zeros(int(SR * total))
    at = 0.4
    for i, (semis, beats) in enumerate(melody):
        if semis is not None and not (drop_every and i % drop_every == drop_every - 1):
            f = semis + 12 * octave
            place(out, bell(note(f), 2.6, rng, 0.9), at + rng.uniform(-0.012, 0.012), gain)
            if harmony is not None:
                place(out, bell(note(f + harmony), 2.4, rng, 0.6), at + rng.uniform(0.0, 0.02), gain * 0.4)
            if double is not None:
                place(out, bell(note(f + double), 2.4, rng, 1.1), at + rng.uniform(0.01, 0.04), gain * 0.28)
        at += beats * beat
    for i, semis in enumerate(bass):
        when = 0.4 + i * bar * beat
        if semis is not None and when < total - 4:
            place(out, bell(note(semis + 12), 3.0, rng, 0.4), when, bass_gain)
    out = reverb(out, space, 0.4, rng, tone=5000, tail=False)
    n = len(out)
    tt = np.arange(n) / SR
    idx = np.arange(n) + wow * np.sin(2 * np.pi * 0.31 * tt) + flutter * np.sin(2 * np.pi * 6.3 * tt)
    if drift:
        # read the tape slower and slower: the pitch sinks by `drift` semitones by the end
        rate = 2 ** (-drift * (tt / tt[-1]) ** 2 / 12)
        idx = np.cumsum(rate) - rate[0] + (idx - np.arange(n))
    out = np.interp(np.clip(idx, 0, n - 1), np.arange(n), out)
    noise_floor = hp(rng.standard_normal(n), 3000) * hiss
    crackle = np.zeros(n)
    for _ in range(int(total * crackles)):
        crackle[rng.integers(0, n - 1)] = rng.uniform(-0.25, 0.25)
    out = lp(out, lowpass) + noise_floor + lp(crackle, 5000)
    return fade(out, 0.05, 2.5)


def pad(seconds, freqs, rng, level=0.25):
    """A low held tone under the music box, breathing slowly."""
    tt = t(seconds)
    out = np.zeros_like(tt)
    for f in freqs:
        lfo = 0.6 + 0.4 * np.sin(2 * np.pi * rng.uniform(0.04, 0.09) * tt + rng.uniform(0, 6))
        out += lfo * (np.sin(2 * np.pi * f * tt) + 0.3 * np.sin(2 * np.pi * f * 2.003 * tt))
    return lp(out, 900) * level / len(freqs)


def campfire(seconds, rng):
    tt = t(seconds)
    rumble = lp(rng.standard_normal(len(tt)), 300) * 0.25
    pops = np.zeros_like(tt)
    for _ in range(int(seconds * 3)):
        at = rng.integers(0, len(tt) - 2000)
        k = rng.integers(200, 1400)
        pops[at:at + k] += rng.standard_normal(k) * np.exp(-np.linspace(0, 8, k)) * rng.uniform(0.1, 0.5)
    return lp(rumble, 400) + bp(pops, 900, 5000) * 0.5


THEME_MELODY = [(7, 1), (0, 1), (3, 1), (2, 2), (None, 1), (7, 1), (0, 1), (5, 1), (3, 2), (None, 1),
                (8, 1), (7, 1), (3, 1), (2, 1), (0, 1), (-1, 1), (0, 3), (None, 2),
                (12, 1), (7, 1), (3, 1), (2, 2), (None, 1), (10, 1), (8, 1), (7, 1), (3, 2), (None, 1),
                (5, 1), (3, 1), (2, 1), (0, 1), (-1, 1), (-5, 1), (-12, 4)]
THEME_BASS = [-24, -24, -19, -19, -28, -28, -24, -24, -24, -24, -19, -19, -28, -29, -24, -24]


def music_night(seed):
    """First dark nights: the theme's key, slower and sparser, a low drone underneath."""
    rng = np.random.default_rng(seed)
    melody = [(12, 2), (10, 1), (7, 3), (8, 2), (7, 1), (3, 3), (None, 3), (5, 2), (3, 1), (2, 3), (0, 2), (-1, 1), (0, 3), (None, 3),
              (7, 2), (8, 1), (10, 3), (12, 2), (10, 1), (7, 3), (None, 3), (3, 2), (2, 1), (0, 6), (None, 3)]
    bass = [-24, -28, -21, -29, -24, -28, -19, -29, -24, -24]
    box = music_box(seed, melody, bass, 58, gain=0.7, bass_gain=0.35, space=4.0)
    return fade(box + pad(len(box) / SR, [55.0, 82.4], rng, 0.18), 0.05, 3.0)


def music_underground(seed):
    """Deep in caves: pieces of the theme with long gaps, the tape sagging as it goes."""
    rng = np.random.default_rng(seed)
    melody = [(7, 1), (0, 1), (3, 1), (2, 3), (None, 4), (7, 1), (0, 1), (5, 1), (3, 3), (None, 5),
              (8, 1), (7, 1), (3, 1), (2, 1), (None, 6), (0, 4), (None, 4), (-1, 2), (-5, 2), (-12, 6)]
    box = music_box(seed, melody, [-24, None, -28, None, -24], 50, wow=70, flutter=5, drift=1.2, lowpass=4200,
                    hiss=0.008, crackles=7, gain=0.65, space=5.0)
    drips = np.zeros_like(box)
    for _ in range(9):
        place(drips, bell(rng.choice([1760.0, 1975.5, 2349.3]) * rng.uniform(0.97, 1.03), 0.6, rng, 1.8), rng.uniform(1, len(box) / SR - 3), 0.12)
    drips = reverb(drips, 4.5, 0.85, rng, tone=6000, tail=False)
    return fade(box + drips + pad(len(box) / SR, [41.2, 61.7], rng, 0.2), 0.05, 3.0)


def music_fire(seed):
    """By the fire, when he trusts you: the one warm piece. C major, gentle, the tape in better shape."""
    rng = np.random.default_rng(seed)
    melody = [(3, 1), (7, 1), (10, 1), (12, 2), (10, 1), (7, 2), (5, 1), (3, 3), (5, 1), (7, 1), (8, 1), (10, 2), (8, 1), (7, 3), (None, 3),
              (3, 1), (7, 1), (10, 1), (15, 2), (12, 1), (10, 2), (7, 1), (8, 3), (7, 1), (5, 1), (3, 1), (2, 2), (-2, 1), (3, 6), (None, 3)]
    bass = [-21, -24, -28, -26, -21, -24, -28, -26, -21, -21]
    box = music_box(seed, melody, bass, 80, wow=14, flutter=1.5, harmony=-5, lowpass=5200, hiss=0.004, crackles=2,
                    gain=0.7, bass_gain=0.4, space=2.6)
    return fade(box + campfire(len(box) / SR, rng) * 0.35, 0.05, 3.0)


def music_flashback(seed):
    """A memory: the theme played backwards, then its first bars forwards, as if the tape caught."""
    forward = theme(511)
    back = forward[::-1].copy()
    first = forward[: int(SR * 11)]
    first = fade(first, 0.0, 2.0)
    out = np.concatenate([back[int(SR * 2):], np.zeros(int(SR * 0.4)), first])
    rng = np.random.default_rng(seed)
    n = len(out)
    tt = np.arange(n) / SR
    idx = np.arange(n) + 60 * np.sin(2 * np.pi * 0.23 * tt)
    out = np.interp(np.clip(idx, 0, n - 1), np.arange(n), out)
    return fade(lp(out, 5000) + hp(rng.standard_normal(n), 3000) * 0.008, 0.5, 2.0)


def music_near(seed):
    """He is near: one held note that slowly bends down, and a high one bending up to meet nothing."""
    rng = np.random.default_rng(seed)
    seconds = 24
    tt = t(seconds)
    bend = np.clip((tt - 6) / 14, 0, 1)
    low = note(-12) * 2 ** (-bend * 1.0 / 12)
    high = note(24) * 2 ** (bend * 0.5 / 12)
    voice = np.zeros_like(tt)
    for f, amp in ((low, 1.0), (low * 1.004, 0.6), (low * 2, 0.25), (high, 0.07)):
        phase = 2 * np.pi * np.cumsum(f) / SR
        voice += amp * np.sin(phase)
    swell = np.clip(tt / 5, 0, 1) * np.clip((seconds - tt) / 4, 0, 1)
    sub = np.sin(2 * np.pi * 36.7 * tt) * 0.4 * swell
    out = reverb(lp(voice, 2500) * swell * 0.5 + sub, 4.0, 0.5, rng, tone=3000, tail=False)
    return fade(out, 0.2, 2.0)


def music_act(seed, act):
    """The theme again at each act card, a little more broken each time."""
    if act == 2:
        return music_box(seed, THEME_MELODY, THEME_BASS, 70, harmony=-5, space=3.4)
    if act == 3:
        return music_box(seed, THEME_MELODY, THEME_BASS, 68, octave=-1, double=1, wow=45, flutter=4, lowpass=5000, hiss=0.008)
    melody = THEME_MELODY[:-1] + [(2, 6)]
    return music_box(seed, melody, THEME_BASS, 52, drop_every=3, wow=60, flutter=5, drift=1.5, lowpass=4500, hiss=0.01, crackles=8)


# ------------------------------------------------------------------ 0.6: the world around you
def step(rng, surface="grass"):
    """One footstep: a heel and a softer toe, crunch for grass, click for stone."""
    out = np.zeros(int(SR * 0.32))
    for at, gain, length in ((0.0, 1.0, 0.09), (0.07 + rng.uniform(0, 0.03), 0.55, 0.12)):
        n = noise(length, rng) * np.exp(-t(length) * (38 if surface == "grass" else 60))
        if surface == "grass":
            n = bp(n, 300 + rng.uniform(0, 200), 2600 + rng.uniform(0, 900))
            n *= 0.6 + 0.4 * (rng.random(len(n)) > 0.6)  # the crunch of stems
        else:
            n = bp(n, 700, 5000) + 0.5 * thump(length, 160, 90, 45)
        place(out, n, at, gain)
    return out


def stalk_step(seed):
    rng = np.random.default_rng(seed)
    x = step(rng, "grass")
    return fade(reverb(lp(x, 4200), 0.8, 0.2, rng, tone=3000), 0.001, 0.1)


def torch_out(seed):
    """A flame pinched out: a short breathy puff, a last crackle, a little smoke."""
    rng = np.random.default_rng(seed)
    seconds = 0.9
    tt = t(seconds)
    puff = hp(noise(seconds, rng), 900) * np.exp(-tt * 9) * (1 - np.exp(-tt * 200))
    out = puff * 0.8
    for _ in range(3 + rng.integers(0, 3)):
        c = hp(noise(0.012, rng), 2500) * np.exp(-t(0.012) * 300)
        place(out, c, rng.uniform(0.02, 0.25), rng.uniform(0.3, 0.8))
    out += 0.25 * thump(seconds, 120, 70, 20)
    return fade(reverb(out, 1.4, 0.35, rng, tone=2500), 0.002, 0.3)


def stone_grind(seed):
    """Something heavy made of stone, moving where nothing should move."""
    rng = np.random.default_rng(seed)
    seconds = 2.2
    tt = t(seconds)
    brown = np.cumsum(noise(seconds, rng))
    brown = hp(brown - np.mean(brown), 30)
    g = bp(brown, 50, 420 + rng.uniform(0, 120))
    grains = (rng.random(len(tt)) > 0.997).astype(float)
    grains = bp(np.convolve(grains, np.exp(-np.arange(400) / 60.0), mode="same"), 300, 2200)
    env = np.sin(np.pi * tt / seconds) ** 0.7
    out = (g / (np.max(np.abs(g)) + 1e-9) + 0.6 * grains / (np.max(np.abs(grains)) + 1e-9)) * env
    out += 0.4 * thump(seconds, 70, 40, 3)
    return fade(reverb(out, 2.0, 0.3, rng, tone=1800), 0.05, 0.4)


def far_steps(seed):
    """Someone walking, a long way off. Not towards you. Probably."""
    rng = np.random.default_rng(seed)
    count = 4 + int(rng.integers(0, 4))
    gap = rng.uniform(0.42, 0.62)
    surface = "stone" if rng.random() < 0.4 else "grass"
    out = np.zeros(int(SR * (count * gap + 2.5)))
    for i in range(count):
        place(out, step(rng, surface), max(0.0, i * gap + rng.uniform(-0.02, 0.02)), 0.6 + 0.4 * i / count)
    return fade(reverb(lp(out, 1600), 2.6, 0.55, rng, tone=1400), 0.01, 0.8)


def far_mining(seed):
    """A pickaxe on stone somewhere in the dark: a few hits, a pause, one more."""
    rng = np.random.default_rng(seed)
    hits = 3 + int(rng.integers(0, 4))
    out = np.zeros(int(SR * (hits * 0.9 + 3.5)))
    at = 0.05
    ring = rng.uniform(1100, 1900)
    for i in range(hits):
        h = np.zeros(int(SR * 0.2))
        h[: int(SR * 0.08)] += bp(noise(0.08, rng), 900, 4800) * np.exp(-t(0.08) * 70)
        h += 0.5 * np.sin(2 * np.pi * ring * t(0.2)) * np.exp(-t(0.2) * 32)
        h += 0.5 * thump(0.2, 220, 110, 35)
        place(out, h, at, rng.uniform(0.7, 1.0))
        at += rng.uniform(0.55, 0.85) + (0.9 if i == hits - 2 and rng.random() < 0.5 else 0)
    return fade(reverb(lp(out, 2800), 3.2, 0.55, rng, tone=2000), 0.002, 1.0)


def drip(seed):
    """A drop of water in a cave: a quick rising plink, then the cave answers."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 2.4))
    drops = 1 + int(rng.random() < 0.4)
    for k in range(drops):
        seconds = 0.06
        tt = t(seconds)
        f0 = rng.uniform(700, 1100)
        freq = f0 * (1 + 1.4 * tt / seconds)
        d = np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-tt * 60)
        place(out, d, 0.02 + k * rng.uniform(0.4, 0.9), 1.0 - 0.3 * k)
    return fade(reverb(out, 2.2, 0.5, rng, tone=4500), 0.001, 0.6)


def creak(seed):
    """Wood taking weight: a stick-slip buzz that bends in pitch, through a hollow body."""
    rng = np.random.default_rng(seed)
    seconds = rng.uniform(0.8, 1.7)
    tt = t(seconds)
    f = rng.uniform(28, 55) + rng.uniform(15, 45) * np.sin(np.pi * tt / seconds * rng.uniform(0.6, 1.4) + rng.uniform(0, 1))
    phase = np.cumsum(f) / SR
    pulses = (np.mod(phase, 1.0) < 0.08).astype(float) * (0.6 + 0.4 * rng.random(len(tt)))
    body = bp(pulses, 250, 1400) + 0.6 * bp(pulses, 1800, 3200)
    env = np.sin(np.pi * tt / seconds) ** 0.5 * (0.7 + 0.3 * np.sin(2 * np.pi * 3 * tt))
    return fade(reverb(body * env, 0.9, 0.25, rng, tone=2500), 0.02, 0.15)


def door_far(seed):
    """A door somewhere else: a short creak and a heavy close, muffled by distance."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 3.5))
    place(out, creak(seed + 1000), 0.0, 0.6)
    shut = 1.0 * thump(0.4, 140, 60, 18) + 0.4 * bp(noise(0.4, rng), 200, 1200) * np.exp(-t(0.4) * 25)
    place(out, shut, rng.uniform(0.7, 1.2))
    return fade(reverb(lp(out, 1200), 2.4, 0.5, rng, tone=1200), 0.01, 0.8)


def knock_pattern(seed, knocks, spacing):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * (knocks * spacing + 1.2)))
    for i in range(knocks):
        body = bp(rng.standard_normal(int(SR * 0.25)), 120, 420) * np.exp(-t(0.25) * 38)
        body += 0.8 * thump(0.25, 190 - 10 * i, 95, 30)
        place(out, body, 0.05 + i * spacing * rng.uniform(0.9, 1.1), 1.0 - 0.1 * i)
    return fade(reverb(out, 0.9, 0.3, rng, tone=1800, tail=False), 0.001, 0.2)


def sfx_06():
    for i in range(1, 7):
        save(f"herobrine/step_{i}", stalk_step(700 + i), peak=0.7)
    for i in range(1, 5):
        save(f"ambient/torch_out_{i}", torch_out(710 + i), peak=0.7)
    for i in range(1, 4):
        save(f"ambient/stone_grind_{i}", stone_grind(720 + i), peak=0.8)
    for i in range(1, 7):
        save(f"ambient/far_steps_{i}", far_steps(730 + i), peak=0.6)
    for i in range(1, 7):
        save(f"ambient/far_mining_{i}", far_mining(740 + i), peak=0.6)
    for i in range(1, 7):
        save(f"ambient/drip_{i}", drip(750 + i), peak=0.6)
    for i in range(1, 9):
        save(f"ambient/creak_{i}", creak(760 + i), peak=0.6)
    for i in range(1, 5):
        save(f"ambient/door_far_{i}", door_far(770 + i), peak=0.6)
    for i, (k, sp) in enumerate(((2, 0.5), (4, 0.33), (3, 0.6), (5, 0.28)), start=2):
        save(f"ambient/knock_{i}", knock_pattern(780 + i, k, sp))
    for i in range(2, 5):
        save(f"herobrine/breath_{i}", breath_behind(790 + i), peak=0.7)
    for i in range(2, 5):
        save(f"ambient/wind_howl_{i}", wind_howl(800 + i), quality=3, peak=0.7)


# ------------------------------------------------------------------ 0.7: sounds that belong to this story
# Steve's note-block tune, in note-block steps (0 is F#3), -1 is a rest. Same as SteveChores.TUNE.
STEVE_TUNE = [13, 6, 9, 8, -1, 13, 6, 11, 9, -1, 14, 13, 9, 8, 6, 5, 6, -1]
F_SHARP_3 = 184.997
# The title-screen seed people used to swear Herobrine came from. The tones in lore/seed are its digits.
OLD_SEED = "478868574082066804"


def harp(freq, seconds, rng, bright=1.0):
    """A note block: a plucked string, its upper harmonics dying first."""
    tt = t(seconds)
    out = np.zeros_like(tt)
    for k in range(1, 9):
        out += (bright ** (k - 1)) / k ** 1.25 * np.sin(2 * np.pi * freq * k * (1 + rng.uniform(-0.001, 0.001)) * tt) \
            * np.exp(-tt * (2.2 + 1.9 * k))
    out[: int(SR * 0.003)] *= np.linspace(0, 1, int(SR * 0.003))
    return out


def tune_far(seed, notes, beat):
    """Steve's tune on a note block somewhere you can't see: thin, distant, in a big space."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * (len(notes) * beat + 4)))
    for i, n in enumerate(notes):
        if n >= 0:
            place(out, harp(F_SHARP_3 * 2 ** (n / 12), 1.6, rng, 0.55), 0.1 + i * beat + rng.uniform(-0.01, 0.01), 0.9)
    return fade(reverb(lp(out, 1500), 3.6, 0.62, rng, tone=1600), 0.01, 1.2)


def tune_wrong(seed):
    """The same tune, played by someone who only heard it once: wrong notes, wrong gaps, stuck on one note."""
    rng = np.random.default_rng(seed)
    notes = [n for n in STEVE_TUNE[: 10 + int(rng.integers(0, 6))]]
    for _ in range(3):
        i = int(rng.integers(0, len(notes)))
        if notes[i] >= 0:
            notes[i] += int(rng.choice([-1, 1, -2]))  # close, never right
    stuck = int(rng.integers(3, len(notes) - 2))
    notes = notes[:stuck] + [notes[stuck]] * 3 + notes[stuck + 1:]
    out = np.zeros(int(SR * (len(notes) * 0.62 + 5)))
    at = 0.1
    for n in notes:
        if n >= 0:
            place(out, harp(F_SHARP_3 * 2 ** (n / 12) * 0.5, 2.0, rng, 0.5), at, 0.9)
        at += rng.uniform(0.38, 0.85)
    return fade(reverb(lp(out, 1300), 3.8, 0.6, rng, tone=1400), 0.01, 1.5)


def winding_down(seed):
    """The theme on its music box, running down: slower, lower, then the spring gives out."""
    rng = np.random.default_rng(seed)
    melody = THEME_MELODY[: 12 + int(rng.integers(0, 6))]
    out = music_box(seed, melody, THEME_BASS[:6], 84, drift=7.0 + rng.uniform(0, 3), wow=36, lowpass=4200, crackles=3)
    clicks = np.zeros(int(SR * 1.6))
    for k in range(5):
        c = bp(noise(0.02, rng), 2500, 7000) * np.exp(-t(0.02) * 200)
        place(clicks, c, 0.15 + k * (0.12 + 0.05 * k), 0.5 - 0.08 * k)
    tail = np.concatenate([out[: int(len(out) * 0.86)], np.zeros(int(SR * 0.2))])
    return fade(np.concatenate([fade(tail, 0.0, 0.4), clicks]), 0.02, 0.3)


def stone_knock(rng, pitch=1.0):
    k = np.zeros(int(SR * 0.3))
    place(k, bp(noise(0.05, rng), 400 * pitch, 3200 * pitch) * np.exp(-t(0.05) * 110), 0.0, 1.0)
    place(k, thump(0.3, 260 * pitch, 120 * pitch, 34), 0.0, 0.9)
    return k


def count(seed):
    """Seven knocks on stone, evenly, like counting. A wait. Then an eighth, closer."""
    rng = np.random.default_rng(seed)
    gap = rng.uniform(0.55, 0.7)
    pitch = rng.uniform(0.85, 1.1)
    out = np.zeros(int(SR * (7 * gap + 2.4 + 2.0)))
    for i in range(7):
        place(out, stone_knock(rng, pitch), 0.05 + i * gap, 0.75)
    far = reverb(lp(out, 2600), 1.8, 0.45, rng, tone=2000)
    near = np.zeros_like(far)
    place(near, stone_knock(rng, pitch * 0.96), 0.05 + 6 * gap + 2.4, 1.0)
    return fade(far + reverb(near, 1.0, 0.25, rng, tone=3000, tail=False)[: len(far)], 0.001, 0.6)


def place_block(rng, kind):
    """One block put down: a soft thud and the grit of it settling."""
    seconds = 0.22
    body = thump(seconds, 170 if kind == "stone" else 120, 70, 28)
    grit = bp(noise(seconds, rng), 250 if kind == "dirt" else 600, 2400 if kind == "dirt" else 4200) * np.exp(-t(seconds) * 30)
    return body * 0.8 + grit * (0.7 if kind == "dirt" else 0.5)


def building_far(seed):
    """Somebody building, a long way off. A row of blocks, a pause, a jump and another, as if pillaring up."""
    rng = np.random.default_rng(seed)
    kind = rng.choice(["stone", "dirt", "wood"])
    placements = []
    at = 0.1
    for _ in range(int(rng.integers(6, 11))):
        placements.append(at)
        at += rng.uniform(0.28, 0.4) if rng.random() < 0.7 else rng.uniform(0.9, 1.6)
    out = np.zeros(int(SR * (at + 3.0)))
    for p in placements:
        place(out, place_block(rng, kind), p, rng.uniform(0.7, 1.0))
    return fade(reverb(lp(out, 1100), 3.0, 0.55, rng, tone=1200), 0.01, 1.0)


def digging_down(seed):
    """Somebody digging straight down. Each block a little deeper, a little further, until you can't hear it."""
    rng = np.random.default_rng(seed)
    blocks = int(rng.integers(4, 7))
    out = np.zeros(int(SR * (blocks * 1.5 + 3)))
    at = 0.05
    for b in range(blocks):
        depth = b / blocks
        stone = b >= 1
        for _ in range(int(rng.integers(2, 5)) if stone else 2):
            h = np.zeros(int(SR * 0.22))
            h[: int(SR * 0.06)] += bp(noise(0.06, rng), 500 if stone else 200, 3800 if stone else 1500) * np.exp(-t(0.06) * 70)
            h += 0.6 * thump(0.22, 200 if stone else 130, 90, 32)
            place(out, lp(h, 2600 - 1800 * depth), at, 1.0 - 0.75 * depth)
            at += rng.uniform(0.22, 0.32)
        # the block gives, and something drops a block lower
        place(out, lp(thump(0.3, 110, 50, 20), 900) * 0.5, at, 1.0 - 0.75 * depth)
        at += rng.uniform(0.45, 0.8)
    return fade(reverb(out, 2.4, 0.5, rng, tone=1500), 0.002, 1.0)


def seed_tone(seed):
    """A world before its seed: a low held fifth, and the digits of an old seed read out as soft tones."""
    rng = np.random.default_rng(seed)
    seconds = 16
    tt = t(seconds)
    hold = np.zeros_like(tt)
    for f, a in ((55.0, 1.0), (82.41, 0.55), (110.0, 0.35)):
        hold += a * np.sin(2 * np.pi * f * tt + rng.uniform(0, 6)) * (0.7 + 0.3 * np.sin(2 * np.pi * rng.uniform(0.05, 0.1) * tt))
    hold = lp(hold, 400) * np.minimum(1, tt / 3) * np.minimum(1, (seconds - tt) / 4)
    pips = np.zeros_like(tt)
    scale = [0, 2, 3, 5, 7, 8, 10, 12, 14, 15]  # A minor, the theme's key
    digits = OLD_SEED if seed % 2 else OLD_SEED[::-1]
    for i, d in enumerate(digits):
        f = 440.0 * 2 ** (scale[int(d)] / 12)
        pt = t(0.5)
        pip = np.sin(2 * np.pi * f * pt) * np.exp(-pt * 6) * np.minimum(1, pt / 0.01)
        place(pips, pip, 2.0 + i * 0.62, 0.22)
    return fade(hold * 0.8 + reverb(pips, 3.0, 0.6, rng, tone=3000, tail=False), 0.5, 2.0)


def reset_sound(seed):
    """The world forgetting itself: a breath drawn backwards, a dead stop, and everything clicking back in."""
    rng = np.random.default_rng(seed)
    swell = reverb(bp(noise(1.2, rng), 300, 6000) * np.exp(-t(1.2) * 3), 1.2, 0.8, rng, tail=False)[::-1]
    out = np.zeros(int(SR * 2.6))
    place(out, fade(swell, 0.05, 0.0), 0.0, 0.7)
    cut = int(SR * 1.2)
    out[cut: cut + int(SR * 0.18)] = 0
    back = np.zeros(int(SR * 1.2))
    for _ in range(40):  # every block rebuilt at once
        c = bp(noise(0.012, rng), 1200, 7000) * np.exp(-t(0.012) * 300)
        place(back, c, rng.uniform(0, 0.25) ** 1.5, rng.uniform(0.2, 0.6))
    place(back, thump(0.6, 90, 40, 9), 0.0, 0.9)
    place(out, back, 1.38, 1.0)
    return fade(out, 0.01, 0.4)


def hum(seed, notes, stop_early=False):
    """Steve humming his tune with his mouth closed, a little off, a long way inside his head."""
    rng = np.random.default_rng(seed)
    beat = 0.5
    seconds = len(notes) * beat + 2.5
    tt = t(seconds)
    freq = np.full(len(tt), np.nan)
    gate = np.zeros(len(tt))
    for i, n in enumerate(notes):
        if n < 0:
            continue
        a, b = int(SR * (0.2 + i * beat)), int(SR * (0.2 + (i + 0.92) * beat))
        freq[a:b] = F_SHARP_3 / 2 * 2 ** (n / 12) * (1 + rng.uniform(-0.008, 0.008))
        gate[a:b] = 1
    if stop_early:
        cut = int(len(tt) * rng.uniform(0.45, 0.6))
        gate[cut:] = 0
    # glide between notes, hold the last pitch through rests
    idx = np.where(~np.isnan(freq), np.arange(len(tt)), 0)
    np.maximum.accumulate(idx, out=idx)
    freq = np.nan_to_num(freq[idx], nan=150.0)
    freq = signal.savgol_filter(freq, int(SR * 0.06) | 1, 2)
    freq *= 1 + 0.006 * np.sin(2 * np.pi * 5.2 * tt)
    gate = lp(gate, 18, order=2)
    src = signal.sawtooth(2 * np.pi * np.cumsum(freq) / SR)
    voice = lp(src, 900) * 0.8 + bp(src, 230, 320) * 1.2 + bp(src, 1900, 2300) * 0.15  # "mmm": nasal, closed mouth
    voice += 0.03 * bp(rng.standard_normal(len(tt)), 300, 2000)
    return fade(reverb(voice * gate, 2.2, 0.4, rng, tone=2500), 0.05, 0.8)


def door_open(seed):
    """A wooden door opening slowly by itself: the latch, a long creak, a soft bump against the wall. Then nothing."""
    rng = np.random.default_rng(seed)
    seconds = rng.uniform(2.2, 2.8)
    tt = t(seconds)
    f = 22 + 30 * (tt / seconds) ** 0.7 + 6 * np.sin(2 * np.pi * 0.8 * tt)
    phase = np.cumsum(f) / SR
    pulses = (np.mod(phase, 1.0) < 0.07).astype(float) * (0.6 + 0.4 * rng.random(len(tt)))
    body = bp(pulses, 220, 1300) + 0.5 * bp(pulses, 1700, 3000)
    body *= np.sin(np.pi * tt / seconds) ** 0.4
    out = np.zeros(int(SR * (seconds + 1.6)))
    latch = np.zeros(int(SR * 0.08))
    place(latch, bp(noise(0.03, rng), 1500, 6000) * np.exp(-t(0.03) * 150), 0.0, 1.0)
    latch += 0.4 * thump(0.08, 600, 300, 60)
    place(out, latch, 0.0, 0.8)
    place(out, body, 0.12, 0.9)
    place(out, thump(0.3, 110, 60, 22) + 0.3 * bp(noise(0.3, rng), 150, 900) * np.exp(-t(0.3) * 25), 0.12 + seconds, 0.5)
    return fade(reverb(out, 1.4, 0.3, rng, tone=2200), 0.001, 0.5)


def presence_lore(seed):
    """He is near: the theme's first three notes, so slow and low they are hardly notes, over seven heavy pulses."""
    rng = np.random.default_rng(seed)
    seconds = 11
    out = np.zeros(int(SR * seconds))
    for i, semis in enumerate((7, 0, 3)):
        tone = bell(note(semis - 19), 4.5, rng, 0.5)
        swell = reverb(tone, 3.5, 0.9, rng, tail=False)[::-1]
        place(out, swell, 0.3 + i * 2.6, 0.55)
    for i in range(7):
        place(out, lp(thump(0.5, 70, 36, 9), 300), 0.6 + i * 1.3, 0.9)
    return fade(reverb(out, 3.0, 0.35, rng, tone=2500, tail=False), 1.0, 2.5)


def sfx_07():
    save("lore/tune_far_1", tune_far(901, STEVE_TUNE[:10], 0.42), peak=0.7)
    save("lore/tune_far_2", tune_far(902, STEVE_TUNE[10:], 0.42), peak=0.7)
    save("lore/tune_far_3", tune_far(903, STEVE_TUNE, 0.55), peak=0.7)
    for i in range(1, 4):
        save(f"lore/tune_wrong_{i}", tune_wrong(910 + i), peak=0.7)
    for i in range(1, 3):
        save(f"lore/winding_down_{i}", winding_down(920 + i), peak=0.7)
    for i in range(1, 4):
        save(f"lore/count_{i}", count(930 + i), peak=0.8)
    for i in range(1, 5):
        save(f"lore/building_far_{i}", building_far(940 + i), peak=0.7)
    for i in range(1, 4):
        save(f"lore/digging_down_{i}", digging_down(950 + i), peak=0.7)
    for i in range(1, 3):
        save(f"lore/seed_{i}", seed_tone(960 + i), quality=3, peak=0.65)
    for i in range(1, 3):
        save(f"lore/reset_{i}", reset_sound(970 + i), peak=0.8)
    save("lore/hum_1", hum(981, STEVE_TUNE[:10]), peak=0.65)
    save("lore/hum_2", hum(982, STEVE_TUNE[10:] + STEVE_TUNE[:5]), peak=0.65)
    save("lore/hum_3", hum(983, STEVE_TUNE, stop_early=True), peak=0.65)
    for i in range(1, 3):
        save(f"lore/door_open_{i}", door_open(990 + i), peak=0.7)
    save("herobrine/presence", presence_lore(999), quality=3, peak=0.75)


WHISPERS = [
    [(400, 2000, 2550), (320, 870, 2250), (640, 1190, 2390)],
    [(500, 700, 2350), (400, 1620, 2400), (600, 1040, 2250), (320, 870, 2250)],
    [(640, 1190, 2390), (400, 2000, 2550), (500, 700, 2350), (400, 1620, 2400), (320, 870, 2250)],
]

if __name__ == "__main__":
    import sys
    if sys.argv[1:] == ["07"]:
        sfx_07()
        sys.exit(0)
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
    # herobrine/whisper_* and steve/mutter_* are real words now: tools/make_voice.py --effects
    save("herobrine/breath", breath_behind(251), peak=0.7)
    save("herobrine/kill", kill(261), peak=0.95)
    save("steve/breach", breach(301), peak=0.9)
    save("steve/panic_breath", panic_breath(311), peak=0.7)
    save("ghost/appear", ghost_appear(401), peak=0.7)
    save("ghost/hum", ghost_hum(411), quality=3, peak=0.6)
    save("memory/fragment", memory_fragment(501), peak=0.75)
    save("memory/theme", theme(511), quality=4, peak=0.75)
    save("music/night", music_night(601), quality=4, peak=0.7)
    save("music/underground", music_underground(611), quality=4, peak=0.7)
    save("music/fire", music_fire(621), quality=4, peak=0.7)
    save("music/flashback", music_flashback(631), quality=4, peak=0.7)
    save("music/near", music_near(641), quality=4, peak=0.7)
    for act in (2, 3, 4):
        save(f"music/act_{act}", music_act(650 + act, act), quality=4, peak=0.75)
    sfx_06()
    sfx_07()
