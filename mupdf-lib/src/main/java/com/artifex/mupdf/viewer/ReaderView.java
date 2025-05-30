package com.artifex.mupdf.viewer;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.Scroller;

import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Stack;

public class ReaderView
        extends AdapterView<Adapter>
        implements GestureDetector.OnGestureListener, ScaleGestureDetector.OnScaleGestureListener, Runnable {
    private boolean tapDisabled = false;
    private int tapPageMargin;

    private static final int MOVING_DIAGONALLY = 0;
    private static final int MOVING_LEFT = 1;
    private static final int MOVING_RIGHT = 2;
    private static final int MOVING_UP = 3;
    private static final int MOVING_DOWN = 4;

    private static final int FLING_MARGIN = 100;
    private static final int GAP = 20;

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 64.0f;

    private static boolean horizontalScrolling = true;

    private PageAdapter mAdapter;
    protected int mCurrent;    // Adapter's index for the current view
    private boolean mResetLayout;
    private final SparseArray<View> mChildViews = new SparseArray<>(3);
    // Shadows the children of the adapter view but with more sensible indexing
    private final LinkedList<View> mViewCache = new LinkedList<>();
    private boolean mUserInteracting;  // Whether the user is interacting
    private boolean mScaling;    // Whether the user is currently pinch zooming
    private float mScale = 1.0f;
    private int mXScroll;    // Scroll amounts recorded from events.
    private int mYScroll;    // and then accounted for in onLayout
    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mScaleGestureDetector;
    private Scroller mScroller;
    private Stepper mStepper;
    private int mScrollerLastX;
    private int mScrollerLastY;
    private float mLastScaleFocusX;
    private float mLastScaleFocusY;

    protected Stack<Integer> mHistory;

    public static boolean isHorizontalScrolling() {
        return horizontalScrolling;
    }

    public static void setHorizontalScrolling(boolean isHorizontal) {
        horizontalScrolling = isHorizontal;
    }

    static abstract class ViewMapper {
        abstract void applyToView(View view);
    }

    public ReaderView(Context context) {
        super(context);
        setup(context);
    }

    public ReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setup(context);
    }

    public ReaderView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setup(context);
    }

    private void setup(Context context) {
        mGestureDetector = new GestureDetector(context, this);
        mScaleGestureDetector = new ScaleGestureDetector(context, this);
        mScroller = new Scroller(context);
        mStepper = new Stepper(this, this);
        mHistory = new Stack<>();

        // Get the screen size etc to customise tap margins.
        // We calculate the size of 1 inch of the screen for tapping.
        // On some devices the dpi values returned are wrong, so we
        // sanity check it: we first restrict it so that we are never
        // less than 100 pixels (the smallest Android device screen
        // dimension I've seen is 480 pixels or so). Then we check
        // to ensure we are never more than 1/5 of the screen width.
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(dm);
        tapPageMargin = (int) dm.xdpi;
        if (tapPageMargin < 100)
            tapPageMargin = 100;
        if (tapPageMargin > dm.widthPixels / 5)
            tapPageMargin = dm.widthPixels / 5;
    }

    public boolean popHistory() {
        if (mHistory.empty())
            return false;
        setDisplayedViewIndex(mHistory.pop());
        return true;
    }

    public void pushHistory() {
        mHistory.push(mCurrent);
    }

    public int getDisplayedViewIndex() {
        return mCurrent;
    }

    public void setDisplayedViewIndex(int i) {
        if (0 <= i && i < mAdapter.getCount()) {
            onMoveOffChild(mCurrent);
            mCurrent = i;
            onMoveToChild(i);
            mResetLayout = true;
            requestLayout();
        }
    }

    public void moveToNext() {
        View v = mChildViews.get(mCurrent + 1);
        if (v != null)
            slideViewOntoScreen(v);
    }

    public void moveToPrevious() {
        View v = mChildViews.get(mCurrent - 1);
        if (v != null)
            slideViewOntoScreen(v);
    }

    // When advancing down the page, we want to advance by about
    // 90% of a screenful. But we'd be happy to advance by between
    // 80% and 95% if it means we hit the bottom in a whole number
    // of steps.
    private int smartAdvanceAmount(int screenHeight, int max) {
        int advance = (int) (screenHeight * 0.9 + 0.5);
        int leftOver = max % advance;
        int steps = max / advance;
        if ((float) leftOver / steps <= screenHeight * 0.05) {
            // We can adjust up by less than 5% to make it exact.
            advance += (int) ((float) leftOver / steps + 0.5);
        } else {
            int overshoot = advance - leftOver;
            if ((float) overshoot / steps <= screenHeight * 0.1) {
                // We can adjust down by less than 10% to make it exact.
                advance -= (int) ((float) overshoot / steps + 0.5);
            }
        }
        if (advance > max)
            advance = max;
        return advance;
    }

    public void smartMoveForwards() {
        View v = mChildViews.get(mCurrent);
        if (v == null)
            return;

        // The following code works in terms of where the screen is on the views;
        // so for example, if the currentView is at (-100,-100), the visible
        // region would be at (100,100). If the previous page was (2000, 3000) in
        // size, the visible region of the previous page might be (2100 + GAP, 100)
        // (i.e. off the previous page). This is different to the way the rest of
        // the code in this file is written, but it's easier for me to think about.
        // At some point we may refactor this to fit better with the rest of the
        // code.

        // screenWidth/Height are the actual width/height of the screen. e.g. 480/800
        final int screenWidth = getWidth();
        final int screenHeight = getHeight();
        // We might be mid scroll; we want to calculate where we scroll to based on
        // where this scroll would end, not where we are now (to allow for people
        // bashing 'forwards' very fast.
        final int remainingX = mScroller.getFinalX() - mScroller.getCurrX();
        final int remainingY = mScroller.getFinalY() - mScroller.getCurrY();
        // right/bottom is in terms of pixels within the scaled document; e.g. 1000
        final int top = -(v.getTop() + mYScroll + remainingY);
        final int right = screenWidth - (v.getLeft() + mXScroll + remainingX);
        final int bottom = screenHeight + top;
        // docWidth/Height are the width/height of the scaled document e.g. 2000x3000
        final int docWidth = v.getMeasuredWidth();
        final int docHeight = v.getMeasuredHeight();

        int xOffset, yOffset;
        if (bottom >= docHeight) {
            // We are flush with the bottom. Advance to next column.
            if (right + screenWidth > docWidth) {
                // No room for another column - go to next page
                View nv = mChildViews.get(mCurrent + 1);
                if (nv == null) // No page to advance to
                    return;
                final int nextTop = -(nv.getTop() + mYScroll + remainingY);
                final int nextLeft = -(nv.getLeft() + mXScroll + remainingX);
                final int nextDocWidth = nv.getMeasuredWidth();
                final int nextDocHeight = nv.getMeasuredHeight();

                // Allow for the next page maybe being shorter than the screen is high
                yOffset = (nextDocHeight < screenHeight ? ((nextDocHeight - screenHeight) >> 1) : 0);

                if (nextDocWidth < screenWidth) {
                    // Next page is too narrow to fill the screen. Scroll to the top, centred.
                    xOffset = (nextDocWidth - screenWidth) >> 1;
                } else {
                    // Reset X back to the left hand column
                    xOffset = right % screenWidth;
                    // Adjust in case the previous page is less wide
                    if (xOffset + screenWidth > nextDocWidth)
                        xOffset = nextDocWidth - screenWidth;
                }
                xOffset -= nextLeft;
                yOffset -= nextTop;
            } else {
                // Move to top of next column
                xOffset = screenWidth;
                yOffset = screenHeight - bottom;
            }
        } else {
            // Advance by 90% of the screen height downwards (in case lines are partially cut off)
            xOffset = 0;
            yOffset = smartAdvanceAmount(screenHeight, docHeight - bottom);
        }
        mScrollerLastX = mScrollerLastY = 0;
        mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 0);
        mStepper.prod();
    }

    public void smartMoveBackwards() {
        View v = mChildViews.get(mCurrent);
        if (v == null)
            return;

        // The following code works in terms of where the screen is on the views;
        // so for example, if the currentView is at (-100,-100), the visible
        // region would be at (100,100). If the previous page was (2000, 3000) in
        // size, the visible region of the previous page might be (2100 + GAP, 100)
        // (i.e. off the previous page). This is different to the way the rest of
        // the code in this file is written, but it's easier for me to think about.
        // At some point we may refactor this to fit better with the rest of the
        // code.

        // screenWidth/Height are the actual width/height of the screen. e.g. 480/800
        int screenWidth = getWidth();
        int screenHeight = getHeight();
        // We might be mid scroll; we want to calculate where we scroll to based on
        // where this scroll would end, not where we are now (to allow for people
        // bashing 'forwards' very fast.
        int remainingX = mScroller.getFinalX() - mScroller.getCurrX();
        int remainingY = mScroller.getFinalY() - mScroller.getCurrY();
        // left/top is in terms of pixels within the scaled document; e.g. 1000
        int left = -(v.getLeft() + mXScroll + remainingX);
        int top = -(v.getTop() + mYScroll + remainingY);
        // docWidth/Height are the width/height of the scaled document e.g. 2000x3000
        int docHeight = v.getMeasuredHeight();

        int xOffset, yOffset;
        if (top <= 0) {
            // We are flush with the top. Step back to previous column.
            if (left < screenWidth) {
                /* No room for previous column - go to previous page */
                View pv = mChildViews.get(mCurrent - 1);
                if (pv == null) /* No page to advance to */
                    return;
                int prevDocWidth = pv.getMeasuredWidth();
                int prevDocHeight = pv.getMeasuredHeight();

                // Allow for the next page maybe being shorter than the screen is high
                yOffset = (prevDocHeight < screenHeight ? ((prevDocHeight - screenHeight) >> 1) : 0);

                int prevLeft = -(pv.getLeft() + mXScroll);
                int prevTop = -(pv.getTop() + mYScroll);
                if (prevDocWidth < screenWidth) {
                    // Previous page is too narrow to fill the screen. Scroll to the bottom, centred.
                    xOffset = (prevDocWidth - screenWidth) >> 1;
                } else {
                    // Reset X back to the right hand column
                    xOffset = (left > 0 ? left % screenWidth : 0);
                    if (xOffset + screenWidth > prevDocWidth)
                        xOffset = prevDocWidth - screenWidth;
                    while (xOffset + screenWidth * 2 < prevDocWidth)
                        xOffset += screenWidth;
                }
                xOffset -= prevLeft;
                yOffset -= prevTop - prevDocHeight + screenHeight;
            } else {
                // Move to bottom of previous column
                xOffset = -screenWidth;
                yOffset = docHeight - screenHeight + top;
            }
        } else {
            // Retreat by 90% of the screen height downwards (in case lines are partially cut off)
            xOffset = 0;
            yOffset = -smartAdvanceAmount(screenHeight, top);
        }
        mScrollerLastX = mScrollerLastY = 0;
        mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 0);
        mStepper.prod();
    }

    public void resetupChildren() {
        for (int i = 0; i < mChildViews.size(); i++)
            onChildSetup(mChildViews.keyAt(i), mChildViews.valueAt(i));
    }

    public void applyToChildren(ViewMapper mapper) {
        for (int i = 0; i < mChildViews.size(); i++)
            mapper.applyToView(mChildViews.valueAt(i));
    }

    public void refresh() {
        mResetLayout = true;

        mScale = 1.0f;
        mXScroll = mYScroll = 0;

        /* All page views need recreating since both page and screen has changed size,
         * invalidating both sizes and bitmaps. */
        mAdapter.refresh();
        for (int i = 0; i < mChildViews.size(); i++) {
            View v = mChildViews.valueAt(i);
            onNotInUse(v);
            removeViewInLayout(v);
        }
        mChildViews.clear();
        mViewCache.clear();

        requestLayout();
    }

    public View getView(int i) {
        return mChildViews.get(i);
    }

    public View getDisplayedView() {
        return mChildViews.get(mCurrent);
    }

    public void run() {
        if (!mScroller.isFinished()) {
            mScroller.computeScrollOffset();
            int x = mScroller.getCurrX();
            int y = mScroller.getCurrY();
            mXScroll += x - mScrollerLastX;
            mYScroll += y - mScrollerLastY;
            mScrollerLastX = x;
            mScrollerLastY = y;
            requestLayout();
            mStepper.prod();
        } else if (!mUserInteracting) {
            // End of an inertial scroll and the user is not interacting.
            // The layout is stable
            View v = mChildViews.get(mCurrent);
            if (v != null)
                postSettle(v);
        }
    }

    public boolean onDown(MotionEvent arg0) {
        mScroller.forceFinished(true);
        return true;
    }

    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (mScaling)
            return true;

        View v = mChildViews.get(mCurrent);
        if (v != null) {
            Rect bounds = getScrollBounds(v);
            switch (directionOfTravel(velocityX, velocityY)) {
                case MOVING_LEFT:
                    if (horizontalScrolling && bounds.left >= 0) {
                        // Fling off to the left bring next view onto screen
                        View vl = mChildViews.get(mCurrent + 1);

                        if (vl != null) {
                            slideViewOntoScreen(vl);
                            return true;
                        }
                    }
                    break;
                case MOVING_UP:
                    if (!horizontalScrolling && bounds.top >= 0) {
                        // Fling off to the top bring next view onto screen
                        View vl = mChildViews.get(mCurrent + 1);

                        if (vl != null) {
                            slideViewOntoScreen(vl);
                            return true;
                        }
                    }
                    break;
                case MOVING_RIGHT:
                    if (horizontalScrolling && bounds.right <= 0) {
                        // Fling off to the right bring previous view onto screen
                        View vr = mChildViews.get(mCurrent - 1);

                        if (vr != null) {
                            slideViewOntoScreen(vr);
                            return true;
                        }
                    }
                    break;
                case MOVING_DOWN:
                    if (!horizontalScrolling && bounds.bottom <= 0) {
                        // Fling off to the bottom bring previous view onto screen
                        View vr = mChildViews.get(mCurrent - 1);

                        if (vr != null) {
                            slideViewOntoScreen(vr);
                            return true;
                        }
                    }
                    break;
            }
            mScrollerLastX = mScrollerLastY = 0;
            // If the page has been dragged out of bounds then we want to spring back
            // nicely. fling jumps back into bounds instantly, so we don't want to use
            // fling in that case. On the other hand, we don't want to forgo a fling
            // just because of a slightly off-angle drag taking us out of bounds other
            // than in the direction of the drag, so we test for out of bounds only
            // in the direction of travel.
            //
            // Also don't fling if out of bounds in any direction by more than fling
            // margin
            Rect expandedBounds = new Rect(bounds);
            expandedBounds.inset(-FLING_MARGIN, -FLING_MARGIN);

            if (withinBoundsInDirectionOfTravel(bounds, velocityX, velocityY)
                    && expandedBounds.contains(0, 0)) {
                mScroller.fling(0, 0, (int) velocityX, (int) velocityY, bounds.left, bounds.right, bounds.top, bounds.bottom);
                mStepper.prod();
            }
        }

        return true;
    }

    public void onLongPress(MotionEvent e) {
    }

    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                            float distanceY) {
        PageView pageView = (PageView) getDisplayedView();
        if (!tapDisabled)
            onDocMotion();
        if (!mScaling) {
            mXScroll -= (int) distanceX;
            mYScroll -= (int) distanceY;
            requestLayout();
        }
        return true;
    }

    public void onShowPress(MotionEvent e) {
    }

    public boolean onScale(ScaleGestureDetector detector) {
        float previousScale = mScale;
        mScale = Math.min(Math.max(mScale * detector.getScaleFactor(), MIN_SCALE), MAX_SCALE);
        float factor = mScale / previousScale;

        View v = mChildViews.get(mCurrent);
        if (v != null) {
            float currentFocusX = detector.getFocusX();
            float currentFocusY = detector.getFocusY();
            // Work out the focus point relative to the view top left
            int viewFocusX = (int) currentFocusX - (v.getLeft() + mXScroll);
            int viewFocusY = (int) currentFocusY - (v.getTop() + mYScroll);
            // Scroll to maintain the focus point
            mXScroll += (int) (viewFocusX - viewFocusX * factor);
            mYScroll += (int) (viewFocusY - viewFocusY * factor);

            if (mLastScaleFocusX >= 0)
                mXScroll += (int) (currentFocusX - mLastScaleFocusX);
            if (mLastScaleFocusY >= 0)
                mYScroll += (int) (currentFocusY - mLastScaleFocusY);

            mLastScaleFocusX = currentFocusX;
            mLastScaleFocusY = currentFocusY;
            requestLayout();
        }
        return true;
    }

    public boolean onScaleBegin(ScaleGestureDetector detector) {
        tapDisabled = true;
        mScaling = true;
        // Ignore any scroll amounts yet to be accounted for: the
        // screen is not showing the effect of them, so they can
        // only confuse the user
        mXScroll = mYScroll = 0;
        mLastScaleFocusX = mLastScaleFocusY = -1;
        return true;
    }

    public void onScaleEnd(ScaleGestureDetector detector) {
        mScaling = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if ((event.getAction() & event.getActionMasked()) == MotionEvent.ACTION_DOWN) {
            tapDisabled = false;
        }

        mScaleGestureDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);

        if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
            mUserInteracting = true;
        }
        if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
            mUserInteracting = false;

            View v = mChildViews.get(mCurrent);
            if (v != null) {
                if (mScroller.isFinished()) {
                    // If, at the end of user interaction, there is no
                    // current inertial scroll in operation then animate
                    // the view onto screen if necessary
                    slideViewOntoScreen(v);
                }

                if (mScroller.isFinished()) {
                    // If still there is no inertial scroll in operation
                    // then the layout is stable
                    postSettle(v);
                }
            }
        }

        requestLayout();
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int n = getChildCount();
        for (int i = 0; i < n; i++)
            measureView(getChildAt(i));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        try {
            onLayout2(changed, left, top, right, bottom);
        } catch (java.lang.OutOfMemoryError e) {
            System.out.println("Out of memory during layout");
        }
    }

    private void onLayout2(boolean changed, int left, int top, int right, int bottom) {

        // "Edit mode" means when the View is being displayed in the Android GUI editor. (this class
        // is instantiated in the IDE, so we need to be a bit careful what we do).
        if (isInEditMode())
            return;

        View cv = mChildViews.get(mCurrent);
        Point cvOffset;

        if (!mResetLayout) {
            // Move to next or previous if current is sufficiently off center
            if (cv != null) {
                boolean move;
                cvOffset = subScreenSizeOffset(cv);
                // cv.getRight() may be out of date with the current scale
                // so add left to the measured width for the correct position
                if (horizontalScrolling)
                    move = cv.getLeft() + cv.getMeasuredWidth() + cvOffset.x + GAP / 2 + mXScroll < getWidth() / 2;
                else
                    move = cv.getTop() + cv.getMeasuredHeight() + cvOffset.y + GAP / 2 + mYScroll < getHeight() / 2;
                if (move && mCurrent + 1 < mAdapter.getCount()) {
                    postUnsettle(cv);
                    // post to invoke test for end of animation
                    // where we must set hq area for the new current view
                    mStepper.prod();

                    onMoveOffChild(mCurrent);
                    mCurrent++;
                    onMoveToChild(mCurrent);
                }

                if (horizontalScrolling)
                    move = cv.getLeft() - cvOffset.x - GAP / 2 + mXScroll >= getWidth() / 2;
                else
                    move = cv.getTop() - cvOffset.y - GAP / 2 + mYScroll >= getHeight() / 2;
                if (move && mCurrent > 0) {
                    postUnsettle(cv);
                    // post to invoke test for end of animation
                    // where we must set hq area for the new current view
                    mStepper.prod();

                    onMoveOffChild(mCurrent);
                    mCurrent--;
                    onMoveToChild(mCurrent);
                }
            }

            // Remove not needed children and hold them for reuse
            int numChildren = mChildViews.size();
            int[] childIndices = new int[numChildren];
            for (int i = 0; i < numChildren; i++)
                childIndices[i] = mChildViews.keyAt(i);

            for (int i = 0; i < numChildren; i++) {
                int ai = childIndices[i];
                if (ai < mCurrent - 1 || ai > mCurrent + 1) {
                    View v = mChildViews.get(ai);
                    onNotInUse(v);
                    mViewCache.add(v);
                    removeViewInLayout(v);
                    mChildViews.remove(ai);
                }
            }
        } else {
            mResetLayout = false;
            mXScroll = mYScroll = 0;

            // Remove all children and hold them for reuse
            int numChildren = mChildViews.size();
            for (int i = 0; i < numChildren; i++) {
                View v = mChildViews.valueAt(i);
                onNotInUse(v);
                mViewCache.add(v);
                removeViewInLayout(v);
            }
            mChildViews.clear();

            // post to ensure generation of hq area
            mStepper.prod();
        }

        // Ensure current view is present
        int cvLeft, cvRight, cvTop, cvBottom;
        boolean notPresent = (mChildViews.get(mCurrent) == null);
        cv = getOrCreateChild(mCurrent);
        // When the view is sub-screen-size in either dimension we
        // offset it to center within the screen area, and to keep
        // the views spaced out
        cvOffset = subScreenSizeOffset(cv);
        if (notPresent) {
            // Main item not already present. Just place it top left
            cvLeft = cvOffset.x;
            cvTop = cvOffset.y;
        } else {
            // Main item already present. Adjust by scroll offsets
            cvLeft = cv.getLeft() + mXScroll;
            cvTop = cv.getTop() + mYScroll;
        }
        // Scroll values have been accounted for
        mXScroll = mYScroll = 0;
        cvRight = cvLeft + cv.getMeasuredWidth();
        cvBottom = cvTop + cv.getMeasuredHeight();

        if (!mUserInteracting && mScroller.isFinished()) {
            Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));
            cvRight += corr.x;
            cvLeft += corr.x;
            cvTop += corr.y;
            cvBottom += corr.y;
        } else if (horizontalScrolling && cv.getMeasuredHeight() <= getHeight()) {
            // When the current view is as small as the screen in height, clamp
            // it vertically
            Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));
            cvTop += corr.y;
            cvBottom += corr.y;
        } else if (!horizontalScrolling && cv.getMeasuredWidth() <= getWidth()) {
            // When the current view is as small as the screen in width, clamp
            // it horizontally
            Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));
            cvRight += corr.x;
            cvLeft += corr.x;
        }

        cv.layout(cvLeft, cvTop, cvRight, cvBottom);

        if (mCurrent > 0) {
            View lv = getOrCreateChild(mCurrent - 1);
            Point leftOffset = subScreenSizeOffset(lv);
            if (horizontalScrolling) {
                int gap = leftOffset.x + GAP + cvOffset.x;
                lv.layout(cvLeft - lv.getMeasuredWidth() - gap,
                        (cvBottom + cvTop - lv.getMeasuredHeight()) / 2,
                        cvLeft - gap,
                        (cvBottom + cvTop + lv.getMeasuredHeight()) / 2);
            } else {
                int gap = leftOffset.y + GAP + cvOffset.y;
                lv.layout((cvLeft + cvRight - lv.getMeasuredWidth()) / 2,
                        cvTop - lv.getMeasuredHeight() - gap,
                        (cvLeft + cvRight + lv.getMeasuredWidth()) / 2,
                        cvTop - gap);
            }
        }

        if (mCurrent + 1 < mAdapter.getCount()) {
            View rv = getOrCreateChild(mCurrent + 1);
            Point rightOffset = subScreenSizeOffset(rv);
            if (horizontalScrolling) {
                int gap = cvOffset.x + GAP + rightOffset.x;
                rv.layout(cvRight + gap,
                        (cvBottom + cvTop - rv.getMeasuredHeight()) / 2,
                        cvRight + rv.getMeasuredWidth() + gap,
                        (cvBottom + cvTop + rv.getMeasuredHeight()) / 2);
            } else {
                int gap = cvOffset.y + GAP + rightOffset.y;
                rv.layout((cvLeft + cvRight - rv.getMeasuredWidth()) / 2,
                        cvBottom + gap,
                        (cvLeft + cvRight + rv.getMeasuredWidth()) / 2,
                        cvBottom + gap + rv.getMeasuredHeight());
            }
        }

        invalidate();
    }

    @Override
    public Adapter getAdapter() {
        return mAdapter;
    }

    @Override
    public View getSelectedView() {
        return null;
    }

    @Override
    public void setAdapter(Adapter adapter) {
        if (mAdapter != null && mAdapter != adapter)
            mAdapter.releaseBitmaps();
        mAdapter = (PageAdapter) adapter;

        requestLayout();
    }

    @Override
    public void setSelection(int arg0) {
        throw new UnsupportedOperationException(getContext().getString(R.string.not_supported));
    }

    private View getCached() {
        if (mViewCache.isEmpty())
            return null;
        else
            return mViewCache.removeFirst();
    }

    private View getOrCreateChild(int i) {
        View v = mChildViews.get(i);
        if (v == null) {
            v = mAdapter.getView(i, getCached(), this);
            addAndMeasureChild(i, v);
            onChildSetup(i, v);
        }

        return v;
    }

    private void addAndMeasureChild(int i, View v) {
        LayoutParams params = v.getLayoutParams();
        if (params == null) {
            params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        }
        addViewInLayout(v, 0, params, true);
        mChildViews.append(i, v); // Record the view against its adapter index
        measureView(v);
    }

    private void measureView(View v) {
        // See what size the view wants to be
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        // Work out a scale that will fit it to this view
        float scale = Math.min((float) getWidth() / (float) v.getMeasuredWidth(),
                (float) getHeight() / (float) v.getMeasuredHeight());
        // Use the fitting values scaled by our current scale factor
        v.measure(View.MeasureSpec.EXACTLY | (int) (v.getMeasuredWidth() * scale * mScale),
                View.MeasureSpec.EXACTLY | (int) (v.getMeasuredHeight() * scale * mScale));
    }

    private Rect getScrollBounds(int left, int top, int right, int bottom) {
        int xmin = getWidth() - right;
        int xmax = -left;
        int ymin = getHeight() - bottom;
        int ymax = -top;

        // In either dimension, if view smaller than screen then
        // constrain it to be central
        if (xmin > xmax) xmin = xmax = (xmin + xmax) / 2;
        if (ymin > ymax) ymin = ymax = (ymin + ymax) / 2;

        return new Rect(xmin, ymin, xmax, ymax);
    }

    private Rect getScrollBounds(View v) {
        // There can be scroll amounts not yet accounted for in
        // onLayout, so add mXScroll and mYScroll to the current
        // positions when calculating the bounds.
        return getScrollBounds(v.getLeft() + mXScroll,
                v.getTop() + mYScroll,
                v.getLeft() + v.getMeasuredWidth() + mXScroll,
                v.getTop() + v.getMeasuredHeight() + mYScroll);
    }

    private Point getCorrection(Rect bounds) {
        return new Point(Math.min(Math.max(0, bounds.left), bounds.right),
                Math.min(Math.max(0, bounds.top), bounds.bottom));
    }

    private void postSettle(final View v) {
        // onSettle and onUnsettle are posted so that the calls
        // won't be executed until after the system has performed
        // layout.
        post(() -> onSettle(v));
    }

    private void postUnsettle(final View v) {
        post(() -> onUnsettle(v));
    }

    private void slideViewOntoScreen(View v) {
        Point corr = getCorrection(getScrollBounds(v));
        if (corr.x != 0 || corr.y != 0) {
            mScrollerLastX = mScrollerLastY = 0;
            mScroller.startScroll(0, 0, corr.x, corr.y, 160);
            mStepper.prod();
        }
    }

    private Point subScreenSizeOffset(View v) {
        return new Point(Math.max((getWidth() - v.getMeasuredWidth()) / 2, 0),
                Math.max((getHeight() - v.getMeasuredHeight()) / 2, 0));
    }

    private static int directionOfTravel(float vx, float vy) {
        if (Math.abs(vx) > 2 * Math.abs(vy))
            return (vx > 0) ? MOVING_RIGHT : MOVING_LEFT;
        else if (Math.abs(vy) > 2 * Math.abs(vx))
            return (vy > 0) ? MOVING_DOWN : MOVING_UP;
        else
            return MOVING_DIAGONALLY;
    }

    private static boolean withinBoundsInDirectionOfTravel(Rect bounds, float vx, float vy) {
        return switch (directionOfTravel(vx, vy)) {
            case MOVING_DIAGONALLY -> bounds.contains(0, 0);
            case MOVING_LEFT -> bounds.left <= 0;
            case MOVING_RIGHT -> bounds.right >= 0;
            case MOVING_UP -> bounds.top <= 0;
            case MOVING_DOWN -> bounds.bottom >= 0;
            default -> throw new NoSuchElementException();
        };
    }

    protected void onTapMainDocArea() {
    }

    protected void onDocMotion() {
    }

    public boolean onSingleTapUp(MotionEvent e) {
        if (!tapDisabled) {
            PageView pageView = (PageView) getDisplayedView();
            if (pageView != null) {
                int page = pageView.hitLink(e.getX(), e.getY());
                if (page > 0) {
                    pushHistory();
                    setDisplayedViewIndex(page);
                    return true;
                }
            }
            if (e.getX() < tapPageMargin) {
                smartMoveBackwards();
            } else if (e.getX() > super.getWidth() - tapPageMargin) {
                smartMoveForwards();
            } else if (e.getY() < tapPageMargin) {
                smartMoveBackwards();
            } else if (e.getY() > super.getHeight() - tapPageMargin) {
                smartMoveForwards();
            } else {
                onTapMainDocArea();
            }
        }
        return true;
    }

    protected void onChildSetup(int i, View v) {
        if (SearchTaskResult.get() != null
                && SearchTaskResult.get().pageNumber == i)
            ((PageView) v).setSearchBoxes(SearchTaskResult.get().searchBoxes);
        else
            ((PageView) v).setSearchBoxes(null);
    }

    protected void onMoveToChild(int i) {
        if (SearchTaskResult.get() != null && SearchTaskResult.get().pageNumber != i) {
            SearchTaskResult.set(null);
            resetupChildren();
        }
    }

    protected void onMoveOffChild(int i) {
    }

    protected void onSettle(View v) {
        // When the layout has settled ask the page to render
        // in HQ
        ((PageView) v).updateHq(false);
    }

    protected void onUnsettle(View v) {
        // When something changes making the previous settled view
        // no longer appropriate, tell the page to remove HQ
        ((PageView) v).removeHq();
    }

    protected void onNotInUse(View v) {
        ((PageView) v).releaseResources();
    }
}
