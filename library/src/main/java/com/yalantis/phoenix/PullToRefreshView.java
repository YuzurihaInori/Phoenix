package com.yalantis.phoenix;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.NonNull;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;
import android.widget.ImageView;

import com.yalantis.phoenix.refresh_view.BaseRefreshView;
import com.yalantis.phoenix.refresh_view.SunRefreshView;
import com.yalantis.phoenix.util.Utils;

import java.security.InvalidParameterException;

public class PullToRefreshView extends ViewGroup {

    private static final int DRAG_MAX_DISTANCE = 120;
    private static final float DRAG_RATE = .5f;
    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;

    public static final int STYLE_SUN = 0;
    public static final int MAX_OFFSET_ANIMATION_DURATION = 700;

    private static final int INVALID_POINTER = -1;

    private View mTarget;
    private ImageView mRefreshView;
    private Interpolator mDecelerateInterpolator;
    private int mTouchSlop;
    private int mTotalDragDistance;
    private BaseRefreshView mBaseRefreshView;
    private float mCurrentDragPercent;
    private int mCurrentOffsetTop;
    private boolean mRefreshing;
    private int mActivePointerId;
    private boolean mIsBeingDragged;
    private float mInitialMotionY;
    private int mFrom;
    private float mFromDragPercent;
    private boolean mNotify;
    private OnRefreshListener mListener;

    private int mTargetPaddingTop;
    private int mTargetPaddingBottom;
    private int mTargetPaddingRight;
    private int mTargetPaddingLeft;

    public PullToRefreshView(Context context) {
        this(context, null);
    }

