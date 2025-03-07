/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.RectF;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.MenuItem;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.FlingAnimationUtils;

public class SwipeHelper implements Gefingerpoken {
    static final String TAG = "com.android.systemui.SwipeHelper";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;
    private static final boolean CONSTRAIN_SWIPE = true;
    private static final boolean FADE_OUT_DURING_SWIPE = true;
    private static final boolean DISMISS_IF_SWIPED_FAR_ENOUGH = true;

    public static final int X = 0;
    public static final int Y = 1;

    private static final float SWIPE_ESCAPE_VELOCITY = 320f; // dp/sec
    private static final int DEFAULT_ESCAPE_ANIMATION_DURATION = 180; // ms
    private static final int MAX_ESCAPE_ANIMATION_DURATION = 320; // ms
    private static final int MAX_DISMISS_VELOCITY = 4000; // dp/sec
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 800 : 140; // ms

    static final float SWIPE_PROGRESS_FADE_END = 0.5f; // fraction of thumbnail width
                                              // beyond which swipe progress->0
    public static final float SWIPED_FAR_ENOUGH_SIZE_FRACTION = 0.6f;
    static final float MAX_SCROLL_SIZE_FRACTION = 0.3f;

    private float mMinSwipeProgress = 0f;
    private float mMaxSwipeProgress = 1f;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private float mPagingTouchSlop;
    private final Callback mCallback;
    private final Handler mHandler;
    private final int mSwipeDirection;
    private final VelocityTracker mVelocityTracker;
    private final FalsingManager mFalsingManager;

    private float mInitialTouchPos;
    private float mPerpendicularInitialTouchPos;
    private boolean mDragging;
    private boolean mSnappingChild;
    private View mCurrView;
    private boolean mCanCurrViewBeDimissed;
    private float mDensityScale;
    private float mTranslation = 0;

    private boolean mMenuRowIntercepting;
    private boolean mLongPressSent;
    private Runnable mWatchLongPress;
    private final long mLongPressTimeout;

    final private int[] mTmpPos = new int[2];
    private final int mFalsingThreshold;
    private boolean mTouchAboveFalsingThreshold;
    private boolean mDisableHwLayers;
    private final boolean mFadeDependingOnAmountSwiped;
    private final Context mContext;

    private final ArrayMap<View, Animator> mDismissPendingMap = new ArrayMap<>();

    public SwipeHelper(int swipeDirection, Callback callback, Context context) {
        mContext = context;
        mCallback = callback;
        mHandler = new Handler();
        mSwipeDirection = swipeDirection;
        mVelocityTracker = VelocityTracker.obtain();
        mPagingTouchSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();

        // Extra long-press!
        mLongPressTimeout = (long) (ViewConfiguration.getLongPressTimeout() * 1.5f);

        Resources res = context.getResources();
        mDensityScale =  res.getDisplayMetrics().density;
        mFalsingThreshold = res.getDimensionPixelSize(R.dimen.swipe_helper_falsing_threshold);
        mFadeDependingOnAmountSwiped = res.getBoolean(R.bool.config_fadeDependingOnAmountSwiped);
        mFalsingManager = FalsingManager.getInstance(context);
        mFlingAnimationUtils = new FlingAnimationUtils(context, getMaxEscapeAnimDuration() / 1000f);
    }

    public void setDensityScale(float densityScale) {
        mDensityScale = densityScale;
    }

    public void setPagingTouchSlop(float pagingTouchSlop) {
        mPagingTouchSlop = pagingTouchSlop;
    }

    public void setDisableHardwareLayers(boolean disableHwLayers) {
        mDisableHwLayers = disableHwLayers;
    }

    private float getPos(MotionEvent ev) {
        return mSwipeDirection == X ? ev.getX() : ev.getY();
    }

    private float getPerpendicularPos(MotionEvent ev) {
        return mSwipeDirection == X ? ev.getY() : ev.getX();
    }

    protected float getTranslation(View v) {
        return mSwipeDirection == X ? v.getTranslationX() : v.getTranslationY();
    }

