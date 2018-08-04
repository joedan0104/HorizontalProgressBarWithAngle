package com.joe.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.support.v4.math.MathUtils;
import android.support.v4.util.Pools;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.view.View;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;

import com.joe.sample.R;
import com.nineoldandroids.animation.ObjectAnimator;

import java.util.ArrayList;

/**
 * 带尖角的水平进度条
 *
 * @author Joe
 * @version 1.0 2018/07/30
 */
public class HorizontalProgressBarWithAngle extends View {
    private static final String TAG = HorizontalProgressBarWithAngle.class.getSimpleName();
    /** Duration of smooth progress animations. */
    private static final int PROGRESS_ANIM_DURATION = 80;
    private static final int MAX_LEVEL = 10000;
    private static final int TIMEOUT_SEND_ACCESSIBILITY_EVENT = 200;
    /** Interpolator used for smooth progress animations. */
    private static final DecelerateInterpolator PROGRESS_ANIM_INTERPOLATOR =
            new DecelerateInterpolator();

    protected final static String MATERIALDESIGNXML = "http://schemas.android.com/apk/res-auto";
    protected final static String ANDROIDXML = "http://schemas.android.com/apk/res/android";
    /**
     * 是否需要刷新
     */
    private boolean mNoInvalidate;
    /**
     * 不确定的
     */
    private boolean mIndeterminate;
    int mMinWidth;
    int mMaxWidth;
    int mMinHeight;
    int mMaxHeight;

    /** Value used to track progress animation, in the range [0...1]. */
    private float mVisualProgress;
    /**
     * 圆角半径
     */
    int mCornerRadius;

    /**
     * 当前进度
     */
    private int mProgress;
    /**
     * 第二进度
     */
    private int mSecondaryProgress;
    /**
     * 最小值
     */
    private int mMin;
    private boolean mMinInitialized;
    /**
     * 最大进度值
     */
    private int mMax;
    private boolean mMaxInitialized;
    /**
     * 进度条背景色
     */
    protected int mBarColor = Color.GRAY;
    /**
     * 加载进度条的颜色
     */
    protected int mProgressColor = 0xFF00CDB0;
    /**
     * 第二加载进度条的颜色
     */
    protected int mSecondaryProgressColor = 0xffe6e6e6;
    /**
     * UI线程
     */
    private long mUiThreadId;
    /**
     * 画笔
     */
    private Paint mPaint;
    /**
     * 进度条背景区域
     */
    private Rect mRect;
    /**
     * 进度条的区域
     */
    private Rect mProgressRect;
    Path mPath = new Path();
    /**
     * 进度条背景区域
     */
    private GradientDrawable mBarDrawable;
    private GradientDrawable mProgressDrawable;
    private Drawable mCurrentDrawable;
    private AccessibilityEventSender mAccessibilityEventSender;
    /**
     * 进度刷新Runnable
     */
    private RefreshProgressRunnable mRefreshProgressRunnable;
    /**
     * 刷新数据列表
     */
    private final ArrayList<RefreshData> mRefreshData = new ArrayList<RefreshData>();
    private boolean mAttached;
    private boolean mRefreshIsPosted;
    /**
     * Command for sending an accessibility event.
     */
    private class AccessibilityEventSender implements Runnable {
        public void run() {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
    }

    public HorizontalProgressBarWithAngle(Context context) {
        this(context, null);
    }

