package com.artifex.mupdf.viewer;

import android.annotation.SuppressLint;
import android.view.View;

public class Stepper {
    protected final View mPoster;
    protected final Runnable mTask;
    protected boolean mPending;

    public Stepper(View v, Runnable r) {
        mPoster = v;
        mTask = r;
        mPending = false;
    }

    @SuppressLint("NewApi")
    public void prod() {
        if (!mPending) {
            mPending = true;
            mPoster.postOnAnimation(() -> {
                mPending = false;
                mTask.run();
            });
        }
    }
}