    private float getVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getXVelocity() :
                vt.getYVelocity();
    }

    protected ObjectAnimator createTranslationAnimation(View v, float newPos) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v,
                mSwipeDirection == X ? View.TRANSLATION_X : View.TRANSLATION_Y, newPos);
        return anim;
    }

    private float getPerpendicularVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getYVelocity() :
                vt.getXVelocity();
    }

    protected Animator getViewTranslationAnimator(View v, float target,
            AnimatorUpdateListener listener) {
        ObjectAnimator anim = createTranslationAnimation(v, target);
        if (listener != null) {
            anim.addUpdateListener(listener);
        }
        return anim;
    }

    protected void setTranslation(View v, float translate) {
        if (v == null) {
            return;
        }
        if (mSwipeDirection == X) {
            v.setTranslationX(translate);
        } else {
            v.setTranslationY(translate);
        }
    }

    protected float getSize(View v) {
        return mSwipeDirection == X ? v.getMeasuredWidth() : v.getMeasuredHeight();
    }

    public void setMinSwipeProgress(float minSwipeProgress) {
        mMinSwipeProgress = minSwipeProgress;
    }

    public void setMaxSwipeProgress(float maxSwipeProgress) {
        mMaxSwipeProgress = maxSwipeProgress;
    }

    private float getSwipeProgressForOffset(View view, float translation) {
        float viewSize = getSize(view);
        float result = Math.abs(translation / viewSize);
        return Math.min(Math.max(mMinSwipeProgress, result), mMaxSwipeProgress);
    }

    private float getSwipeAlpha(float progress) {
        if (mFadeDependingOnAmountSwiped) {
            // The more progress has been fade, the lower the alpha value so that the view fades.
            return Math.max(1 - progress, 0);
        }

        return 1f - Math.max(0, Math.min(1, progress / SWIPE_PROGRESS_FADE_END));
    }

    private void updateSwipeProgressFromOffset(View animView, boolean dismissable) {
        updateSwipeProgressFromOffset(animView, dismissable, getTranslation(animView));
    }

    private void updateSwipeProgressFromOffset(View animView, boolean dismissable,
            float translation) {
        float swipeProgress = getSwipeProgressForOffset(animView, translation);
        if (!mCallback.updateSwipeProgress(animView, dismissable, swipeProgress)) {
            if (FADE_OUT_DURING_SWIPE && dismissable) {
                if (!mDisableHwLayers) {
                    if (swipeProgress != 0f && swipeProgress != 1f) {
                        animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    } else {
                        animView.setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                }
                animView.setAlpha(getSwipeAlpha(swipeProgress));
            }
        }
        invalidateGlobalRegion(animView);
    }

    // invalidate the view's own bounds all the way up the view hierarchy
    public static void invalidateGlobalRegion(View view) {
        invalidateGlobalRegion(
            view,
            new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
    }

    // invalidate a rectangle relative to the view's coordinate system all the way up the view
    // hierarchy
    public static void invalidateGlobalRegion(View view, RectF childBounds) {
        //childBounds.offset(view.getTranslationX(), view.getTranslationY());
        if (DEBUG_INVALIDATE)
            Log.v(TAG, "-------------");
        while (view.getParent() != null && view.getParent() instanceof View) {
            view = (View) view.getParent();
            view.getMatrix().mapRect(childBounds);
            view.invalidate((int) Math.floor(childBounds.left),
                            (int) Math.floor(childBounds.top),
                            (int) Math.ceil(childBounds.right),
                            (int) Math.ceil(childBounds.bottom));
            if (DEBUG_INVALIDATE) {
                Log.v(TAG, "INVALIDATE(" + (int) Math.floor(childBounds.left)
                        + "," + (int) Math.floor(childBounds.top)
                        + "," + (int) Math.ceil(childBounds.right)
                        + "," + (int) Math.ceil(childBounds.bottom));
            }
        }
    }

    public void cancelLongPress() {
        if (mWatchLongPress != null) {
            mHandler.removeCallbacks(mWatchLongPress);
            mWatchLongPress = null;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        if (mCurrView instanceof ExpandableNotificationRow) {
            NotificationMenuRowPlugin nmr = ((ExpandableNotificationRow) mCurrView).getProvider();
            mMenuRowIntercepting = nmr.onInterceptTouchEvent(mCurrView, ev);
        }
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTouchAboveFalsingThreshold = false;
                mDragging = false;
                mSnappingChild = false;
                mLongPressSent = false;
                mVelocityTracker.clear();
                mCurrView = mCallback.getChildAtPosition(ev);

                if (mCurrView != null) {
                    onDownUpdate(mCurrView, ev);
                    mCanCurrViewBeDimissed = mCallback.canChildBeDismissed(mCurrView);
                    mVelocityTracker.addMovement(ev);
                    mInitialTouchPos = getPos(ev);
                    mPerpendicularInitialTouchPos = getPerpendicularPos(ev);
                    mTranslation = getTranslation(mCurrView);
                    if (mWatchLongPress == null) {
                        mWatchLongPress = new Runnable() {
                            @Override
                            public void run() {
                                if (mCurrView != null && !mLongPressSent) {
                                    mLongPressSent = true;
                                    mCurrView.getLocationOnScreen(mTmpPos);
                                    final int x = (int) ev.getRawX() - mTmpPos[0];
                                    final int y = (int) ev.getRawY() - mTmpPos[1];
                                    if (mCurrView instanceof ExpandableNotificationRow) {
                                        mCurrView.sendAccessibilityEvent(
                                                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                                        ExpandableNotificationRow currRow =
                                                (ExpandableNotificationRow) mCurrView;
                                        currRow.doLongClickCallback(x, y);
                                    }
                                }
                            }
                        };
                    }
                    mHandler.postDelayed(mWatchLongPress, mLongPressTimeout);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null && !mLongPressSent) {
                    mVelocityTracker.addMovement(ev);
                    float pos = getPos(ev);
                    float perpendicularPos = getPerpendicularPos(ev);
                    float delta = pos - mInitialTouchPos;
                    float deltaPerpendicular = perpendicularPos - mPerpendicularInitialTouchPos;
                    if (Math.abs(delta) > mPagingTouchSlop
                            && Math.abs(delta) > Math.abs(deltaPerpendicular)) {
                        if (mCallback.canChildBeDragged(mCurrView)) {
                            mCallback.onBeginDrag(mCurrView);
                            mDragging = true;
                            mInitialTouchPos = getPos(ev);
                            mTranslation = getTranslation(mCurrView);
                        }
                        cancelLongPress();
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                final boolean captured = (mDragging || mLongPressSent || mMenuRowIntercepting);
                mDragging = false;
                mCurrView = null;
                mLongPressSent = false;
                mMenuRowIntercepting = false;
                cancelLongPress();
                if (captured) return true;
                break;
        }
        return mDragging || mLongPressSent || mMenuRowIntercepting;
    }

    /**
     * @param view The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     * @param useAccelerateInterpolator Should an accelerating Interpolator be used
     */
    public void dismissChild(final View view, float velocity, boolean useAccelerateInterpolator) {
        dismissChild(view, velocity, null /* endAction */, 0 /* delay */,
                useAccelerateInterpolator, 0 /* fixedDuration */, false /* isDismissAll */);
    }

    /**
     * @param view The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     * @param endAction The action to perform at the end
     * @param delay The delay after which we should start
     * @param useAccelerateInterpolator Should an accelerating Interpolator be used
     * @param fixedDuration If not 0, this exact duration will be taken
     */
    public void dismissChild(final View animView, float velocity, final Runnable endAction,
            long delay, boolean useAccelerateInterpolator, long fixedDuration,
            boolean isDismissAll) {
        final boolean canBeDismissed = mCallback.canChildBeDismissed(animView);
        float newPos;
        boolean isLayoutRtl = animView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // if we use the Menu to dismiss an item in landscape, animate up
        boolean animateUpForMenu = velocity == 0 && (getTranslation(animView) == 0 || isDismissAll)
                && mSwipeDirection == Y;
        // if the language is rtl we prefer swiping to the left
        boolean animateLeftForRtl = velocity == 0 && (getTranslation(animView) == 0 || isDismissAll)
                && isLayoutRtl;
        boolean animateLeft = (Math.abs(velocity) > getEscapeVelocity() && velocity < 0) ||
                (getTranslation(animView) < 0 && !isDismissAll);
        if (animateLeft || animateLeftForRtl || animateUpForMenu) {
            newPos = -getSize(animView);
        } else {
            newPos = getSize(animView);
        }
        long duration;
        if (fixedDuration == 0) {
            duration = MAX_ESCAPE_ANIMATION_DURATION;
            if (velocity != 0) {
                duration = Math.min(duration,
                        (int) (Math.abs(newPos - getTranslation(animView)) * 1000f / Math
                                .abs(velocity))
                );
            } else {
                duration = DEFAULT_ESCAPE_ANIMATION_DURATION;
            }
        } else {
            duration = fixedDuration;
        }

        if (!mDisableHwLayers) {
            animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        AnimatorUpdateListener updateListener = new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                onTranslationUpdate(animView, (float) animation.getAnimatedValue(), canBeDismissed);
            }
        };

        Animator anim = getViewTranslationAnimator(animView, newPos, updateListener);
        if (anim == null) {
            return;
        }
        if (useAccelerateInterpolator) {
            anim.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
            anim.setDuration(duration);
        } else {
            mFlingAnimationUtils.applyDismissing(anim, getTranslation(animView),
                    newPos, velocity, getSize(animView));
        }
        if (delay > 0) {
            anim.setStartDelay(delay);
        }
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                updateSwipeProgressFromOffset(animView, canBeDismissed);
                mDismissPendingMap.remove(animView);
                boolean wasRemoved = false;
                if (animView instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) animView;
                    wasRemoved = row.isRemoved();
                }
                if (!mCancelled || wasRemoved) {
                    mCallback.onChildDismissed(animView);
                }
                if (endAction != null) {
                    endAction.run();
                }
                if (!mDisableHwLayers) {
                    animView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            }
        });

        prepareDismissAnimation(animView, anim);
        mDismissPendingMap.put(animView, anim);
        anim.start();
    }

    /**
     * Called to update the dismiss animation.
     */
    protected void prepareDismissAnimation(View view, Animator anim) {
        // Do nothing
    }

    public void snapChild(final View animView, final float targetLeft, float velocity) {
        final boolean canBeDismissed = mCallback.canChildBeDismissed(animView);
        AnimatorUpdateListener updateListener = new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                onTranslationUpdate(animView, (float) animation.getAnimatedValue(), canBeDismissed);
            }
        };

        Animator anim = getViewTranslationAnimator(animView, targetLeft, updateListener);
        if (anim == null) {
            return;
        }
        int duration = SNAP_ANIM_LEN;
        anim.setDuration(duration);
        anim.addListener(new AnimatorListenerAdapter() {
            boolean wasCancelled = false;

            @Override
            public void onAnimationCancel(Animator animator) {
                wasCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                mSnappingChild = false;
                if (!wasCancelled) {
                    updateSwipeProgressFromOffset(animView, canBeDismissed);
                    mCallback.onChildSnappedBack(animView, targetLeft);
                }
            }
        });
        prepareSnapBackAnimation(animView, anim);
        mSnappingChild = true;
        anim.start();
    }

    /**
     * Called to update the snap back animation.
     */
    protected void prepareSnapBackAnimation(View view, Animator anim) {
        // Do nothing
    }

    /**
     * Called when there's a down event.
     */
    public void onDownUpdate(View currView, MotionEvent ev) {
        // Do nothing
    }

    /**
     * Called on a move event.
     */
    protected void onMoveUpdate(View view, MotionEvent ev, float totalTranslation, float delta) {
        // Do nothing
    }

    /**
     * Called in {@link AnimatorUpdateListener#onAnimationUpdate(ValueAnimator)} when the current
     * view is being animated to dismiss or snap.
     */
    public void onTranslationUpdate(View animView, float value, boolean canBeDismissed) {
        updateSwipeProgressFromOffset(animView, canBeDismissed, value);
    }

    private void snapChildInstantly(final View view) {
        final boolean canAnimViewBeDismissed = mCallback.canChildBeDismissed(view);
        setTranslation(view, 0);
        updateSwipeProgressFromOffset(view, canAnimViewBeDismissed);
    }

    /**
     * Called when a view is updated to be non-dismissable, if the view was being dismissed before
     * the update this will handle snapping it back into place.
     *
     * @param view the view to snap if necessary.
     * @param animate whether to animate the snap or not.
     * @param targetLeft the target to snap to.
     */
    public void snapChildIfNeeded(final View view, boolean animate, float targetLeft) {
        if ((mDragging && mCurrView == view) || mSnappingChild) {
            return;
        }
        boolean needToSnap = false;
        Animator dismissPendingAnim = mDismissPendingMap.get(view);
        if (dismissPendingAnim != null) {
            needToSnap = true;
            dismissPendingAnim.cancel();
        } else if (getTranslation(view) != 0) {
            needToSnap = true;
        }
        if (needToSnap) {
            if (animate) {
                snapChild(view, targetLeft, 0.0f /* velocity */);
            } else {
                snapChildInstantly(view);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mLongPressSent && !mMenuRowIntercepting) {
            return true;
        }

        if (!mDragging && !mMenuRowIntercepting) {
            if (mCallback.getChildAtPosition(ev) != null) {

                // We are dragging directly over a card, make sure that we also catch the gesture
                // even if nobody else wants the touch event.
                onInterceptTouchEvent(ev);
                return true;
            } else {

                // We are not doing anything, make sure the long press callback
                // is not still ticking like a bomb waiting to go off.
                cancelLongPress();
                return false;
            }
        }

        mVelocityTracker.addMovement(ev);
        final int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    float delta = getPos(ev) - mInitialTouchPos;
                    float absDelta = Math.abs(delta);
                    if (absDelta >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                    }
                    // don't let items that can't be dismissed be dragged more than
                    // maxScrollDistance
                    if (CONSTRAIN_SWIPE && !mCallback.canChildBeDismissed(mCurrView)) {
                        float size = getSize(mCurrView);
                        float maxScrollDistance = MAX_SCROLL_SIZE_FRACTION * size;
                        if (absDelta >= size) {
                            delta = delta > 0 ? maxScrollDistance : -maxScrollDistance;
                        } else {
                            delta = maxScrollDistance * (float) Math.sin((delta/size)*(Math.PI/2));
                        }
                    }

                    setTranslation(mCurrView, mTranslation + delta);
                    updateSwipeProgressFromOffset(mCurrView, mCanCurrViewBeDimissed);
                    onMoveUpdate(mCurrView, ev, mTranslation + delta, delta);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mCurrView == null) {
                    break;
                }
                mVelocityTracker.computeCurrentVelocity(1000 /* px/sec */, getMaxVelocity());
                float velocity = getVelocity(mVelocityTracker);

                if (!handleUpEvent(ev, mCurrView, velocity, getTranslation(mCurrView))) {
                    if (isDismissGesture(ev)) {
                        // flingadingy
                        dismissChild(mCurrView, velocity,
                                !swipedFastEnough() /* useAccelerateInterpolator */);
                    } else {
                        // snappity
                        mCallback.onDragCancelled(mCurrView);
                        snapChild(mCurrView, 0 /* leftTarget */, velocity);
                    }
                    mCurrView = null;
                }
                mDragging = false;
                break;
        }
        return true;
    }

    private int getFalsingThreshold() {
        float factor = mCallback.getFalsingThresholdFactor();
        return (int) (mFalsingThreshold * factor);
    }

    private float getMaxVelocity() {
        return MAX_DISMISS_VELOCITY * mDensityScale;
    }

    protected float getEscapeVelocity() {
        return getUnscaledEscapeVelocity() * mDensityScale;
    }

    protected float getUnscaledEscapeVelocity() {
        return SWIPE_ESCAPE_VELOCITY;
    }

    protected long getMaxEscapeAnimDuration() {
        return MAX_ESCAPE_ANIMATION_DURATION;
    }

    protected boolean swipedFarEnough() {
        float translation = getTranslation(mCurrView);
        return DISMISS_IF_SWIPED_FAR_ENOUGH
                && Math.abs(translation) > SWIPED_FAR_ENOUGH_SIZE_FRACTION * getSize(mCurrView);
    }

    public boolean isDismissGesture(MotionEvent ev) {
        return ev.getActionMasked() == MotionEvent.ACTION_UP
                && !isFalseGesture(ev) && (swipedFastEnough() || swipedFarEnough())
                && mCallback.canChildBeDismissed(mCurrView);
    }

    public boolean isFalseGesture(MotionEvent ev) {
        boolean falsingDetected = mCallback.isAntiFalsingNeeded();
        if (mFalsingManager.isClassiferEnabled()) {
            falsingDetected = falsingDetected && mFalsingManager.isFalseTouch();
        } else {
            falsingDetected = falsingDetected && !mTouchAboveFalsingThreshold;
        }
        return falsingDetected;
    }

    protected boolean swipedFastEnough() {
        float velocity = getVelocity(mVelocityTracker);
        float translation = getTranslation(mCurrView);
        boolean ret = (Math.abs(velocity) > getEscapeVelocity())
                && (velocity > 0) == (translation > 0);
        return ret;
    }

    protected boolean handleUpEvent(MotionEvent ev, View animView, float velocity,
            float translation) {
        return false;
    }

    public interface Callback {
        View getChildAtPosition(MotionEvent ev);

        boolean canChildBeDismissed(View v);

        boolean isAntiFalsingNeeded();

        void onBeginDrag(View v);

        void onChildDismissed(View v);

        void onDragCancelled(View v);

        /**
         * Called when the child is snapped to a position.
         *
         * @param animView the view that was snapped.
         * @param targetLeft the left position the view was snapped to.
         */
        void onChildSnappedBack(View animView, float targetLeft);

        /**
         * Updates the swipe progress on a child.
         *
         * @return if true, prevents the default alpha fading.
         */
        boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress);

        /**
         * @return The factor the falsing threshold should be multiplied with
         */
        float getFalsingThresholdFactor();

        /**
         * @return If true, the given view is draggable.
         */
        default boolean canChildBeDragged(@NonNull View animView) { return true; }
    }
}
