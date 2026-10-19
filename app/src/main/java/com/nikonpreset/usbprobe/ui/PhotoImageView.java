package com.nikonpreset.usbprobe.ui;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.view.animation.DecelerateInterpolator;
import java.util.function.IntConsumer;

/** A fitted photo with pinch, double-tap and bounded drag gestures. Bitmap ownership stays with its caller. */
public final class PhotoImageView extends View {
    private static final float MAX_ZOOM = 4f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix imageMatrix = new Matrix();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scales;
    private Bitmap photo;
    private Runnable singleTap;
    private IntConsumer swipe;
    private IntConsumer pageBoundary;
    private boolean retryNextAvailable;
    private final PhotoSwipeGesture swipeGesture = new PhotoSwipeGesture();
    private final PhotoPagerMotion pager = new PhotoPagerMotion();
    private Bitmap previousPhoto, nextPhoto;
    private boolean canPrevious, canNext;
    private int pagingAxis;
    private float downX, downY, pageGrabOffset;
    private final float touchSlop, maximumFlingVelocity;
    private VelocityTracker pageVelocity;
    private long lastPageFrame;
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String placeholderText = "正在加载照片…";
    private final Runnable pageFrame = this::advancePage;
    private ValueAnimator zoomAnimator;
    private float scaleFocusX, scaleFocusY;
    private float fitScale = 1f;
    private float zoom = 1f;
    private float offsetX, offsetY;
    private float lastX, lastY;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;

    public PhotoImageView(Context context) {
        this(context, null);
    }

