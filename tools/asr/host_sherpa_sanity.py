import argparse
import os
import time
import wave

import numpy as np
import sherpa_onnx


def read_wave(path):
    with wave.open(path, "rb") as stream:
        if stream.getnchannels() != 1 or stream.getsampwidth() != 2:
            raise ValueError("WAV must be mono PCM16")
        samples = np.frombuffer(stream.readframes(stream.getnframes()), dtype=np.int16)
        return samples.astype(np.float32) / 32768.0, stream.getframerate()


def decode(recognizer, path):
    samples, rate = read_wave(path)
    stream = recognizer.create_stream()
    stream.accept_waveform(rate, samples)
    stream.accept_waveform(rate, np.zeros(int(rate * 0.66), dtype=np.float32))
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_streams([stream])
    result = recognizer.get_result(stream)
    return getattr(result, "text", result), len(samples) / rate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model_dir")
    parser.add_argument("wav", nargs="+")
    parser.add_argument("--escape", action="store_true")
    args = parser.parse_args()
    root = os.path.abspath(args.model_dir)
    recognizer = sherpa_onnx.OnlineRecognizer.from_paraformer(
        tokens=os.path.join(root, "tokens.txt"),
        encoder=os.path.join(root, "encoder.int8.onnx"),
        decoder=os.path.join(root, "decoder.int8.onnx"),
        num_threads=1,
        provider="cpu",
        sample_rate=16000,
        feature_dim=80,
        decoding_method="greedy_search",
    )
    started = time.perf_counter()
    total = 0.0
    print("HOST_RTF_ONLY")
    for path in args.wav:
        text, duration = decode(recognizer, path)
        total += duration
        print("file=%s" % path)
        print("duration_s=%.3f" % duration)
        value = text.encode("unicode_escape").decode() if args.escape else text
        print("transcript=%s" % value)
    elapsed = time.perf_counter() - started
    print("decode_elapsed_s=%.3f" % elapsed)
    print("rtf=%.4f" % (elapsed / total if total else 0.0))


if __name__ == "__main__":
    main()