    public PullToRefreshView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RefreshView);
        final int type = a.getInteger(R.styleable.RefreshView_type, STYLE_SUN);
        a.recycle();

        //减速插值器
        mDecelerateInterpolator = new DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR);
        //获取临界值
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        //拉动最大距离，dp转px
        mTotalDragDistance = Utils.convertDpToPixel(context, DRAG_MAX_DISTANCE);

        //创建imageview对象
        mRefreshView = new ImageView(context);

        //设置刷新type，其实只有一种type.....
        setRefreshStyle(type);

        //把刷新view加入到本view
        addView(mRefreshView);

        //保证ondraw方法被执行
        setWillNotDraw(false);
        //设置child view按指定顺序绘制？
        ViewCompat.setChildrenDrawingOrderEnabled(this, true);
    }

    public void setRefreshStyle(int type) {
        setRefreshing(false);
        switch (type) {
            case STYLE_SUN:
                mBaseRefreshView = new SunRefreshView(getContext(), this);
                break;
            default:
                throw new InvalidParameterException("Type does not exist");
        }
        //给刷新view添加背景
        mRefreshView.setImageDrawable(mBaseRefreshView);
    }

    /**
     * This method sets padding for the refresh (progress) view.
     */
    public void setRefreshViewPadding(int left, int top, int right, int bottom) {
        mRefreshView.setPadding(left, top, right, bottom);
    }

    public int getTotalDragDistance() {
        return mTotalDragDistance;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        ensureTarget();
        if (mTarget == null)
            return;

        //测量宽-左padding-右padding
        widthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth() - getPaddingRight() - getPaddingLeft(), MeasureSpec.EXACTLY);
        //测量宽-上padding-下padding
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight() - getPaddingTop() - getPaddingBottom(), MeasureSpec.EXACTLY);
        //给child view 设置允许宽高
        mTarget.measure(widthMeasureSpec, heightMeasureSpec);
        //给刷新view 设置允许宽高
        mRefreshView.measure(widthMeasureSpec, heightMeasureSpec);
    }

    private void ensureTarget() {
        if (mTarget != null)
            return;
        if (getChildCount() > 0) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child != mRefreshView) {
                    mTarget = child;
                    //获取child view 底部padding
                    mTargetPaddingBottom = mTarget.getPaddingBottom();
                    //获取child view 左边padding
                    mTargetPaddingLeft = mTarget.getPaddingLeft();
                    //获取child view 右边padding
                    mTargetPaddingRight = mTarget.getPaddingRight();
                    //获取child view 顶边padding
                    mTargetPaddingTop = mTarget.getPaddingTop();
                }
            }
        }
    }

    /**
     * 该方法目的 记录初始点 Y轴坐标 和 是否为拉动状态
     * @param ev
     * @return
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {


        //该viewgroup可用（N） || mTarget可滑动（Y） || 正在刷新（Y） 三者满足其一，不拦截事件
        if (!isEnabled() || canChildScrollUp() || mRefreshing) {
            return false;
        }

        //获取事件action
        final int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                //将mTarget移至y=0
                //mBaseRefreshView（为mRefreshView的drawable）移至y=0
                setTargetOffsetTop(0, true);
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                //设置拉动 为false
                mIsBeingDragged = false;
                final float initialMotionY = getMotionEventY(ev, mActivePointerId);

                //如果触点为无效点，不拦截事件
                if (initialMotionY == -1) {
                    return false;
                }
                //备份初始点的Y值
                mInitialMotionY = initialMotionY;
                break;
            case MotionEvent.ACTION_MOVE:
                //如果触点为无效点，不拦截事件
                if (mActivePointerId == INVALID_POINTER) {
                    return false;
                }
                final float y = getMotionEventY(ev, mActivePointerId);
                if (y == -1) {
                    return false;
                }

                //拉动偏移量 = 移动后的y值-初始按下 y值
                final float yDiff = y - mInitialMotionY;

                //如果偏移量大于临界值 并且 是否拉动为false，则设置实付拉动为true
                if (yDiff > mTouchSlop && !mIsBeingDragged) {
                    mIsBeingDragged = true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                //抬起手指  拉动结束，设置触摸点为无效点
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        return mIsBeingDragged;
    }

    /**
     *  处理拉动后，mBaseRefreshView变化和mTarget的位置变化
     * @param ev
     * @return
     */
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent ev) {

        //拉动为false 不处理事件
        if (!mIsBeingDragged) {
            return super.onTouchEvent(ev);
        }

        //获取事件action
        final int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId);
                if (pointerIndex < 0) {
                    return false;
                }

                final float y = MotionEventCompat.getY(ev, pointerIndex);

                //Y轴拉动偏移量
                final float yDiff = y - mInitialMotionY;
                //Y轴偏移量*拉动系数= 实际mTarget移动距离
                final float scrollTop = yDiff * DRAG_RATE;
                //实际mTarget移动距离/最大可移动距离 = 移动百分比
                mCurrentDragPercent = scrollTop / mTotalDragDistance;

                //小于0 不对事件做处理
                if (mCurrentDragPercent < 0) {
                    return false;
                }
                // 取1和 移动百分比中较小的那个，如果拉动大于最大可以拉动的距离，就取1f，Math.abs()好像并没有什么用
                float boundedDragPercent = Math.min(1f, Math.abs(mCurrentDragPercent));
                //实际拉动距离-总拉动距离 这里的Math.abs()应该也是保险起见而已
                float extraOS = Math.abs(scrollTop) - mTotalDragDistance;
                //最大拉动距离 赋值给新变量，估计要计算啥吧。
                float slingshotDist = mTotalDragDistance;

                //这应该是一个函数。。。。嗯。。。是个函数
                //滑动张力？ =
                float tensionSlingshotPercent = Math.max(0,Math.min(extraOS, slingshotDist * 2) / slingshotDist);
                //张力百分比？ =
                float tensionPercent = (float) ((tensionSlingshotPercent / 4) - Math.pow((tensionSlingshotPercent / 4), 2)) * 2f;
                float extraMove = (slingshotDist) * tensionPercent / 2;
                int targetY = (int) ((slingshotDist * boundedDragPercent) + extraMove);

                //设置mBaseRefreshView的拉动百分比，控制太阳上升
                mBaseRefreshView.setPercent(mCurrentDragPercent, true);
                //这里为啥不用  setTargetOffsetTop((int)Math.min(scrollTop, mTotalDragDistance) - mCurrentOffsetTop, true);这个与下面的区别仅仅只有1点点点而已
                setTargetOffsetTop(targetY - mCurrentOffsetTop, true);
                break;
            }
            case MotionEventCompat.ACTION_POINTER_DOWN:
                final int index = MotionEventCompat.getActionIndex(ev);
                mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActivePointerId == INVALID_POINTER) {
                    return false;
                }
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                //记录拉动实际距离
                final float overScrollTop = (y - mInitialMotionY) * DRAG_RATE;
                //设置拉动 为false
                mIsBeingDragged = false;
                //如果实际拉动距离 大于 最大拉动距离，设置为正在刷新
                if (overScrollTop > mTotalDragDistance) {
                    setRefreshing(true, true);
                } else {
                    //否则设置为没在刷新，并将mtarget设置为初始状态
                    mRefreshing = false;
                    animateOffsetToStartPosition();
                }
                mActivePointerId = INVALID_POINTER;
                return false;
            }
        }

        return true;
    }

    //启动移动mTarget至初始位置的动画
    private void animateOffsetToStartPosition() {
        //top偏移量
        mFrom = mCurrentOffsetTop;
        //当前拖动百分比
        mFromDragPercent = mCurrentDragPercent;

        //动画时长 = 最大时长*当前拖动百分比
        long animationDuration = Math.abs((long) (MAX_OFFSET_ANIMATION_DURATION * mFromDragPercent));

        mAnimateToStartPosition.reset();
        mAnimateToStartPosition.setDuration(animationDuration);
        mAnimateToStartPosition.setInterpolator(mDecelerateInterpolator);
        //mToStartListener在动画结束后，设置mCurrentOffsetTop为初始值，并设置停止刷新
        mAnimateToStartPosition.setAnimationListener(mToStartListener);
        mRefreshView.clearAnimation();
        mRefreshView.startAnimation(mAnimateToStartPosition);
    }

    //启动移动mTarget至当前位置的动画
    private void animateOffsetToCorrectPosition() {
        //mTarget.getTop();
        mFrom = mCurrentOffsetTop;
        //实际移动百分比
        mFromDragPercent = mCurrentDragPercent;

        mAnimateToCorrectPosition.reset();
        mAnimateToCorrectPosition.setDuration(MAX_OFFSET_ANIMATION_DURATION);
        mAnimateToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        mRefreshView.clearAnimation();
        mRefreshView.startAnimation(mAnimateToCorrectPosition);

        if (mRefreshing) {
            mBaseRefreshView.start();
            if (mNotify) {
                if (mListener != null) {
                    mListener.onRefresh();
                }
            }
        } else {
            mBaseRefreshView.stop();
            animateOffsetToStartPosition();
        }
        mCurrentOffsetTop = mTarget.getTop();
        mTarget.setPadding(mTargetPaddingLeft, mTargetPaddingTop, mTargetPaddingRight, mTotalDragDistance);
    }

    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            moveToStart(interpolatedTime);
        }
    };

    private final Animation mAnimateToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int targetTop;
            int endTarget = mTotalDragDistance;
            targetTop = (mFrom + (int) ((endTarget - mFrom) * interpolatedTime));
            int offset = targetTop - mTarget.getTop();

            mCurrentDragPercent = mFromDragPercent - (mFromDragPercent - 1.0f) * interpolatedTime;
            mBaseRefreshView.setPercent(mCurrentDragPercent, false);

            setTargetOffsetTop(offset, false /* requires update */);
        }
    };

    private void moveToStart(float interpolatedTime) {
        int targetTop = mFrom - (int) (mFrom * interpolatedTime);
        float targetPercent = mFromDragPercent * (1.0f - interpolatedTime);
        int offset = targetTop - mTarget.getTop();

        mCurrentDragPercent = targetPercent;
        mBaseRefreshView.setPercent(mCurrentDragPercent, true);
        mTarget.setPadding(mTargetPaddingLeft, mTargetPaddingTop, mTargetPaddingRight, mTargetPaddingBottom + targetTop);
        setTargetOffsetTop(offset, false);
    }

    public void setRefreshing(boolean refreshing) {
        if (mRefreshing != refreshing) {
            setRefreshing(refreshing, false /* notify */);
        }
    }

    private void setRefreshing(boolean refreshing, final boolean notify) {
        if (mRefreshing != refreshing) {
            mNotify = notify;
            ensureTarget();
            mRefreshing = refreshing;
            if (mRefreshing) {
                mBaseRefreshView.setPercent(1f, true);
                animateOffsetToCorrectPosition();
            } else {
                animateOffsetToStartPosition();
            }
        }
    }

    private Animation.AnimationListener mToStartListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            mBaseRefreshView.stop();
            mCurrentOffsetTop = mTarget.getTop();
        }
    };

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == mActivePointerId) {
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mActivePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex);
        }
    }

    private float getMotionEventY(MotionEvent ev, int activePointerId) {
        final int index = MotionEventCompat.findPointerIndex(ev, activePointerId);
        if (index < 0) {
            return -1;
        }
        return MotionEventCompat.getY(ev, index);
    }

    private void setTargetOffsetTop(int offset, boolean requiresUpdate) {
        mTarget.offsetTopAndBottom(offset);
        mBaseRefreshView.offsetTopAndBottom(offset);
        //mTarget 顶部距离该viewgroup的坐标
        mCurrentOffsetTop = mTarget.getTop();
        //是否刷新
        if (requiresUpdate && android.os.Build.VERSION.SDK_INT < 11) {
            invalidate();
        }
    }

    private boolean canChildScrollUp() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (mTarget instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) mTarget;
                return absListView.getChildCount() > 0
                        && (absListView.getFirstVisiblePosition() > 0 || absListView.getChildAt(0)
                        .getTop() < absListView.getPaddingTop());
            } else {
                return mTarget.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(mTarget, -1);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

        ensureTarget();
        if (mTarget == null)
            return;

        //测量得到的高
        int height = getMeasuredHeight();
        //测量得到的宽
        int width = getMeasuredWidth();
        //左padding
        int left = getPaddingLeft();
        //小padding
        int top = getPaddingTop();
        //右padding
        int right = getPaddingRight();
        //底部padding
        int bottom = getPaddingBottom();

        //给child view 制定上，下，左，右，范围
        mTarget.layout(left, top + mCurrentOffsetTop, left + width - right, top + height - bottom + mCurrentOffsetTop);
        //给刷新 view 制定上，下，左，右，范围
        mRefreshView.layout(left, top, left + width - right, top + height - bottom);
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        mListener = listener;
    }

    public interface OnRefreshListener {
        void onRefresh();
    }

}