    public PhotoImageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        maximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        placeholderPaint.setColor(0xFFB8BCC2);
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
        placeholderPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        setClickable(true);
        setFocusable(true);
        setContentDescription("照片，左右滑动切换，双指缩放、双击放大，轻触显示或隐藏操作");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                performClick();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                swipeGesture.cancel();
                returnPageHome();
                if (hasPhoto()) {
                    animateZoom(zoom > 1.01f ? 1f : DOUBLE_TAP_ZOOM, event.getX(), event.getY());
                }
                return true;
            }
        });
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelZoomAnimation(); swipeGesture.cancel();
                returnPageHome();
                if (Math.abs(pager.offset()) > .5f) return false;
                scaleFocusX = detector.getFocusX(); scaleFocusY = detector.getFocusY();
                return hasPhoto();
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (!hasPhoto()) return false;
                float factor = detector.getScaleFactor();
                if (Float.isFinite(factor) && factor > 0f) {
                    float oldScale = fitScale * zoom;
                    float imageX = (scaleFocusX - offsetX) / oldScale;
                    float imageY = (scaleFocusY - offsetY) / oldScale;
                    zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
                    scaleFocusX = detector.getFocusX(); scaleFocusY = detector.getFocusY();
                    offsetX = scaleFocusX - imageX * fitScale * zoom;
                    offsetY = scaleFocusY - imageY * fitScale * zoom;
                    constrainOffsets(); postInvalidateOnAnimation();
                }
                return true;
            }
        });
        // Double-tap is reserved for a predictable fitted / enlarged toggle.
        scales.setQuickScaleEnabled(false);
    }

    public void setPhoto(Bitmap bitmap) {
        if (photo == bitmap) return;
        cancelZoomAnimation(); swipeGesture.cancel(); resetPager();
        photo = bitmap;
        resetZoom();
    }

    /**
     * Replaces a preview with the same already-oriented photo at another resolution.
     * The caller explicitly opts in: switching to a different photo should use setPhoto.
     * Zoom is relative to the fitted image, and its center is normalized to image dimensions,
     * so the replacement also works after the view rotates or changes size.
     */
    public void setPhotoPreservingTransform(Bitmap bitmap) {
        if (photo == bitmap) return;
        if (!hasPhoto() || bitmap == null || bitmap.isRecycled() || getWidth() <= 0 || getHeight() <= 0) {
            cancelZoomAnimation();
            photo = bitmap;
            resetZoom();
            return;
        }
        // Before/after images share oriented dimensions. Keep their transform
        // bit-for-bit so repeated comparisons cannot accumulate rounding drift.
        if (photo.getWidth() == bitmap.getWidth() && photo.getHeight() == bitmap.getHeight()) {
            photo = bitmap;
            invalidate();
            return;
        }
        float previousScale = fitScale * zoom;
        if (!Float.isFinite(previousScale) || previousScale <= 0f) {
            setPhoto(bitmap);
            return;
        }
        float centerX = (getWidth() / 2f - offsetX) / (previousScale * photo.getWidth());
        float centerY = (getHeight() / 2f - offsetY) / (previousScale * photo.getHeight());
        photo = bitmap;
        zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom));
        updateFitScale();
        offsetX = getWidth() / 2f - centerX * photo.getWidth() * fitScale * zoom;
        offsetY = getHeight() / 2f - centerY * photo.getHeight() * fitScale * zoom;
        constrainOffsets();
        invalidate();
    }

    public void setOnSingleTap(Runnable callback) {
        singleTap = callback;
    }

    public void setOnSwipe(IntConsumer callback) {
        swipe = callback;
        if (callback == null) { swipeGesture.cancel(); resetPager(); }
    }

    /** Neighbour bitmaps remain caller-owned; null draws a loading placeholder without decoding. */
    public void setPagingNeighbors(Bitmap previous, boolean previousAvailable, Bitmap next, boolean nextAvailable) {
        previousPhoto = previous; nextPhoto = next;
        canPrevious = previousAvailable; canNext = nextAvailable;
        if (pager.pendingDirection() < 0 && !canPrevious || pager.pendingDirection() > 0 && !canNext) returnPageHome();
        postInvalidateOnAnimation();
    }

    public void setPlaceholderText(String label) {
        placeholderText = label == null || label.isEmpty() ? "暂无预览" : label;
        postInvalidateOnAnimation();
    }

    /** Explicit edge release may retry a paused page; it never invents or commits a neighbour. */
    public void setOnPageBoundary(IntConsumer callback, boolean canRetryNext) {
        pageBoundary = callback;
        retryNextAvailable = canRetryNext && callback != null;
    }

    public void resetZoom() {
        zoom = 1f;
        updateFitScale();
        constrainOffsets();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetPager();
        float previousScale = fitScale * zoom;
        float centerX = previousScale > 0f ? (oldWidth / 2f - offsetX) / previousScale : 0f;
        float centerY = previousScale > 0f ? (oldHeight / 2f - offsetY) / previousScale : 0f;
        updateFitScale();
        if (hasPhoto() && oldWidth > 0 && oldHeight > 0 && zoom > 1f) {
            offsetX = width / 2f - centerX * fitScale * zoom;
            offsetY = height / 2f - centerY * fitScale * zoom;
        }
        constrainOffsets();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int saved = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        if (hasPhoto()) {
            int currentPage = canvas.save();
            canvas.clipRect(pager.offset(), 0, getWidth() + pager.offset(), getHeight());
            imageMatrix.setScale(fitScale * zoom, fitScale * zoom);
            imageMatrix.postTranslate(offsetX + pager.offset(), offsetY);
            canvas.drawBitmap(photo, imageMatrix, paint);
            canvas.restoreToCount(currentPage);
        } else {
            int currentPage = canvas.save();
            canvas.clipRect(pager.offset(), 0, getWidth() + pager.offset(), getHeight());
            canvas.drawText(placeholderText, pager.offset() + getWidth() / 2f, getHeight() / 2f, placeholderPaint);
            canvas.restoreToCount(currentPage);
        }
        if (pager.offset() < 0 && canNext) drawNeighbour(canvas, nextPhoto, getWidth() + pager.offset());
        else if (pager.offset() > 0 && canPrevious) drawNeighbour(canvas, previousPhoto, -getWidth() + pager.offset());
        canvas.restoreToCount(saved);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelZoomAnimation();
            removeCallbacks(pageFrame); pager.grab();
            pageGrabOffset = pager.offset(); downX = event.getX(); downY = event.getY(); pagingAxis = 0;
            if (pageVelocity == null) pageVelocity = VelocityTracker.obtain();
            else pageVelocity.clear();
            swipeGesture.begin(event.getX(), event.getY(), event.getEventTime(), PhotoSwipeGesture.canStart(
                    hasPhoto(), canPrevious && swipe != null,
                    (canNext || retryNextAvailable && pageBoundary != null) && swipe != null, zoom));
        }
        if (pageVelocity != null) pageVelocity.addMovement(event);
        if (event.getPointerCount() > 1) { cancelZoomAnimation(); swipeGesture.cancel(); returnPageHome(); }
        scales.onTouchEvent(event);
        gestures.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
                disallowParentIntercept(true);
                break;
            case MotionEvent.ACTION_MOVE:
                swipeGesture.move(event.getX(), event.getY(), zoom <= 1.001f, event.getPointerCount());
                updatePageDrag(event);
                int pointerIndex = event.findPointerIndex(activePointer);
                if (pointerIndex >= 0) {
                    float x = event.getX(pointerIndex);
                    float y = event.getY(pointerIndex);
                    if (hasPhoto() && !scales.isInProgress() && event.getPointerCount() == 1 && zoom > 1f) {
                        offsetX += x - lastX;
                        offsetY += y - lastY;
                        constrainOffsets();
                        postInvalidateOnAnimation();
                    }
                    lastX = x;
                    lastY = y;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // Re-anchor the remaining finger so ending a pinch cannot jump the image.
                int removedIndex = event.getActionIndex();
                int remainingIndex = event.findPointerIndex(activePointer);
                if (remainingIndex == removedIndex || remainingIndex < 0) remainingIndex = removedIndex == 0 ? 1 : 0;
                if (remainingIndex < event.getPointerCount()) {
                    activePointer = event.getPointerId(remainingIndex);
                    lastX = event.getX(remainingIndex);
                    lastY = event.getY(remainingIndex);
                }
                break;
            case MotionEvent.ACTION_UP:
                float releaseVelocity = 0;
                if (pageVelocity != null) {
                    pageVelocity.computeCurrentVelocity(1000, maximumFlingVelocity);
                    releaseVelocity = pageVelocity.getXVelocity(activePointer);
                }
                swipeGesture.move(event.getX(), event.getY(), zoom <= 1.001f, 1);
                boolean cancelPage = !swipeGesture.isHorizontal(event.getX()) || pagingAxis != 1 || zoom > 1.001f;
                int boundary = cancelPage ? 0 : swipeGesture.boundaryDirection(event.getX(), event.getEventTime(),
                        Math.max(48f * getResources().getDisplayMetrics().density,
                                Math.min(getWidth() * .14f, 88f * getResources().getDisplayMetrics().density)), releaseVelocity);
                if (boundary > 0 && canNext || boundary < 0 && canPrevious) boundary = 0;
                swipeGesture.cancel();
                pager.release(releaseVelocity, getWidth(), canPrevious && swipe != null, canNext && swipe != null, cancelPage);
                startPageAnimation(); recyclePageVelocity();
                activePointer = MotionEvent.INVALID_POINTER_ID;
                disallowParentIntercept(false);
                if (boundary != 0 && swipe != null && pageBoundary != null) pageBoundary.accept(boundary);
                break;
            case MotionEvent.ACTION_CANCEL:
                swipeGesture.cancel();
                returnPageHome(); recyclePageVelocity();
                activePointer = MotionEvent.INVALID_POINTER_ID;
                disallowParentIntercept(false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (singleTap != null) singleTap.run();
        return true;
    }

    private boolean hasPhoto() {
        return photo != null && !photo.isRecycled();
    }

    private void updateFitScale() {
        if (hasPhoto() && getWidth() > 0 && getHeight() > 0) {
            fitScale = Math.min((float) getWidth() / photo.getWidth(), (float) getHeight() / photo.getHeight());
        } else {
            fitScale = 1f;
        }
    }

    private void zoomAt(float targetZoom, float focusX, float focusY) {
        float oldScale = fitScale * zoom;
        if (oldScale <= 0f) return;
        float imageX = (focusX - offsetX) / oldScale;
        float imageY = (focusY - offsetY) / oldScale;
        zoom = Math.max(1f, Math.min(MAX_ZOOM, targetZoom));
        float scale = fitScale * zoom;
        offsetX = focusX - imageX * scale;
        offsetY = focusY - imageY * scale;
        constrainOffsets();
        postInvalidateOnAnimation();
    }

    private void animateZoom(float target, float focusX, float focusY) {
        cancelZoomAnimation();
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            zoomAt(target, focusX, focusY); return;
        }
        zoomAnimator = ValueAnimator.ofFloat(zoom, target);
        zoomAnimator.setDuration(240);
        zoomAnimator.setInterpolator(new DecelerateInterpolator(2f));
        zoomAnimator.addUpdateListener(animation -> {
            if (hasPhoto()) zoomAt((Float) animation.getAnimatedValue(), focusX, focusY);
        });
        zoomAnimator.start();
    }

    private void cancelZoomAnimation() {
        if (zoomAnimator != null) { zoomAnimator.cancel(); zoomAnimator = null; }
    }

    @Override protected void onDetachedFromWindow() {
        cancelZoomAnimation(); swipeGesture.cancel(); resetPager(); recyclePageVelocity();
        super.onDetachedFromWindow();
    }

    private void updatePageDrag(MotionEvent event) {
        if (!swipeGesture.isArmed() || event.getPointerCount() != 1 || scales.isInProgress() || zoom > 1.001f) return;
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (pagingAxis == 0) {
            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) pagingAxis = 1;
            else if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                pagingAxis = -1; swipeGesture.cancel(); returnPageHome();
            }
        }
        if (pagingAxis == 1) {
            pager.drag(pageGrabOffset + dx, getWidth(), canPrevious && swipe != null, canNext && swipe != null);
            postInvalidateOnAnimation();
        }
    }

    private void drawNeighbour(Canvas canvas, Bitmap neighbour, float pageLeft) {
        if (neighbour == null || neighbour.isRecycled()) {
            canvas.drawText("正在加载…", pageLeft + getWidth() / 2f, getHeight() / 2f, placeholderPaint);
            return;
        }
        float scale = Math.min((float) getWidth() / neighbour.getWidth(), (float) getHeight() / neighbour.getHeight());
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(pageLeft + (getWidth() - neighbour.getWidth() * scale) / 2f,
                (getHeight() - neighbour.getHeight() * scale) / 2f);
        canvas.drawBitmap(neighbour, imageMatrix, paint);
    }

    private void startPageAnimation() {
        removeCallbacks(pageFrame);
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            int completed = pager.finishImmediately(); postInvalidateOnAnimation(); commitPage(completed); return;
        }
        lastPageFrame = System.nanoTime();
        postOnAnimation(pageFrame);
    }

    private void advancePage() {
        long now = System.nanoTime();
        int completed = pager.advance((now - lastPageFrame) / 1_000_000_000f);
        lastPageFrame = now;
        postInvalidateOnAnimation();
        if (pager.isSettling()) postOnAnimation(pageFrame);
        else commitPage(completed);
    }

    private void commitPage(int direction) {
        if (direction != 0 && swipe != null && (direction > 0 ? canNext : canPrevious)) swipe.accept(direction);
    }

    private void returnPageHome() {
        if (Math.abs(pager.offset()) < .5f && !pager.isSettling()) return;
        if (pager.isSettling() && pager.pendingDirection() == 0) return;
        pager.spring(0, getWidth(), 0);
        startPageAnimation();
    }

    private void resetPager() {
        removeCallbacks(pageFrame); pager.reset(); pagingAxis = 0;
    }

    private void recyclePageVelocity() {
        if (pageVelocity != null) { pageVelocity.recycle(); pageVelocity = null; }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        boolean fitted = zoom <= 1.001f && swipe != null;
        info.setScrollable(fitted && (canPrevious || canNext || retryNextAvailable && pageBoundary != null));
        if (fitted && canNext) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        else if (fitted && retryNextAvailable && pageBoundary != null) info.addAction(
                new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "重试读取更多照片"));
        if (fitted && canPrevious) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        int direction = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1
                : action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 0;
        if (direction == 1 && !canNext && retryNextAvailable && pageBoundary != null && zoom <= 1.001f && swipe != null) {
            pageBoundary.accept(1); return true;
        }
        if (direction != 0 && zoom <= 1.001f && swipe != null && (direction > 0 ? canNext : canPrevious)) {
            swipeGesture.cancel(); pager.grab(); pager.spring(direction, getWidth(), 0); startPageAnimation(); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private void constrainOffsets() {
        if (!hasPhoto()) {
            offsetX = offsetY = 0f;
            return;
        }
        float width = photo.getWidth() * fitScale * zoom;
        float height = photo.getHeight() * fitScale * zoom;
        offsetX = width <= getWidth() ? (getWidth() - width) / 2f
                : Math.max(getWidth() - width, Math.min(0f, offsetX));
        offsetY = height <= getHeight() ? (getHeight() - height) / 2f
                : Math.max(getHeight() - height, Math.min(0f, offsetY));
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
