package com.artifex.mupdf.viewer;

public interface CancellableTaskDefinition<Params, Result> {
    Result doInBackground(Params... params);

    void doCancel();

    void doCleanup();
}
