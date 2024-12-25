package com.artifex.mupdf.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.artifex.mupdf.fitz.Quad;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class SearchTask {
    private final Context mContext;
    private final MuPDFCore mCore;
    private final AlertDialog.Builder mAlertBuilder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SearchTask(Context context, MuPDFCore core) {
        mContext = context;
        mCore = core;
        mAlertBuilder = new AlertDialog.Builder(context);
    }

    protected abstract void onTextFound(SearchTaskResult result);

    public void search(final String text, int direction, int displayPage, int searchPage) {
        if (mCore == null)
            return;
        final int increment = direction;
        final int startIndex = searchPage == -1 ? displayPage : searchPage + increment;
        executor.execute(() -> {
            int index = startIndex;
            SearchTaskResult result = null;
            while (0 <= index && index < mCore.countPages()) {
                Quad[][] searchHits = mCore.searchPage(index, text);
                if (searchHits != null && searchHits.length > 0) {
                    result = new SearchTaskResult(text, index, searchHits);
                    break; // exit the loop if results are found
                }
                index += increment;
            }
            // update UI on the main thread
            SearchTaskResult finalResult = result;
            ((Activity) mContext).runOnUiThread(() -> {
                if (finalResult != null) {
                    onTextFound(finalResult);
                } else {
                    mAlertBuilder.setTitle(R.string.no_match_found);
                    AlertDialog alert = mAlertBuilder.create();
                    alert.setButton(AlertDialog.BUTTON_POSITIVE, mContext.getString(R.string.dismiss), (DialogInterface.OnClickListener) null);
                    alert.show();
                }
            });
        });
    }

}
