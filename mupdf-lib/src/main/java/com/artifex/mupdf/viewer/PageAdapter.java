package com.artifex.mupdf.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PageAdapter extends BaseAdapter {
    private final Context mContext;
    private final MuPDFCore mCore;
    private final SparseArray<PointF> mPageSizes = new SparseArray<>();
    private Bitmap mSharedHqBm;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public PageAdapter(Context c, MuPDFCore core) {
        mContext = c;
        mCore = core;
    }

    public int getCount() {
        try {
            return mCore.countPages();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public Object getItem(int position) {
        return null;
    }

    public long getItemId(int position) {
        return 0;
    }

    public synchronized void releaseBitmaps() {
        //  recycle and release the shared bitmap.
        if (mSharedHqBm != null)
            mSharedHqBm.recycle();
        mSharedHqBm = null;
    }

    public void refresh() {
        mPageSizes.clear();
    }

    public synchronized View getView(final int position, View convertView, ViewGroup parent) {
        final PageView pageView;
        if (convertView == null) {
            if (mSharedHqBm == null || mSharedHqBm.getWidth() != parent.getWidth() || mSharedHqBm.getHeight() != parent.getHeight()) {
                if (parent.getWidth() > 0 && parent.getHeight() > 0)
                    mSharedHqBm = Bitmap.createBitmap(parent.getWidth(), parent.getHeight(), Bitmap.Config.ARGB_8888);
                else
                    mSharedHqBm = null;
            }

            pageView = new PageView(mContext, mCore, new Point(parent.getWidth(), parent.getHeight()), mSharedHqBm);
        } else {
            pageView = (PageView) convertView;
        }

        PointF pageSize = mPageSizes.get(position);
        if (pageSize != null) {
            // We already know the page size. Set it up
            // immediately
            pageView.setPage(position, pageSize);
        } else {
            // Page size as yet unknown. Blank it for now, and
            // start a background task to find the size
            pageView.blank(position);

            // execute sizing task in the background
            executorService.execute(() -> {
                PointF result = null;
                try {
                    result = mCore.getPageSize(position); // Get the page size
                } catch (RuntimeException e) {
                    e.printStackTrace(); // Handle the exception as needed
                }
                // update UI on the main thread
                PointF finalResult = result;
                handler.post(() -> {
                    // We now know the page size
                    mPageSizes.put(position, finalResult);
                    // Check that this view hasn't been reused for another page since we started
                    if (pageView.getPage() == position) {
                        pageView.setPage(position, finalResult);
                    }
                });
            });
        }
        return pageView;
    }
}