    public HorizontalProgressBarWithAngle(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HorizontalProgressBarWithAngle(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HorizontalProgressBarWithAngle(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr);
        mUiThreadId = Thread.currentThread().getId();
        //初始化进度条
        initProgressBar();
        //初始化进度条的值
        initAttributes(context, attrs, defStyleAttr, defStyleRes);

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRect = new Rect(0, 0, mMaxWidth, mMaxHeight);
        mBarDrawable = new GradientDrawable();
        mProgressDrawable = new GradientDrawable();
        mCornerRadius = 8;
    }

    /**
     * 初始化属性值
     *
     * @param context
     * @param attrs
     */
    private void initAttributes(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        //获取通用属性
        mMinWidth = attrs.getAttributeIntValue(ANDROIDXML, "minWidth", mMinWidth);
        mMaxWidth = attrs.getAttributeIntValue(ANDROIDXML, "maxWidth", mMaxWidth);
        mMinHeight = attrs.getAttributeIntValue(ANDROIDXML, "minHeight", mMinHeight);
        mMaxHeight = attrs.getAttributeIntValue(ANDROIDXML, "maxHeight", mMaxHeight);
        setMin(attrs.getAttributeIntValue(ANDROIDXML, "min", mMin));
        setMax(attrs.getAttributeIntValue(ANDROIDXML, "max", mMax));
        setProgress(attrs.getAttributeIntValue(ANDROIDXML, "progress", mProgress));

        //读取自定义属性
        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.HorizontalProgressBarWithAngle);
        //背景颜色
        mBarColor = a.getColor(R.styleable.HorizontalProgressBarWithAngle_hpb_barColor, Color.GRAY);
        //进度条颜色
        mProgressColor = a.getColor(R.styleable.HorizontalProgressBarWithAngle_hpb_progressColor, 0xFF00CDB0);
        a.recycle();

        mNoInvalidate = true;
    }

    /**
     * <p>Return the upper limit of this progress bar's range.</p>
     *
     * @return a positive integer
     *
     * @see #setMax(int)
//     * @see #getProgress()
//     * @see #getSecondaryProgress()
     */
    @ViewDebug.ExportedProperty(category = "progress")
    public synchronized int getMax() {
        return mMax;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mRefreshData != null) {
            synchronized (this) {
                final int count = mRefreshData.size();
                for (int i = 0; i < count; i++) {
                    final RefreshData rd = mRefreshData.get(i);
                    doRefreshProgress(rd.progress, rd.fromUser, true, rd.animate);
                    rd.recycle();
                }
                mRefreshData.clear();
            }
        }
        mAttached = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mRefreshProgressRunnable != null) {
            removeCallbacks(mRefreshProgressRunnable);
            mRefreshIsPosted = false;
        }
        if (mAccessibilityEventSender != null) {
            removeCallbacks(mAccessibilityEventSender);
        }
        // This should come after stopAnimation(), otherwise an invalidate message remains in the
        // queue, which can prevent the entire view hierarchy from being GC'ed during a rotation
        super.onDetachedFromWindow();
        mAttached = false;
    }

    /**
     * <p>Set the lower range of the progress bar to <tt>min</tt>.</p>
     *
     * @param min the lower range of this progress bar
     *
//     * @see #getMin()
//     * @see #setProgress(int)
//     * @see #setSecondaryProgress(int)
     */
//    @android.view.RemotableViewMethod
    public synchronized void setMin(int min) {
        if (mMaxInitialized) {
            if (min > mMax) {
                min = mMax;
            }
        }
        mMinInitialized = true;
        if (mMaxInitialized && min != mMin) {
            mMin = min;
            postInvalidate();

            if (mProgress < min) {
                mProgress = min;
            }
            refreshProgress(mProgress, false, false);
        } else {
            mMin = min;
        }
    }

    /**
     * <p>Set the upper range of the progress bar <tt>max</tt>.</p>
     *
     * @param max the upper range of this progress bar
     *
     * @see #getMax()
//     * @see #setProgress(int)
//     * @see #setSecondaryProgress(int)
     */
