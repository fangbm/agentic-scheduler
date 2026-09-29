"""Create a deterministic, original 60-second Temvio promo score."""

from __future__ import annotations

import argparse
import math
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 48_000
TEMPO = 76
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
             duration: float, pan: float = 0.0, decay: float = 3.5,
             color: float = 0.0) -> None:
    first = max(0, int(round(start * SAMPLE_RATE)))
    count = min(int(round(duration * SAMPLE_RATE)), len(audio) - first)
    if count <= 0:
        return
    local = np.arange(count, dtype=np.float64) / SAMPLE_RATE
    envelope = np.exp(-decay * local)
    attack = np.minimum(1.0, local / 0.045)
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
        span = chord_seconds + 0.72
        count = min(int(round(span * SAMPLE_RATE)), len(audio) - first)
        if count <= 0:
            continue
        local = np.arange(count, dtype=np.float64) / SAMPLE_RATE
        attack = np.minimum(1.0, local / 0.72)
        release = np.minimum(1.0, np.maximum(0.0, (span - local) / 0.86))
        breathe = 0.96 + 0.04 * np.sin(2 * np.pi * 0.12 * local + index * 0.3)
        env = attack * release * breathe
        _, notes = CHORDS[index % len(CHORDS)]
        for voice, note in enumerate(notes):
            frequency = midi_hz(note)
            phase = 2 * np.pi * frequency * local
            tone = np.sin(phase) + 0.045 * np.sin(phase * 2.002 + voice * 0.08)
            level = 0.082 if voice < 3 else 0.058
            pan = (-0.20, -0.07, 0.08, 0.20)[voice]
            audio[first:first + count, 0] += tone * env * level * math.sqrt((1 - pan) * 0.5)
            audio[first:first + count, 1] += tone * env * level * math.sqrt((1 + pan) * 0.5)

    # Sparse, softly rounded notes add movement without a busy repeating pulse.
    melody_pattern = (2, 1, 3, 2, 0, 2, 1, 3)
    step_seconds = 1.58
    steps = int(math.ceil(duration / step_seconds))
    for step in range(steps):
        start = step * step_seconds
        chord_index = int(start // chord_seconds) % len(CHORDS)
        _, chord = CHORDS[chord_index]
        note = chord[melody_pattern[step % len(melody_pattern)]] + 12
        add_note(audio, start, midi_hz(note), 0.032, 0.72,
                 pan=(-0.12 if step % 2 else 0.12), decay=3.0, color=0.055)

    # Low, sustained root tones anchor each chord; no kick, snare, or noise layer.
    for index in range(chord_count):
        start = index * chord_seconds
        root, _ = CHORDS[index % len(CHORDS)]
        add_note(audio, start, midi_hz(root - 12), 0.036, chord_seconds + 0.55,
                 pan=0.0, decay=1.0, color=0.025)

    time = np.arange(len(audio), dtype=np.float64) / SAMPLE_RATE
    fade = np.minimum(1.0, np.minimum(time / 0.55, (duration - time) / 1.4)).clip(0, 1)
    audio *= fade[:, None]
    audio *= 1.45
    peak = float(np.max(np.abs(audio)))
    if peak > 0.82:
        audio *= 0.82 / peak
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
