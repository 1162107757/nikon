package com.nikonpreset.usbprobe.ui;

import android.content.Context;
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
        setClickable(true);
        setFocusable(true);
        setContentDescription("照片，可双指缩放、双击放大，轻触显示或隐藏操作");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                performClick();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                if (hasPhoto()) {
                    if (zoom > 1.01f) resetZoom();
                    else zoomAt(DOUBLE_TAP_ZOOM, event.getX(), event.getY());
                }
                return true;
            }
        });
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (!hasPhoto()) return false;
                float factor = detector.getScaleFactor();
                if (Float.isFinite(factor) && factor > 0f) {
                    zoomAt(zoom * factor, detector.getFocusX(), detector.getFocusY());
                }
                return true;
            }
        });
        // Double-tap is reserved for a predictable fitted / enlarged toggle.
        scales.setQuickScaleEnabled(false);
    }

    public void setPhoto(Bitmap bitmap) {
        if (photo == bitmap) return;
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
            setPhoto(bitmap);
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

    public void resetZoom() {
        zoom = 1f;
        updateFitScale();
        constrainOffsets();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
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
        if (!hasPhoto()) return;
        imageMatrix.setScale(fitScale * zoom, fitScale * zoom);
        imageMatrix.postTranslate(offsetX, offsetY);
        canvas.drawBitmap(photo, imageMatrix, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
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
                int pointerIndex = event.findPointerIndex(activePointer);
                if (pointerIndex >= 0) {
                    float x = event.getX(pointerIndex);
                    float y = event.getY(pointerIndex);
                    if (hasPhoto() && !scales.isInProgress() && event.getPointerCount() == 1 && zoom > 1f) {
                        offsetX += x - lastX;
                        offsetY += y - lastY;
                        constrainOffsets();
                        invalidate();
                    }
                    lastX = x;
                    lastY = y;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // Re-anchor the remaining finger so ending a pinch cannot jump the image.
                int removedIndex = event.getActionIndex();
                int remainingIndex = removedIndex == 0 ? 1 : 0;
                if (remainingIndex < event.getPointerCount()) {
                    activePointer = event.getPointerId(remainingIndex);
                    lastX = event.getX(remainingIndex);
                    lastY = event.getY(remainingIndex);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
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
        invalidate();
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