//    @android.view.RemotableViewMethod
    public synchronized void setMax(int max) {
        if (mMinInitialized) {
            if (max < mMin) {
                max = mMin;
            }
        }
        mMaxInitialized = true;
        if (mMinInitialized && max != mMax) {
            mMax = max;
            postInvalidate();

            if (mProgress > max) {
                mProgress = max;
            }
            refreshProgress(mProgress, false, false);
        } else {
            mMax = max;
        }
    }

    /**
     * Sets the current progress to the specified value. Does not do anything
     * if the progress bar is in indeterminate mode.
     * <p>
     * This method will immediately update the visual position of the progress
     * indicator. To animate the visual position to the target value, use
     * {@link #setProgress(int, boolean)}}.
     *
     * @param progress the new progress, between 0 and {@link #getMax()}
     *
//     * @see #setIndeterminate(boolean)
//     * @see #isIndeterminate()
//     * @see #getProgress()
//     * @see #incrementProgressBy(int)
     */
//    @android.view.RemotableViewMethod
    public synchronized void setProgress(int progress) {
        setProgressInternal(progress, false, false);
    }

    /**
     * Sets the current progress to the specified value, optionally animating
     * the visual position between the current and target values.
     * <p>
     * Animation does not affect the result of getProgress(), which
     * will return the target value immediately after this method is called.
     *
     * @param progress the new progress value, between 0 and {@link #getMax()}
     * @param animate {@code true} to animate between the current and target
     *                values or {@code false} to not animate
     */
    public void setProgress(int progress, boolean animate) {
        setProgressInternal(progress, false, animate);
    }

    /**
     * 设置进度条背景色
     *
     * @param mBarColor
     */
    public void setBarColor(int mBarColor) {
        this.mBarColor = mBarColor;
        postInvalidate();
    }

    /**
     * 设置进度条颜色
     *
     * @param mProgressColor
     */
    public void setProgressColor(int mProgressColor) {
        this.mProgressColor = mProgressColor;
        postInvalidate();
    }

    //    @android.view.RemotableViewMethod
    synchronized boolean setProgressInternal(int progress, boolean fromUser, boolean animate) {
        if (mIndeterminate) {
            // Not applicable.
            return false;
        }

        progress = MathUtils.clamp(progress, mMin, mMax);

        if (progress == mProgress) {
            // No change from current.
            return false;
        }

        mProgress = progress;
        refreshProgress(mProgress, fromUser, animate);
        return true;
    }

    /**
     * 刷新进度
     *
     * @param progress
     * @param fromUser
     * @param animate
     */
    private synchronized void refreshProgress(int progress, boolean fromUser,
                                              boolean animate) {
        if (mUiThreadId == Thread.currentThread().getId()) {
            doRefreshProgress(progress, fromUser, true, animate);
        } else {
            if (mRefreshProgressRunnable == null) {
                mRefreshProgressRunnable = new RefreshProgressRunnable();
            }

            final RefreshData rd = RefreshData.obtain(progress, fromUser, animate);
            mRefreshData.add(rd);
            if (mAttached && !mRefreshIsPosted) {
                post(mRefreshProgressRunnable);
                mRefreshIsPosted = true;
            }
        }
    }

    /**
     * 进度刷新任务
     */
    private class RefreshProgressRunnable implements Runnable {
        public void run() {
            synchronized (HorizontalProgressBarWithAngle.this) {
                final int count = mRefreshData.size();
                for (int i = 0; i < count; i++) {
                    final RefreshData rd = mRefreshData.get(i);
                    doRefreshProgress(rd.progress, rd.fromUser, true, rd.animate);
                    rd.recycle();
                }
                mRefreshData.clear();
                mRefreshIsPosted = false;
            }
        }
    }

    private static class RefreshData {
        private static final int POOL_MAX = 24;
        private static final Pools.SynchronizedPool<RefreshData> sPool =
                new Pools.SynchronizedPool<RefreshData>(POOL_MAX);

        public int progress;
        public boolean fromUser;
        public boolean animate;

        public static RefreshData obtain(int progress, boolean fromUser, boolean animate) {
            RefreshData rd = sPool.acquire();
            if (rd == null) {
                rd = new RefreshData();
            }
            rd.progress = progress;
            rd.fromUser = fromUser;
            rd.animate = animate;
            return rd;
        }

        public void recycle() {
            sPool.release(this);
        }
    }

    private synchronized void doRefreshProgress(int progress, boolean fromUser,
                                                boolean callBackToApp, boolean animate) {
        int range = mMax - mMin;
        final float scale = range > 0 ? (progress - mMin) / (float) range : 0;

        if (animate) {
            final ObjectAnimator animator;
            animator = ObjectAnimator.ofFloat(this, "visual_progress", mVisualProgress, scale);
            animator.setDuration(PROGRESS_ANIM_DURATION);
            animator.setInterpolator(PROGRESS_ANIM_INTERPOLATOR);
            animator.start();
        } else {
            setVisualProgress(scale);
        }

        if (callBackToApp) {
            onProgressRefresh(scale, fromUser, progress);
        }
    }

    void onProgressRefresh(float scale, boolean fromUser, int progress) {

    }

    /**
     * Schedule a command for sending an accessibility event.
     * </br>
     * Note: A command is used to ensure that accessibility events
     *       are sent at most one in a given time frame to save
     *       system resources while the progress changes quickly.
     */
    private void scheduleAccessibilityEventSender() {
        if (mAccessibilityEventSender == null) {
            mAccessibilityEventSender = new AccessibilityEventSender();
        } else {
            removeCallbacks(mAccessibilityEventSender);
        }
        postDelayed(mAccessibilityEventSender, TIMEOUT_SEND_ACCESSIBILITY_EVENT);
    }

    /**
     * Sets the visual state of a progress indicator.
     *
     * @param progress the visual progress in the range [0...1]
     */
    private void setVisualProgress(float progress) {
        mVisualProgress = progress;

        //刷新进度
        invalidate();
    }

    /**
     * <p>
     * Initialize the progress bar's default values:
     * </p>
     * <ul>
     * <li>progress = 0</li>
     * <li>max = 100</li>
     * <li>animation duration = 4000 ms</li>
     * <li>indeterminate = false</li>
     * <li>behavior = repeat</li>
     * </ul>
     */
    private void initProgressBar() {
        mMin = 0;
        mMax = 100;
        mProgress = 0;
        mSecondaryProgress = 0;
        mMinWidth = 24;
        mMaxWidth = 48;
        mMinHeight = 24;
        mMaxHeight = 48;
        mCornerRadius = 4;
    }

    /**
     * 测量进度条的宽高
     *
     * @param widthMeasureSpec
     * @param heightMeasureSpec
     */
    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int dw = 0;
        int dh = 0;

        dw = Math.max(mMinWidth, mMaxWidth);
        dh = Math.max(mMinHeight, mMaxHeight);

        //增加边距
        dw += getPaddingLeft() + getPaddingRight();
        dh += getPaddingTop() + getPaddingBottom();

        final int measuredWidth = resolveSizeAndState(dw, widthMeasureSpec, 0);
        final int measuredHeight = resolveSizeAndState(dh, heightMeasureSpec, 0);
        Log.d(TAG, "measuredWidth: " + measuredWidth + " measuredHeight:" + measuredHeight);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    /**
     * 绘制进度条
     *
     * @param canvas
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        //绘制背景
        drawBarBackground(canvas);

        //绘制进度条
        drawProgress(canvas);
    }

    /**
     * 绘制进度条背景
     *
     * @param canvas
     */
    private void drawBarBackground(Canvas canvas) {
        float r = mCornerRadius;
        canvas.save();
        //绘制背景色
        Log.d(TAG, "onDraw measuredWidth: " + getMeasuredWidth() + " measuredHeight:" + getMeasuredHeight());
        mRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
        mBarDrawable.setBounds(mRect);
        mBarDrawable.setColor(mBarColor);
        mBarDrawable.setShape(GradientDrawable.RECTANGLE);
        mBarDrawable.setGradientRadius((float)(Math.sqrt(2) * 60));
        setCornerRadii(mBarDrawable, mCornerRadius, mCornerRadius, mCornerRadius, mCornerRadius);
        mBarDrawable.draw(canvas);
        canvas.restore();
    }

    /**
     * 绘制进度条
     *
     * @param canvas
     */
    private void drawProgress(Canvas canvas) {
        if(mProgress <= 0) {
            return;
        }
        //特殊情况(100%进度不需要展示尖角)
        if(mProgress >= mMax) {
            //已经是全部进度
            drawFullProgress(canvas);
            return;
        }
        //计算当前的进度区域
        canvas.save();
        if(null == mProgressRect) {
            mProgressRect = new Rect();
        }
        int width = getMeasuredWidth() / mMax * mProgress;
        mProgressRect.set(0, 0, width, getMeasuredHeight());
//        //裁剪进度条区域
//        canvas.clipRect(mProgressRect);
        mPath.reset();
        canvas.clipPath(mPath); // makes the clip empty
        //mpathd的起始位置
        mPath.moveTo(0, 0);
        //从起始位置划线到(200, 200)坐标
        mPath.lineTo(width, 0);
        mPath.lineTo(width - getMeasuredHeight() / 3, getMeasuredHeight());
        mPath.lineTo(0, getMeasuredHeight());
        //将mpath封闭，也可以写 mpath.lineTo(100, 100);代替
        mPath.close();
        //裁剪一个三角形
        canvas.clipPath(mPath, Region.Op.REPLACE);

        //绘制进度图形
        mProgressDrawable.setBounds(mProgressRect);
        mProgressDrawable.setColor(mProgressColor);
        mProgressDrawable.setShape(GradientDrawable.RECTANGLE);
        mProgressDrawable.setGradientRadius((float)(Math.sqrt(2) * 60));
        setCornerRadii(mProgressDrawable, mCornerRadius, 0, mCornerRadius, 0);
        mProgressDrawable.draw(canvas);
        canvas.restore();
    }

    /**
     * 绘制全部进度
     *
     * @param canvas
     */
    private void drawFullProgress(Canvas canvas) {
        canvas.save();
        //绘制背景色
        if(null == mProgressRect) {
            mProgressRect = new Rect();
        }
        Log.d(TAG, "drawFullProgress measuredWidth: " + getMeasuredWidth() + " measuredHeight:" + getMeasuredHeight());
        mProgressRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
        mProgressDrawable.setBounds(mProgressRect);
        mProgressDrawable.setColor(mProgressColor);
        mProgressDrawable.setShape(GradientDrawable.RECTANGLE);
        mProgressDrawable.setGradientRadius((float)(Math.sqrt(2) * 60));
        setCornerRadii(mProgressDrawable, mCornerRadius, mCornerRadius, mCornerRadius, mCornerRadius);
        mProgressDrawable.draw(canvas);
        canvas.restore();
    }

    /**
     * 设置矩形的边角半径
     *
     * @param drawable
     * @param leftTop
     * @param rightTop
     * @param leftBottom
     * @param rightBottom
     */
    static void setCornerRadii(GradientDrawable drawable, float leftTop,
                               float rightTop, float leftBottom, float rightBottom) {
        drawable.setCornerRadii(new float[] { leftTop, leftTop, rightTop, rightTop,
                rightBottom, rightBottom, leftBottom, leftBottom });
    }

    private final FloatProperty<HorizontalProgressBarWithAngle> VISUAL_PROGRESS =
            new FloatProperty<HorizontalProgressBarWithAngle>("visual_progress") {
                @Override
                public void setValue(HorizontalProgressBarWithAngle object, float value) {
                    object.mVisualProgress = value;
                }

                @Override
                public Float get(HorizontalProgressBarWithAngle object) {
                    return object.mVisualProgress;
                }
            };
}
