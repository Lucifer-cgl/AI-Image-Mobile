package com.aimangastudio.planner;

public final class PlannerEngine {
    static { System.loadLibrary("planner_jni"); }
    public static native String plan(String modelPath, String systemPrompt, String userPrompt, int maxTokens);
    private PlannerEngine() {}
}
