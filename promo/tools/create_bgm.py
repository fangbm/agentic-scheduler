"""Create a deterministic, original 60-second Temvio promo score."""

from __future__ import annotations

import argparse
import math
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 48_000
TEMPO = 100
BEAT = 60.0 / TEMPO
CHORDS = [
    (48, (60, 64, 67, 74)),  # C add9
    (43, (59, 62, 67, 74)),  # G add9
    (41, (57, 60, 64, 69)),  # F maj7
    (45, (57, 60, 64, 67)),  # A minor 7
]


def midi_hz(note: int) -> float:
    return 440.0 * (2.0 ** ((note - 69) / 12.0))


def add_note(audio: np.ndarray, start: float, frequency: float, amplitude: float,
             duration: float, pan: float = 0.0, decay: float = 8.0,
             color: float = 0.0) -> None:
    first = max(0, int(round(start * SAMPLE_RATE)))
    count = min(int(round(duration * SAMPLE_RATE)), len(audio) - first)
    if count <= 0:
        return
    local = np.arange(count, dtype=np.float64) / SAMPLE_RATE
    envelope = np.exp(-decay * local)
    attack = np.minimum(1.0, local / 0.009)
    phase = 2.0 * np.pi * frequency * local
    signal = (np.sin(phase) + color * np.sin(phase * 2.003)) * envelope * attack * amplitude
    left = math.sqrt((1.0 - pan) * 0.5)
    right = math.sqrt((1.0 + pan) * 0.5)
    audio[first:first + count, 0] += signal * left
    audio[first:first + count, 1] += signal * right


def render(duration: float = 60.0) -> np.ndarray:
    audio = np.zeros((int(round(duration * SAMPLE_RATE)), 2), dtype=np.float64)
    chord_seconds = BEAT * 8
    chord_count = math.ceil(duration / chord_seconds)

    for index in range(chord_count):
        start = index * chord_seconds
        first = int(round(start * SAMPLE_RATE))
        count = min(int(round(chord_seconds * SAMPLE_RATE)), len(audio) - first)
        if count <= 0:
            continue
        local = np.arange(count, dtype=np.float64) / SAMPLE_RATE
        attack = np.minimum(1.0, local / 0.38)
        release = np.minimum(1.0, np.maximum(0.0, (chord_seconds - local) / 0.30))
        breathe = 0.90 + 0.10 * np.sin(2 * np.pi * 0.18 * local + index * 0.3)
        env = attack * release * breathe
        _, notes = CHORDS[index % len(CHORDS)]
        for voice, note in enumerate(notes):
            frequency = midi_hz(note)
            phase = 2 * np.pi * frequency * local
            tone = np.sin(phase) + 0.18 * np.sin(phase * 2.002 + voice * 0.08)
            level = 0.026 if voice < 3 else 0.018
            pan = (-0.34, -0.12, 0.15, 0.36)[voice]
            audio[first:first + count, 0] += tone * env * level * math.sqrt((1 - pan) * 0.5)
            audio[first:first + count, 1] += tone * env * level * math.sqrt((1 + pan) * 0.5)

    # A light repeating arpeggio supplies forward motion without crowding speech.
    arp_pattern = (0, 2, 1, 3, 2, 1, 3, 2)
    step_seconds = BEAT / 2
    steps = int(math.ceil(duration / step_seconds))
    for step in range(steps):
        start = step * step_seconds + (0.035 if step % 2 else 0.0)
        chord_index = int(start // chord_seconds) % len(CHORDS)
        _, chord = CHORDS[chord_index]
        note = chord[arp_pattern[step % len(arp_pattern)]] + 12
        add_note(audio, start, midi_hz(note), 0.075, 0.27,
                 pan=(-0.22 if step % 2 else 0.22), decay=13.0, color=0.32)

    # Rounded bass notes and a restrained kick on beats one and three.
    for beat_index in range(int(math.ceil(duration / BEAT))):
        start = beat_index * BEAT
        chord_index = int(start // chord_seconds) % len(CHORDS)
        root, _ = CHORDS[chord_index]
        add_note(audio, start, midi_hz(root), 0.082, 0.38,
                 pan=0.0, decay=7.0, color=0.08)
        if beat_index % 4 in (0, 2):
            count = min(int(0.20 * SAMPLE_RATE), len(audio) - int(start * SAMPLE_RATE))
            first = int(start * SAMPLE_RATE)
            if count > 0:
                local = np.arange(count, dtype=np.float64) / SAMPLE_RATE
                sweep = 74.0 - 28.0 * np.minimum(1.0, local / 0.16)
                phase = 2 * np.pi * np.cumsum(sweep) / SAMPLE_RATE
                kick = 0.09 * np.sin(phase) * np.exp(-24 * local)
                audio[first:first + count, 0] += kick
                audio[first:first + count, 1] += kick

    # Quiet deterministic noise ticks mark the backbeat; no sampled material.
    noise = np.random.default_rng(240929).standard_normal(int(0.055 * SAMPLE_RATE))
    noise *= np.exp(-72 * np.arange(len(noise), dtype=np.float64) / SAMPLE_RATE)
    noise -= np.convolve(noise, np.ones(15) / 15, mode="same")
    for beat_index in range(int(math.ceil(duration / BEAT))):
        if beat_index % 4 not in (1, 3):
            continue
        first = int(round(beat_index * BEAT * SAMPLE_RATE))
        count = min(len(noise), len(audio) - first)
        if count > 0:
            audio[first:first + count, 0] += noise[:count] * 0.020
            audio[first:first + count, 1] += noise[:count] * 0.020

    time = np.arange(len(audio), dtype=np.float64) / SAMPLE_RATE
    fade = np.minimum(1.0, np.minimum(time / 0.55, (duration - time) / 1.4)).clip(0, 1)
    audio *= fade[:, None]
    peak = float(np.max(np.abs(audio)))
    if peak > 0:
        audio *= 0.78 / peak
    audio = np.tanh(audio * 1.12) / np.tanh(1.12)
    return np.clip(audio, -1.0, 1.0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="artifacts/temvio-original-score.wav")
    parser.add_argument("--duration", type=float, default=60.0)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    pcm = (render(args.duration) * 32767).astype("<i2")
    with wave.open(str(output), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE)
        target.writeframes(pcm.tobytes())
    print(f"Created {output} ({args.duration:.1f}s, stereo, {SAMPLE_RATE}Hz)")


if __name__ == "__main__":
    main()
