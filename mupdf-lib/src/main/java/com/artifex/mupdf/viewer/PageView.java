package com.artifex.mupdf.viewer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.FileUriExposedException;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.res.ResourcesCompat;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.Quad;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Make our ImageViews opaque to optimize redraw
class OpaqueImageView extends AppCompatImageView {

    public OpaqueImageView(Context context) {
        super(context);
    }

    @Override
    public boolean isOpaque() {
        return true;
    }
}

public class PageView extends ViewGroup {
    private final String APP = "MuPDF";
    private final MuPDFCore mCore;

    private static final int HIGHLIGHT_COLOR = 0x8040E0D0;
    private static final int LINK_COLOR_LIGHT = 0x1A0000FF;
    private static final int LINK_COLOR_DARK = 0x26FFFFFF;
    private static final int BACKGROUND_COLOR = 0xFFFFFFFF;
    private static final int PROGRESS_DIALOG_DELAY = 200;

    protected final Context mContext;

    protected int mPageNumber;
    private final Point mParentSize;
    protected Point pageSizeAtMinZoom;   // Size of page at minimum zoom
    protected float mSourceScale;

    private ImageView imageAtMinZoom; // Image rendered at minimum zoom
    private Bitmap mEntireBm;
    private final Matrix mEntireMat;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Point mPatchViewSize; // View size on the basis of which the patch was created
    private Rect mPatchArea;
    private ImageView mPatch;
    private Bitmap mPatchBm;
    private Quad[][] mSearchBoxes;
    protected Link[] mLinks;
    private View mSearchView;
    private boolean mIsBlank;

    private ImageView mErrorIndicator;

    private ProgressBar mBusyIndicator;
    private final Handler mHandler = new Handler();

    public PageView(Context c, MuPDFCore core, Point parentSize, Bitmap sharedHqBm) {
        super(c);
        mContext = c;
        mCore = core;
        mParentSize = parentSize;
        setBackgroundColor(BACKGROUND_COLOR);
        mEntireBm = Bitmap.createBitmap(parentSize.x, parentSize.y, Config.ARGB_8888);
        mPatchBm = sharedHqBm;
        mEntireMat = new Matrix();
    }

    private void renderPageInBackgroundEntire() {
        setBackgroundColor(MuPDFCore.getInvert() ? Color.BLACK : Color.WHITE);
        imageAtMinZoom.setImageBitmap(null);
        imageAtMinZoom.invalidate();
        if (mBusyIndicator == null) {
            mBusyIndicator = new ProgressBar(mContext);
            mBusyIndicator.setIndeterminate(true);
            addView(mBusyIndicator);
            mBusyIndicator.setVisibility(INVISIBLE);
            mHandler.postDelayed(() -> {
                if (mBusyIndicator != null) {
                    mBusyIndicator.setVisibility(VISIBLE);
                }
            }, PROGRESS_DIALOG_DELAY);
        }
        CancellableTaskDefinition<Void, Boolean> task = getDrawPageTask(mEntireBm, pageSizeAtMinZoom.x, pageSizeAtMinZoom.y, 0, 0, pageSizeAtMinZoom.x, pageSizeAtMinZoom.y);
        // execute rendering task in the background
        executorService.execute(() -> {
            Boolean result;
            try {
                result = task.doInBackground();
            } catch (Exception e) {
                e.printStackTrace();
                result = false;
            }
            // update UI on the main thread
            Boolean finalResult = result;
            handler.post(() -> {
                removeView(mBusyIndicator);
                mBusyIndicator = null;
                if (finalResult != null && finalResult) {
                    clearRenderError();
                    imageAtMinZoom.setImageBitmap(mEntireBm);
                    imageAtMinZoom.invalidate();
                } else {
                    setRenderError("Error rendering page");
                }
                setBackgroundColor(Color.TRANSPARENT);
            });
        });
    }

