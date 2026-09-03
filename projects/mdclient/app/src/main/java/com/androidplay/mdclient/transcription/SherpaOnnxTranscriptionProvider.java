package com.androidplay.mdclient.transcription;

import android.content.Context;
import com.androidplay.mdclient.audio.AudioFrameBus;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Sherpa-ONNX consumer. Reflection keeps the buildable shell usable without the optional AAR. */
public final class SherpaOnnxTranscriptionProvider implements TranscriptionProvider {
    private final File modelDir;
    private final AudioFrameBus bus;
    private AudioFrameBus.Subscription subscription;
    private Thread worker;
    private Object recognizer;
    private Object stream;
    private Listener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private long segmentStartFrame;
    private String lastText = "";

    public SherpaOnnxTranscriptionProvider(Context context, File modelDir, AudioFrameBus bus) {
        if (context == null || modelDir == null || bus == null) throw new NullPointerException("context/modelDir/bus");
        this.modelDir = modelDir;
        this.bus = bus;
    }

    @Override public synchronized void start(String sessionId, Listener listener) {
        if (running.get()) return;
        this.listener = listener;
        try {
            createRecognizer();
            subscription = bus.subscribe();
            running.set(true);
            worker = new Thread(this::decodeLoop, "sherpa-asr");
            worker.start();
        } catch (Exception e) {
            reportError("model_load", e.toString());
            release();
        }
    }

    @Override public void acceptAudio(short[] samples, long frameStart, long frameEnd, long audioTimeNs) {
        if (running.get()) decodeFrame(new AudioFrameBus.Frame(samples, frameStart, frameEnd, audioTimeNs));
    }

    private void decodeLoop() {
        while (running.get()) {
            try {
                AudioFrameBus.Frame frame = subscription.poll(250);
                if (frame != null) decodeFrame(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void decodeFrame(AudioFrameBus.Frame frame) {
        try {
            float[] samples = new float[frame.samples.length];
            for (int i = 0; i < samples.length; i++) samples[i] = frame.samples[i] / 32768.0f;
            invoke(stream, "acceptWaveform", new Class[]{float[].class, int.class}, samples, 16000);
            while ((Boolean) invoke(recognizer, "isReady", new Class[]{stream.getClass()}, stream))
                invoke(recognizer, "decode", new Class[]{stream.getClass()}, stream);
            Object result = invoke(recognizer, "getResult", new Class[]{stream.getClass()}, stream);
            String text = String.valueOf(invoke(result, "getText", new Class[0]));
            boolean endpoint = (Boolean) invoke(recognizer, "isEndpoint", new Class[]{stream.getClass()}, stream);
            if (!text.equals(lastText)) {
                lastText = text;
                emit(text, frame, endpoint);
            }
            if (endpoint) {
                invoke(recognizer, "reset", new Class[]{stream.getClass()}, stream);
                lastText = "";
                segmentStartFrame = frame.frameEnd;
            }
        } catch (Exception e) {
            reportError("decode", e.toString());
        }
    }

    private void emit(String text, AudioFrameBus.Frame frame, boolean isFinal) {
        if (listener != null) listener.onSegment(new TranscriptSegment(
                "sherpa-" + frame.frameEnd, "sherpa-onnx-1.13.7", segmentStartFrame,
                frame.frameEnd, frame.audioTimeNs, text, isFinal, null));
    }

    private void createRecognizer() throws Exception {
        Class<?> modelClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig");
        Object model = modelClass.getConstructor().newInstance();
        Class<?> paraClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig");
        Object para = paraClass.getConstructor().newInstance();
        File root = new File(modelDir, "sherpa-onnx-streaming-paraformer-bilingual-zh-en");
        invoke(para, "setEncoder", new Class[]{String.class}, new File(root, "encoder.int8.onnx").getAbsolutePath());
        invoke(para, "setDecoder", new Class[]{String.class}, new File(root, "decoder.int8.onnx").getAbsolutePath());
        invoke(model, "setParaformer", new Class[]{paraClass}, para);
        invoke(model, "setTokens", new Class[]{String.class}, new File(root, "tokens.txt").getAbsolutePath());
        invoke(model, "setModelType", new Class[]{String.class}, "paraformer");
        Class<?> configClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig");
        Object config = configClass.getConstructor().newInstance();
        invoke(config, "setModelConfig", new Class[]{modelClass}, model);
        invoke(config, "setEnableEndpoint", new Class[]{boolean.class}, true);
        recognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
                .getConstructor(android.content.res.AssetManager.class, configClass)
                .newInstance(null, config);
        stream = invoke(recognizer, "createStream", new Class[]{String.class}, "");
    }

    @Override public synchronized void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
        release();
    }

    private synchronized void release() {
        if (subscription != null) subscription.close();
        subscription = null;
        invokeQuiet(stream, "inputFinished");
        invokeQuiet(stream, "release");
        invokeQuiet(recognizer, "release");
        stream = null;
        recognizer = null;
    }

    private void reportError(String code, String detail) { if (listener != null) listener.onError(code, detail); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }
    private static void invokeQuiet(Object target, String name) { if (target != null) try { invoke(target, name, new Class[0]); } catch (Exception ignored) {} }
}
