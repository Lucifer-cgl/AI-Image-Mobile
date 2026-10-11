package com.aimangastudio.mobileimage;

public final class NativeGenerator {
    static { System.loadLibrary("mobile_image_jni"); }

    public interface ProgressListener {
        void onProgress(int percent, String stage);
    }

    public static native byte[] generate(String modelPath, String decoderPath, String prompt, String negative,
        int width, int height, int steps, long seed, byte[] initRgb, int initWidth,
        int initHeight, float strength, boolean storageMapped, ProgressListener listener);
    public static native String lastError();
    public static native void cancel();
    public static native void release();
    private NativeGenerator() {}
}