    private void backgroundRenderPatch(CancellableTaskDefinition<Void, Boolean> task, Point patchViewSize, Rect patchArea) {
        // execute rendering task in the background
        executorService.execute(() -> {
            Boolean result;
            try {
                result = task.doInBackground();
            } catch (Exception e) {
                e.printStackTrace();
                result = false;
            }
            // update UI on the main thread
            Boolean finalResult = result;
            handler.post(() -> {
                if (finalResult != null && finalResult) {
                    mPatchViewSize = patchViewSize;
                    mPatchArea = patchArea;
                    clearRenderError();
                    mPatch.setImageBitmap(mPatchBm);
                    mPatch.invalidate();
                    mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
                } else {
                    setRenderError("Error rendering patch");
                }
            });
        });
    }


    private void reinit() {
        mIsBlank = true;
        mPageNumber = 0;

        if (pageSizeAtMinZoom == null)
            pageSizeAtMinZoom = mParentSize;

        if (imageAtMinZoom != null) {
            imageAtMinZoom.setImageBitmap(null);
            imageAtMinZoom.invalidate();
        }

        if (mPatch != null) {
            mPatch.setImageBitmap(null);
            mPatch.invalidate();
        }

        mPatchViewSize = null;
        mPatchArea = null;

        mSearchBoxes = null;
        mLinks = null;

        clearRenderError();
    }

    public void releaseResources() {
        reinit();

        if (mBusyIndicator != null) {
            removeView(mBusyIndicator);
            mBusyIndicator = null;
        }
        clearRenderError();
    }

    public void releaseBitmaps() {
        reinit();
        // recycle bitmaps before releasing them.
        if (mEntireBm != null)
            mEntireBm.recycle();
        mEntireBm = null;
        if (mPatchBm != null)
            mPatchBm.recycle();
        mPatchBm = null;
    }

    public void blank(int page) {
        reinit();
        mPageNumber = page;
        setBackgroundColor(BACKGROUND_COLOR);
    }

    protected void clearRenderError() {
        if (mErrorIndicator == null)
            return;
        removeView(mErrorIndicator);
        mErrorIndicator = null;
        invalidate();
    }

    protected void setRenderError(String why) {
        int page = mPageNumber;
        reinit();
        mPageNumber = page;
        if (mBusyIndicator != null) {
            removeView(mBusyIndicator);
            mBusyIndicator = null;
        }
        if (mSearchView != null) {
            removeView(mSearchView);
            mSearchView = null;
        }
        if (mErrorIndicator == null) {
            mErrorIndicator = new OpaqueImageView(mContext);
            mErrorIndicator.setScaleType(ImageView.ScaleType.CENTER);
            addView(mErrorIndicator);
            Drawable mErrorIcon = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_error_red_24dp, null);
            mErrorIndicator.setImageDrawable(mErrorIcon);
            mErrorIndicator.setBackgroundColor(BACKGROUND_COLOR);
        }
        mErrorIndicator.bringToFront();
        mErrorIndicator.invalidate();
    }

    public void setPage(int page, PointF size) {
        mIsBlank = false;
        // Highlights may be missing because mIsBlank was true on last draw
        if (mSearchView != null)
            mSearchView.invalidate();

        mPageNumber = page;

        if (size == null) {
            setRenderError("Error loading page");
            size = new PointF(612, 792);
        }

        // Calculate scaled size that fits within the screen limits
        // This is the size at minimum zoom
        mSourceScale = Math.min(mParentSize.x / size.x, mParentSize.y / size.y);
        Point newSize = new Point((int) (size.x * mSourceScale), (int) (size.y * mSourceScale));
        pageSizeAtMinZoom = newSize;

        if (mErrorIndicator != null)
            return;

        if (imageAtMinZoom == null) {
            imageAtMinZoom = new OpaqueImageView(mContext);
            imageAtMinZoom.setScaleType(ImageView.ScaleType.MATRIX);
            addView(imageAtMinZoom);
        }

        imageAtMinZoom.setImageBitmap(null);
        imageAtMinZoom.invalidate();

        Handler handler = new Handler(Looper.getMainLooper());
        executorService.execute(() -> {
            Link[] links = getLinkInfo();
            handler.post(() -> {
                mLinks = links;
                if (mSearchView != null) {
                    mSearchView.invalidate();
                }
            });
        });

        renderPageInBackgroundEntire();

        if (mSearchView == null) {
            mSearchView = new View(mContext) {
                @Override
                protected void onDraw(final Canvas canvas) {
                    super.onDraw(canvas);
                    // Work out current total scale factor
                    // from source to view
                    final float scale = mSourceScale * (float) getWidth() / (float) pageSizeAtMinZoom.x;
                    final Paint paint = new Paint();

                    if (!mIsBlank && mSearchBoxes != null) {
                        paint.setColor(HIGHLIGHT_COLOR);
                        for (Quad[] searchBox : mSearchBoxes) {
                            for (Quad q : searchBox) {
                                Path path = new Path();
                                path.moveTo(q.ul_x * scale, q.ul_y * scale);
                                path.lineTo(q.ll_x * scale, q.ll_y * scale);
                                path.lineTo(q.lr_x * scale, q.lr_y * scale);
                                path.lineTo(q.ur_x * scale, q.ur_y * scale);
                                path.close();
                                canvas.drawPath(path, paint);
                            }
                        }
                    }

                    if (!mIsBlank && mLinks != null) {
                        paint.setColor(MuPDFCore.getInvert() ? LINK_COLOR_DARK : LINK_COLOR_LIGHT);
                        for (Link link : mLinks) {
                            canvas.drawRect(
                                    link.getBounds().x0 * scale, link.getBounds().y0 * scale,
                                    link.getBounds().x1 * scale, link.getBounds().y1 * scale,
                                    paint
                            );
                        }
                    }
                }
            };

            addView(mSearchView);
        }
        requestLayout();
    }

    public void setSearchBoxes(Quad[][] searchBoxes) {
        mSearchBoxes = searchBoxes;
        if (mSearchView != null)
            mSearchView.invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int x, y;
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            x = pageSizeAtMinZoom.x;
        } else {
            x = MeasureSpec.getSize(widthMeasureSpec);
        }
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            y = pageSizeAtMinZoom.y;
        } else {
            y = MeasureSpec.getSize(heightMeasureSpec);
        }

        setMeasuredDimension(x, y);

        if (mBusyIndicator != null) {
            int limit = Math.min(mParentSize.x, mParentSize.y) / 2;
            mBusyIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
        }
        if (mErrorIndicator != null) {
            int limit = Math.min(mParentSize.x, mParentSize.y) / 2;
            mErrorIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;

        if (imageAtMinZoom != null) {
            if (imageAtMinZoom.getWidth() != w || imageAtMinZoom.getHeight() != h) {
                mEntireMat.setScale(w / (float) pageSizeAtMinZoom.x, h / (float) pageSizeAtMinZoom.y);
                imageAtMinZoom.setImageMatrix(mEntireMat);
                imageAtMinZoom.invalidate();
            }
            imageAtMinZoom.layout(0, 0, w, h);
        }

        if (mSearchView != null) {
            mSearchView.layout(0, 0, w, h);
        }

        if (mPatchViewSize != null) {
            if (mPatchViewSize.x != w || mPatchViewSize.y != h) {
                // Zoomed since patch was created
                mPatchViewSize = null;
                mPatchArea = null;
                if (mPatch != null) {
                    mPatch.setImageBitmap(null);
                    mPatch.invalidate();
                }
            } else {
                mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
            }
        }

        if (mBusyIndicator != null) {
            int bw = mBusyIndicator.getMeasuredWidth();
            int bh = mBusyIndicator.getMeasuredHeight();

            mBusyIndicator.layout((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
        }

        if (mErrorIndicator != null) {
            int bw = (int) (8.5 * mErrorIndicator.getMeasuredWidth());
            int bh = 11 * mErrorIndicator.getMeasuredHeight();
            mErrorIndicator.layout((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
        }
    }

    public void updateHq(boolean update) {
        if (mErrorIndicator != null) {
            if (mPatch != null) {
                mPatch.setImageBitmap(null);
                mPatch.invalidate();
            }
            return;
        }

        Rect viewArea = new Rect(getLeft(), getTop(), getRight(), getBottom());
        if (viewArea.width() == pageSizeAtMinZoom.x || viewArea.height() == pageSizeAtMinZoom.y) {
            // If the viewArea's size matches the unzoomed size, there is no need for an hq patch
            if (mPatch != null) {
                mPatch.setImageBitmap(null);
                mPatch.invalidate();
            }
        } else {
            final Point patchViewSize = new Point(viewArea.width(), viewArea.height());
            final Rect patchArea = new Rect(0, 0, mParentSize.x, mParentSize.y);

            // Intersect and test that there is an intersection
            if (!patchArea.intersect(viewArea))
                return;

            // Offset patch area to be relative to the view top left
            patchArea.offset(-viewArea.left, -viewArea.top);

            boolean area_unchanged = patchArea.equals(mPatchArea) && patchViewSize.equals(mPatchViewSize);

            // If being asked for the same area as last time and not because of an update then nothing to do
            if (area_unchanged && !update)
                return;

            boolean completeRedraw = !(area_unchanged && update);

            // Create and add the image view if not already done
            if (mPatch == null) {
                mPatch = new OpaqueImageView(mContext);
                mPatch.setScaleType(ImageView.ScaleType.MATRIX);
                addView(mPatch);
                if (mSearchView != null)
                    mSearchView.bringToFront();
            }

            CancellableTaskDefinition<Void, Boolean> task;

            if (completeRedraw)
                task = getDrawPageTask(mPatchBm, patchViewSize.x, patchViewSize.y,
                        patchArea.left, patchArea.top,
                        patchArea.width(), patchArea.height());
            else
                task = getUpdatePageTask(mPatchBm, patchViewSize.x, patchViewSize.y,
                        patchArea.left, patchArea.top,
                        patchArea.width(), patchArea.height());

            backgroundRenderPatch(task, patchViewSize, patchArea);
        }
    }

    public void removeHq() {
        mPatchViewSize = null;
        mPatchArea = null;
        if (mPatch != null) {
            mPatch.setImageBitmap(null);
            mPatch.invalidate();
        }
    }

    public int getPage() {
        return mPageNumber;
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    public int hitLink(Link link) {
        if (link.isExternal()) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link.getURI()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            try {
                mContext.startActivity(intent);
            } catch (FileUriExposedException x) {
                Log.e(APP, x.toString());
                Toast.makeText(getContext(), "Android does not allow following file:// link: " + link.getURI(), Toast.LENGTH_LONG).show();
            } catch (Throwable x) {
                Log.e(APP, x.toString());
                Toast.makeText(getContext(), x.getMessage(), Toast.LENGTH_LONG).show();
            }
            return 0;
        } else {
            return mCore.resolveLink(link);
        }
    }

    public int hitLink(float x, float y) {
        // Since link highlighting was implemented, the super class
        // PageView has had sufficient information to be able to
        // perform this method directly. Making that change would
        // make MuPDFCore.hitLinkPage superfluous.
        float scale = mSourceScale * (float) getWidth() / (float) pageSizeAtMinZoom.x;
        float docRelX = (x - getLeft()) / scale;
        float docRelY = (y - getTop()) / scale;

        if (mLinks != null)
            for (Link l : mLinks)
                if (l.getBounds().contains(docRelX, docRelY))
                    return hitLink(l);
        return 0;
    }

    protected CancellableTaskDefinition<Void, Boolean> getDrawPageTask(final Bitmap bm, final int sizeX, final int sizeY,
                                                                       final int patchX, final int patchY, final int patchWidth, final int patchHeight) {
        return new MuPDFCancellableTaskDefinition<>() {
            @Override
            public Boolean doInBackground(Cookie cookie, Void... params) {
                if (bm == null)
                    return Boolean.FALSE;
                try {
                    mCore.drawPage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie);
                    return Boolean.TRUE;
                } catch (RuntimeException e) {
                    return Boolean.FALSE;
                }
            }
        };

    }

    protected CancellableTaskDefinition<Void, Boolean> getUpdatePageTask(final Bitmap bm, final int sizeX, final int sizeY,
                                                                         final int patchX, final int patchY, final int patchWidth, final int patchHeight) {
        return new MuPDFCancellableTaskDefinition<>() {
            @Override
            public Boolean doInBackground(Cookie cookie, Void... params) {
                if (bm == null)
                    return Boolean.FALSE;
                try {
                    mCore.updatePage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie);
                    return Boolean.TRUE;
                } catch (RuntimeException e) {
                    return Boolean.FALSE;
                }
            }
        };
    }

    protected Link[] getLinkInfo() {
        try {
            return mCore.getPageLinks(mPageNumber);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
