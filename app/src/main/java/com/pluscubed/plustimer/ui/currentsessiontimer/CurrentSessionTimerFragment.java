package com.pluscubed.plustimer.ui.currentsessiontimer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PictureDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Property;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.couchbase.lite.CouchbaseLiteException;
import com.pluscubed.plustimer.R;
import com.pluscubed.plustimer.model.PuzzleType;
import com.pluscubed.plustimer.model.ScrambleAndSvg;
import com.pluscubed.plustimer.model.Session;
import com.pluscubed.plustimer.model.Solve;
import com.pluscubed.plustimer.ui.RecyclerViewUpdate;
import com.pluscubed.plustimer.utils.PrefUtils;
import com.pluscubed.plustimer.utils.Utils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import rx.Completable;
import rx.SingleSubscriber;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;

/**
 * TimerFragment
 */

public class CurrentSessionTimerFragment extends Fragment implements CurrentSessionTimerView {

    public static final String TAG = "CURRENT_SESSION_TIMER_FRAGMENT";
    private static final long HOLD_TIME = 550000000L;
    private static final int REFRESH_RATE = 15;
    private static final String STATE_IMAGE_DISPLAYED = "scramble_image_displayed_boolean";
    private static final String STATE_START_TIME = "start_time_long";
    private static final String STATE_RUNNING = "running_boolean";
    private static final String STATE_INSPECTING = "inspecting_boolean";
    private static final String STATE_INSPECTION_START_TIME = "inspection_start_time_long";

    private CurrentSessionTimerPresenter mPresenter;

    //Preferences
    private boolean mHoldToStartEnabled;
    private boolean mInspectionEnabled;
    private boolean mTwoRowTimeEnabled;
    private PrefUtils.TimerUpdate mUpdateTimePref;
    private boolean mMillisecondsEnabled;
    private boolean mMonospaceScrambleFontEnabled;
    private int mTimerTextSize;
    private int mScrambleTextSize;
    private boolean mKeepScreenOn;
    private boolean mSignEnabled;
    //Retained Fragment
    private CurrentSessionTimerRetainedFragment mRetainedFragment;
    //Views
    private TextView mTimerText;
    private TextView mTimerText2;
    private TextView mScrambleText;
    private View mScrambleTextShadow;
    private RecyclerView mTimeBarRecycler;
    private ImageView mScrambleImage;
    private TextView mStatsSolvesText;
    private TextView mStatsText;
    private LinearLayout mLastBarLinearLayout;
    private Button mLastDnfButton;
    private Button mLastPlusTwoButton;
    private Button mLastDeleteButton;
    private FrameLayout mDynamicStatusBarFrame;
    private TextView mDynamicStatusBarText;
    //Handler
    private Handler mUiHandler;
    //Dynamic status variables
    private boolean mDynamicStatusBarVisible;
    private boolean mHoldTiming;
    private long mHoldTimerStartTimestamp;
    private boolean mInspecting;
    private long mInspectionStartTimestamp;
    private long mInspectionStopTimestamp;
    private long mTimingStartTimestamp;
    private boolean mFromSavedInstanceState;
    private boolean mTiming;


    private boolean mScrambleImageDisplay;
    private boolean mLateStartPenalty;
    private boolean mBldMode;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (mUpdateTimePref != PrefUtils.TimerUpdate.OFF) {
                if (!mBldMode) {
                    setTimerText(Utils.timeStringsFromNsSplitByDecimal(
                            System.nanoTime() - mTimingStartTimestamp,
                            mMillisecondsEnabled));
                } else {
                    setTimerText(Utils.timeStringsFromNsSplitByDecimal(
                            System.nanoTime() - mInspectionStartTimestamp,
                            mMillisecondsEnabled));
                }
                setTimerTextToPrefSize();
                mUiHandler.postDelayed(this, REFRESH_RATE);
            } else {
                setTimerText(new String[]{getString(R.string.timing), ""});
                mTimerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 100);
            }
        }
    };
    private AnimatorSet mLastBarAnimationSet;
    private ValueAnimator mScrambleAnimator;
    private ObjectAnimator mScrambleElevationAnimator;
    //Runnables
    private final Runnable holdTimerRunnable = new Runnable() {
        @Override
        public void run() {
            setTextColor(Color.GREEN);
            setTimerTextToPrefSize();
            if (!mInspecting) {
                playExitAnimations();
                getActivityCallback().lockDrawerAndViewPager(true);
            }
        }
    };
    private final Runnable inspectionRunnable = new Runnable() {
        @Override
        public void run() {
            String[] array = Utils.timeStringsFromNsSplitByDecimal
                    (16000000000L - (System.nanoTime() -
                            mInspectionStartTimestamp), mMillisecondsEnabled);
            array[1] = "";

            if (15000000000L - (System.nanoTime() -
                    mInspectionStartTimestamp) > 0) {
                //If inspection proceeding normally
                setTimerText(array);
            } else {
                if (17000000000L - (System.nanoTime() -
                        mInspectionStartTimestamp) > 0) {
                    //If late start
                    mLateStartPenalty = true;
                    setTimerText(new String[]{"+2", ""});
                } else {
                    //If past 17 seconds which means DNF
                    stopHoldTimer();
                    stopInspection();

                    playEnterAnimations();

                    Solve s = null;
                    try {
                        s = PuzzleType.getCurrent().getCurrentSession(getActivity()).newSolve(getActivity());
                        s.setScramble(getActivity(), mRetainedFragment.getCurrentScrambleAndSvg().getScramble());
                        s.setRawTime(getActivity(), 0);
                        s.setPenalty(getActivity(), Solve.PENALTY_DNF);
                    } catch (IOException | CouchbaseLiteException e) {
                        e.printStackTrace();
                    }

                    //Add the solve to the current session with the current
                    // scramble/scramble image and DNF
                    mPresenter.onTimingFinished(s);

                    resetTimer();
                    invalidateTimerText();


                    if (mRetainedFragment.isScrambling()) {
                        setScrambleText(getString(R.string.scrambling));
                    }
                    mRetainedFragment.postSetScrambleViewsToCurrent();
                    return;
                }
            }

            //If proceeding normally or +2
            setTimerTextToPrefSize();
            mUiHandler.postDelayed(this, REFRESH_RATE);
        }
    };

    //TODO
    public void onNewSession() {
        updateStatsAndTimerText(null, RecyclerViewUpdate.DATA_RESET);
        TimeBarRecyclerAdapter adapter = (TimeBarRecyclerAdapter) mTimeBarRecycler.getAdapter();
        adapter.notifyChange(null, RecyclerViewUpdate.REMOVE_ALL);
    }

    //TODO
    //Generate string with specified current averages and mean of current
    // session
    private String buildStatsWithAveragesOf(Context context,
                                            Integer... currentAverageSpecs) {
        Arrays.sort(currentAverageSpecs, Collections.reverseOrder());
        String s = "";

        /*PuzzleType.getCurrent().getCurrentSession()
                .flatMap(new Func1<Session, Single<?>>() {
                    @Override
                    public Single<?> call(Session session) {
                        return null;
                    }
                })
        for (int i : currentAverageSpecs) {
            if (PuzzleType.getCurrentId().getCurrentSession().getNumberOfSolves() >= i) {
                s += String.format(context.getString(R.string.cao), i) + ": " + PuzzleType.getCurrentId().getCurrentSession()
                        .getStringCurrentAverageOf(i, mMillisecondsEnabled) + "\n";
            }
        }
        if (PuzzleType.getCurrentId().getCurrentSession().getNumberOfSolves() > 0) {
            s += context.getString(R.string.mean) + PuzzleType.getCurrentId().getCurrentSession().getStringMean(mMillisecondsEnabled);
        }*/
        return s;
    }

    /**
     * Set timer textviews using an array. Hides/shows lower textview
     * depending on preferences
     * and whether the second array item is blank.
     *
     * @param array An array of 2 strings
     */
    void setTimerText(String[] array) {
        if (mTwoRowTimeEnabled) {
            mTimerText.setText(array[0]);
            mTimerText2.setText(array[1]);
            if (array[1].equals("") || (mTiming && mUpdateTimePref != PrefUtils.TimerUpdate.ON)) {
                mTimerText2.setVisibility(View.GONE);
            } else {
                mTimerText2.setVisibility(View.VISIBLE);
            }
        } else {
            mTimerText2.setVisibility(View.GONE);
            mTimerText.setText(array[0]);
            if (!array[1].equals("") && !(mTiming && (mUpdateTimePref != PrefUtils.TimerUpdate
                    .ON))) {
                mTimerText.append("." + array[1]);
            }
        }
    }

    void setScrambleText(String text) {
        mScrambleText.setText(text);
        invalidateScrambleShadow(false);
    }

    private void invalidateScrambleShadow(final boolean overrideShowShadow) {
        Runnable animate = () -> {
            if (mScrambleElevationAnimator != null) {
                mScrambleElevationAnimator.cancel();
            }
            Property<View, Float> property;
            View view;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                property = View.TRANSLATION_Z;
                view = mScrambleText;
            } else {
                property = View.ALPHA;
                view = mScrambleTextShadow;
            }
            mScrambleElevationAnimator = ObjectAnimator.ofFloat(view,
                    property, getScrambleTextElevationOrShadowAlpha(overrideShowShadow));
            mScrambleElevationAnimator.setDuration(150);
            mScrambleElevationAnimator.start();
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mScrambleText.postOnAnimation(animate);
        } else {
            mScrambleText.post(animate);
        }
    }

    //Taken from http://stackoverflow.com/questions/3619693
    private int getRelativeTop(View view) {
        if (view.getParent() == view.getRootView())
            return view.getTop();
        else
            return view.getTop() + getRelativeTop((View) view.getParent());
    }

    private float getScrambleTextElevationOrShadowAlpha(boolean override) {
        boolean overlap = getRelativeTop(mScrambleText) + Utils.getTextViewHeight(mScrambleText)
                > getRelativeTop(mTimerText);
        float rootFrameTranslationY = getActivityCallback() != null ?
                getActivityCallback().getContentFrameLayout().getTranslationY() : 0f;
        boolean shadowShown = overlap || rootFrameTranslationY != 0 || override;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return shadowShown ? Utils.convertDpToPx(getActivity(), 2) : 0f;
        } else {
            return shadowShown ? 1f : 0f;
        }
    }

    //Set scramble text and scramble image to current ones
    public void setScrambleTextAndImageToCurrent() {
        ScrambleAndSvg currentScrambleAndSvg = mRetainedFragment.getCurrentScrambleAndSvg();
        if (currentScrambleAndSvg != null) {
            SVG svg = null;
            try {
                svg = SVG.getFromString(currentScrambleAndSvg.getSvg());
            } catch (SVGParseException e) {
                e.printStackTrace();
            }
            Drawable drawable = null;
            if (svg != null) {
                drawable = new PictureDrawable(svg.renderToPicture());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                mScrambleImage.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
            mScrambleImage.setImageDrawable(drawable);

            setScrambleText(Utils.getUiScramble(
                    currentScrambleAndSvg.getScramble(), mSignEnabled,
                    PuzzleType.getCurrentId()));
        } else {
            mRetainedFragment.generateNextScramble();
            mRetainedFragment.postSetScrambleViewsToCurrent();
        }
    }

    void onPuzzleTypeChanged() {
        updateStatsAndTimerText(null, RecyclerViewUpdate.DATA_RESET);

        //Update RecyclerView
        TimeBarRecyclerAdapter adapter = (TimeBarRecyclerAdapter) mTimeBarRecycler.getAdapter();
        adapter.notifyChange(null, RecyclerViewUpdate.DATA_RESET);
    }

    @Override
    public void updateStatsAndTimerText(Solve solve, RecyclerViewUpdate mode) {
        //Update stats
        PuzzleType.getCurrent().getCurrentSessionDeferred(getActivity())
                .map(Session::getNumberOfSolves)
                .subscribe(new SingleSubscriber<Integer>() {
                    @Override
                    public void onSuccess(Integer numberOfSolves) {
                        mStatsSolvesText.setText(getString(R.string.solves_colon) + numberOfSolves);
                    }

                    @Override
                    public void onError(Throwable error) {

                    }
                });


        mStatsText.setText(buildStatsWithAveragesOf(getActivity(), 5, 12, 100));


        if (!mTiming && !mInspecting && solve != null) {
            if (mode == RecyclerViewUpdate.INSERT) {
                setTimerTextFromSolve(solve);
            } else {
                invalidateTimerText();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            //Toggle image button
            case R.id.menu_activity_current_session_scramble_image_menuitem:
                toggleScrambleImage();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mPresenter = new CurrentSessionTimerPresenter();
        mPresenter.attachView(this);

        //TODO
        /*PuzzleType.getCurrentId().(puzzleTypeObserver);
        PuzzleType.getCurrentId().getCurrentSession()
                .registerObserver(sessionSolvesListener);*/

        //Set up UIHandler
        mUiHandler = new Handler(Looper.getMainLooper());

        PreferenceManager.setDefaultValues(getActivity(), R.xml.preferences, false);
        initSharedPrefs();

        if (savedInstanceState != null) {
            mScrambleImageDisplay = savedInstanceState.getBoolean
                    (STATE_IMAGE_DISPLAYED);
            mTimingStartTimestamp = savedInstanceState.getLong
                    (STATE_START_TIME);
            mTiming = savedInstanceState.getBoolean(STATE_RUNNING);
            mInspecting = savedInstanceState.getBoolean(STATE_INSPECTING);
            mInspectionStartTimestamp = savedInstanceState.getLong
                    (STATE_INSPECTION_START_TIME);
            mFromSavedInstanceState = true;
        } else {
            mFromSavedInstanceState = false;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_current_session_timer,
                container, false);

        mTimerText = (TextView) v.findViewById(R.id.fragment_current_session_timer_time_textview);
        mTimerText2 = (TextView) v.findViewById(R.id.fragment_current_session_timer_timeSecondary_textview);
        mScrambleText = (TextView) v.findViewById(R.id.fragment_current_session_timer_scramble_textview);
        mScrambleTextShadow = v.findViewById(R.id.fragment_current_session_timer_scramble_shadow);
        mScrambleImage = (ImageView) v.findViewById(R.id.fragment_current_session_timer_scramble_imageview);
        mTimeBarRecycler = (RecyclerView) v.findViewById(R.id.fragment_current_session_timer_timebar_recycler);

        mStatsText = (TextView) v.findViewById(R.id.fragment_current_session_timer_stats_textview);
        mStatsSolvesText = (TextView) v.findViewById(R.id.fragment_current_session_timer_stats_solves_number_textview);

        mLastBarLinearLayout = (LinearLayout) v.findViewById(R.id.fragment_current_session_timer_last_linearlayout);
        mLastDnfButton = (Button) v.findViewById(R.id.fragment_current_session_timer_last_dnf_button);
        mLastPlusTwoButton = (Button) v.findViewById(R.id.fragment_current_session_timer_last_plustwo_button);
        mLastDeleteButton = (Button) v.findViewById(R.id.fragment_current_session_timer_last_delete_button);

        mLastDnfButton.setOnClickListener(view -> {
            PuzzleType.getCurrent().getCurrentSessionDeferred(getActivity()).toObservable()
                    .flatMap(session -> session.getLastSolve(getActivity()))
                    .flatMap(solve -> solve.setPenaltyDeferred(getActivity(), Solve.PENALTY_DNF).toObservable()).toCompletable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Completable.CompletableSubscriber() {
                        @Override
                        public void onCompleted() {
                            playLastBarExitAnimation();
                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onSubscribe(Subscription d) {

                        }
                    });
        });

        mLastPlusTwoButton.setOnClickListener(view -> {
            PuzzleType.getCurrent().getCurrentSessionDeferred(getActivity()).toObservable()
                    .flatMap(session -> session.getLastSolve(getActivity()))
                    .flatMap(solve -> solve.setPenaltyDeferred(getActivity(), Solve.PENALTY_PLUSTWO).toObservable()).toCompletable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Completable.CompletableSubscriber() {
                        @Override
                        public void onCompleted() {
                            playLastBarExitAnimation();
                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onSubscribe(Subscription d) {

                        }
                    });
        });

        mLastDeleteButton.setOnClickListener(v1 -> {
            PuzzleType.getCurrent().getCurrentSessionDeferred(getActivity())
                    .flatMapObservable(session ->
                            session.deleteSolveDeferred(getActivity(), PuzzleType.getCurrentId())
                                    .toObservable())
                    .toCompletable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::playLastBarExitAnimation);
        });

        LinearLayoutManager timeBarLayoutManager = new LinearLayoutManager(getActivity(),
                LinearLayoutManager.HORIZONTAL, false) {
            //TODO: Smooth scroll so empty space for insertion opens up
            //Take a look at onLayoutChildren so insertion animation is nice.
        };
        timeBarLayoutManager.setStackFromEnd(true);
        mTimeBarRecycler.setLayoutManager(timeBarLayoutManager);
        mTimeBarRecycler.setHasFixedSize(true);
        mTimeBarRecycler.setAdapter(new TimeBarRecyclerAdapter(this));

        mDynamicStatusBarFrame = (FrameLayout) v.findViewById(R.id.fragment_current_session_timer_dynamic_status_frame);
        mDynamicStatusBarText = (TextView) v.findViewById(R.id.fragment_current_session_timer_dynamic_status_text);

        mRetainedFragment = getActivityCallback().getTimerRetainedFragment();
        mRetainedFragment.setTargetFragment(this, 0);

        //When the root view is touched...
        v.setOnTouchListener((v1, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    return onTimerTouchDown();
                }
                case MotionEvent.ACTION_UP: {
                    onTimerTouchUp();
                    return false;
                }
                default:
                    return false;
            }
        });

        if (!mFromSavedInstanceState) {
            //When the fragment is initializing, disable action bar and
            // generate a scramble.
            mRetainedFragment.resetScramblerThread();
            enableMenuItems(false);
            setScrambleText(getString(R.string.scrambling));
        } else {
            if (mInspecting) {
                mUiHandler.post(inspectionRunnable);
            }
            if (mTiming) {
                mUiHandler.post(timerRunnable);
            }
            if (mTiming || mInspecting) {
                enableMenuItems(false);
            } else if (!mRetainedFragment.isScrambling()) {
                enableMenuItems(true);
            }
            if (mInspecting || mTiming || !mRetainedFragment.isScrambling()) {
                // If timer is timing/inspecting, then update text/image to
                // current. If timer is
                // not timing/inspecting and not scrambling,
                // then update scramble views to current.
                setScrambleTextAndImageToCurrent();
            } else {
                setScrambleText(getString(R.string.scrambling));
            }
        }

        //If the scramble image is currently displayed and it is not scrambling,
        // then make sure it is set to visible; otherwise, set to gone.
        showScrambleImage(mScrambleImageDisplay && !mRetainedFragment
                .isScrambling());

        mScrambleImage.setOnClickListener(v1 -> toggleScrambleImage());

        //TODO
        //onPuzzleTypeChanged();

        return v;
    }

    public CurrentSessionTimerPresenter getPresenter() {
        return mPresenter;
    }

    @Override
    public void setInitialized() {
        mBldMode = PuzzleType.getCurrent().isBld();
        if (!mFromSavedInstanceState) {
            mRetainedFragment.generateNextScramble();
            mRetainedFragment.postSetScrambleViewsToCurrent();
        }
    }

    @Override
    public Activity getContextCompat() {
        return getActivity();
    }

    @Override
    public void onResume() {
        super.onResume();
        initSharedPrefs();
        if (mKeepScreenOn) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (mMonospaceScrambleFontEnabled) {
            mScrambleText.setTypeface(Typeface.MONOSPACE);
        } else {
            mScrambleText.setTypeface(Typeface.DEFAULT);
        }

        mScrambleText.setTextSize(mScrambleTextSize);

        mPresenter.onResume();

        //TODO
        //When Settings change
        //onPuzzleTypeChanged();
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //PuzzleType.getCurrentId().saveCurrentSession(getActivity());
        stopHoldTimer();

        //mPresenter.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_IMAGE_DISPLAYED, mScrambleImageDisplay);
        outState.putLong(STATE_START_TIME, mTimingStartTimestamp);
        outState.putBoolean(STATE_RUNNING, mTiming);
        outState.putBoolean(STATE_INSPECTING, mInspecting);
        outState.putLong(STATE_INSPECTION_START_TIME,
                mInspectionStartTimestamp);
    }

    public void scrollRecyclerView(int position) {
        mTimeBarRecycler.smoothScrollToPosition(position);
    }

    public TimeBarRecyclerAdapter getTimeBarAdapter() {
        return (TimeBarRecyclerAdapter) mTimeBarRecycler.getAdapter();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        //When destroyed, stop timer runnable
        mUiHandler.removeCallbacksAndMessages(null);
        mRetainedFragment.setTargetFragment(null, 0);


        mPresenter.onDestroy();
        mPresenter.detachView();

        //TODO
        /*PuzzleType.getCurrentId().getCurrentSession()
                .unregisterObserver(sessionSolvesListener);
        PuzzleType.unregisterObserver(puzzleTypeObserver);*/
    }

    void initSharedPrefs() {
        mInspectionEnabled = PrefUtils.isInspectionEnabled(getActivity());
        mHoldToStartEnabled = PrefUtils.isHoldToStartEnabled(getActivity());
        mTwoRowTimeEnabled = getResources().getConfiguration().orientation == 1
                        && PrefUtils.isTwoRowTimeEnabled(getActivity());
        mUpdateTimePref = PrefUtils.getTimerUpdateMode(getActivity());
        mMillisecondsEnabled = PrefUtils.isDisplayMillisecondsEnabled(getActivity());
        mTimerTextSize = PrefUtils.getTimerTextSize(getActivity());
        mScrambleTextSize = PrefUtils.getScrambleTextSize(getActivity());
        mKeepScreenOn = PrefUtils.isKeepScreenOnEnabled(getActivity());
        mSignEnabled = PrefUtils.isSignEnabled(getActivity());
        mMonospaceScrambleFontEnabled = PrefUtils.isMonospaceScrambleFontEnabled(getActivity());
    }

    void setTimerTextToPrefSize() {
        if (mTimerText.getText() != getString(R.string.ready)) {
            if (mTimerText != null && mTimerText2 != null) {
                if (mTwoRowTimeEnabled) {
                    mTimerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTimerTextSize);
                } else {
                    mTimerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTimerTextSize * 0.7F);
                }
                mTimerText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTimerTextSize / 2);
            }
        }
    }

    void showScrambleImage(boolean enable) {
        if (enable) {
            mScrambleImage.setVisibility(View.VISIBLE);
        } else {
            mScrambleImage.setVisibility(View.GONE);
        }
        mScrambleImageDisplay = enable;
    }

    private void setTextColorPrimary() {
        int[] textColorAttr = new int[]{android.R.attr.textColor};
        TypedArray a = getActivity().obtainStyledAttributes(new TypedValue().data, textColorAttr);
        int color = a.getColor(0, -1);
        a.recycle();
        setTextColor(color);
    }

    void setTextColor(int color) {
        mTimerText.setTextColor(color);
        mTimerText2.setTextColor(color);
    }

    /**
     * Sets the timer text to last solve's time; if there are no solves,
     * set to ready. Updates the timer text's size.
     */
    void invalidateTimerText() {

        PuzzleType.getCurrent().getCurrentSessionDeferred(getActivity())
                .flatMapObservable(session -> session.getLastSolve(getActivity()))
                .observeOn(AndroidSchedulers.mainThread())
                .defaultIfEmpty(null)
                .subscribe(new Subscriber<Solve>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onNext(Solve solve) {
                        if (solve != null) {
                            setTimerTextFromSolve(solve);
                        } else {
                            setTimerText(new String[]{getString(R.string.ready), ""});
                            mTimerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 100);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });

        setTimerTextToPrefSize();
    }

    void setTimerTextFromSolve(Solve solve) {
        setTimerText(solve.getTimeStringArray(mMillisecondsEnabled));
        setTimerTextToPrefSize();
    }

    public void enableMenuItems(boolean enable) {
        ActivityCallback callback = getActivityCallback();
        callback.enableMenuItems(enable);
    }

    private ActivityCallback getActivityCallback() {
        ActivityCallback callback;
        try {
            callback = (ActivityCallback) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException(
                    getActivity().toString() + " must implement " +
                            "ActivityCallback");
        }
        return callback;
    }

    void toggleScrambleImage() {
        if (mScrambleImageDisplay) {
            mScrambleImageDisplay = false;
            mScrambleImage.setVisibility(View.GONE);
        } else {
            if (!mRetainedFragment.isScrambling()) {
                mScrambleImageDisplay = true;
                mScrambleImage.setVisibility(View.VISIBLE);
                mScrambleImage.setOnClickListener(v -> {
                    mScrambleImageDisplay = false;
                    mScrambleImage.setVisibility(View.GONE);
                });
            }
        }
    }

    /**
     * @return whether
     * {@link CurrentSessionTimerFragment#onTimerTouchUp()}
     * will be triggered when touch is released
     */
    private synchronized boolean onTimerTouchDown() {
        boolean scrambling = mRetainedFragment.isScrambling();

        //Currently Timing: User stopping timer
        if (mTiming) {
            Solve s = null;
            try {
                s = PuzzleType.getCurrent().getCurrentSession(getActivity()).newSolve(getActivity());
                s.setScramble(getActivity(), mRetainedFragment.getCurrentScrambleAndSvg().getScramble());
                s.setRawTime(getActivity(), System.nanoTime() - mTimingStartTimestamp);

                //TODO: Blind mode
                /*if (!mBldMode) {
                } else {
                    s = new BldSolve(mRetainedFragment.getCurrentScrambleAndSvg().getScramble(),
                            System.nanoTime() - mTimingStartTimestamp,
                            mInspectionStopTimestamp - mInspectionStartTimestamp);
                }*/

                if (mInspectionEnabled && mLateStartPenalty) {
                    s.setPenalty(getActivity(), Solve.PENALTY_PLUSTWO);
                }

            } catch (IOException | CouchbaseLiteException e) {
                e.printStackTrace();
            }


            //Add the solve to the current session with the
            // current scramble/scramble image and time
            mPresenter.onTimingFinished(s);


            playLastBarEnterAnimation();
            playEnterAnimations();
            getActivityCallback().lockDrawerAndViewPager(false);

            resetTimer();

            setTimerTextFromSolve(s);


            if (scrambling) {
                setScrambleText(getString(R.string.scrambling));
            }

            mRetainedFragment.postSetScrambleViewsToCurrent();
            return false;
        }

        if (mBldMode) {
            return onTimerBldTouchDown(scrambling);
        }

        if (mHoldToStartEnabled &&
                ((!mInspectionEnabled && !scrambling) || mInspecting)) {
            //If hold to start is on, start the hold timer
            //If inspection is enabled, only start hold timer when inspecting
            //Go to section 2
            startHoldTimer();
            return true;
        } else if (mInspecting) {
            //If inspecting and hold to start is off, start regular timer
            //Go to section 3
            setTextColor(Color.GREEN);
            return true;
        }

        //If inspection is on and haven't started yet: section 1
        //If hold to start and inspection are both off: section 3
        if (!scrambling) {
            setTextColor(Color.GREEN);
            return true;
        }
        return false;
    }

    private synchronized boolean onTimerBldTouchDown(boolean scrambling) {
        //If inspecting: section 3
        //If not inspecting yet and not scrambling: section 1
        setTextColor(Color.GREEN);
        return mInspecting || !scrambling;
    }


    private synchronized void onTimerTouchUp() {
        if ((mInspectionEnabled || mBldMode) && !mInspecting) {
            //Section 1
            //If inspection is on (or BLD) and not inspecting
            startInspection();
            playExitAnimations();
        } else if (!mBldMode && mHoldToStartEnabled) {
            //Section 2
            //Hold to start is on (may be inspecting)
            if (mHoldTiming &&
                    (System.nanoTime() - mHoldTimerStartTimestamp >=
                            HOLD_TIME)) {
                //User held long enough for timer to turn
                // green and lifted: start timing
                stopInspection();
                stopHoldTimer();
                startTiming();
                if (!mBldMode && !mInspectionEnabled) {
                    //If hold timer was started but not in
                    // inspection, generate next scramble
                    mRetainedFragment.generateNextScramble();
                }
            } else {
                //User started hold timer but lifted before
                // the timer is green: stop hold timer
                stopHoldTimer();
            }
        } else {
            //Section 3
            //Hold to start is off, start timing
            if (mInspecting) {
                stopInspection();
            } else {
                playExitAnimations();
            }
            startTiming();
            if (!mBldMode) {
                mRetainedFragment.generateNextScramble();
            }
        }
    }

    private void playExitAnimations() {
        Utils.lockOrientation(getActivity());
        playScrambleExitAnimation();
        playStatsExitAnimation();
        getActivityCallback().playToolbarExitAnimation();
    }

    public void playEnterAnimations() {
        Utils.unlockOrientation(getActivity());
        playScrambleEnterAnimation();
        playStatsEnterAnimation();
        getActivityCallback().playToolbarEnterAnimation();
    }

    private void playDynamicStatusBarEnterAnimation() {
        mDynamicStatusBarVisible = true;
        ObjectAnimator enter = ObjectAnimator.ofFloat(mDynamicStatusBarFrame, View.TRANSLATION_Y,
                0f);
        enter.setDuration(125);
        enter.setInterpolator(new DecelerateInterpolator());
        AnimatorSet dynamicStatusBarAnimatorSet = new AnimatorSet();
        dynamicStatusBarAnimatorSet.play(enter);
        dynamicStatusBarAnimatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mDynamicStatusBarVisible) {
                    mTimeBarRecycler.setVisibility(View.INVISIBLE);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        dynamicStatusBarAnimatorSet.start();
    }

    private void playDynamicStatusBarExitAnimation() {
        mDynamicStatusBarVisible = false;
        ObjectAnimator exit = ObjectAnimator.ofFloat(mDynamicStatusBarFrame, View.TRANSLATION_Y,
                mDynamicStatusBarFrame.getHeight());
        exit.setDuration(125);
        exit.setInterpolator(new AccelerateInterpolator());
        AnimatorSet dynamicStatusBarAnimatorSet = new AnimatorSet();
        dynamicStatusBarAnimatorSet.play(exit);
        dynamicStatusBarAnimatorSet.start();
        mTimeBarRecycler.setVisibility(View.VISIBLE);
    }

    private void playLastBarEnterAnimation() {
        if (mLastBarAnimationSet != null) {
            mLastBarAnimationSet.cancel();
        }
        mLastDeleteButton.setEnabled(true);
        mLastDnfButton.setEnabled(true);
        mLastPlusTwoButton.setEnabled(true);
        ObjectAnimator enter = ObjectAnimator.ofFloat(mLastBarLinearLayout, View.TRANSLATION_Y,
                -mLastBarLinearLayout.getHeight());
        ObjectAnimator exit = ObjectAnimator.ofFloat(mLastBarLinearLayout, View.TRANSLATION_Y, 0f);
        enter.setDuration(125);
        exit.setDuration(125);
        exit.setStartDelay(1500);
        enter.setInterpolator(new DecelerateInterpolator());
        exit.setInterpolator(new AccelerateInterpolator());
        mLastBarAnimationSet = new AnimatorSet();
        mLastBarAnimationSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mLastBarLinearLayout.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mLastBarLinearLayout.getTranslationY() == 0f) {
                    mLastBarLinearLayout.setVisibility(View.GONE);
                }
            }
        });
        mLastBarAnimationSet.playSequentially(enter, exit);
        mLastBarAnimationSet.start();
    }

    void playLastBarExitAnimation() {
        mLastDeleteButton.setEnabled(false);
        mLastDnfButton.setEnabled(false);
        mLastPlusTwoButton.setEnabled(false);
        ObjectAnimator exit = ObjectAnimator.ofFloat(mLastBarLinearLayout, View.TRANSLATION_Y, 0f);
        exit.setDuration(125);
        exit.setInterpolator(new AccelerateInterpolator());
        AnimatorSet lastBarAnimationSet = new AnimatorSet();
        lastBarAnimationSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mLastBarLinearLayout.getTranslationY() == 0f) {
                    mLastBarLinearLayout.setVisibility(View.GONE);
                }
            }
        });
        lastBarAnimationSet.play(exit);
        lastBarAnimationSet.start();
    }

    void playScrambleExitAnimation() {
        if (mScrambleAnimator != null) {
            mScrambleAnimator.cancel();
        }
        mScrambleAnimator = ObjectAnimator.ofFloat(mScrambleText, View.TRANSLATION_Y,
                -mScrambleText.getHeight());
        mScrambleAnimator.setDuration(300);
        mScrambleAnimator.setInterpolator(new AccelerateInterpolator());
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
            mScrambleAnimator.addUpdateListener(animation -> mScrambleTextShadow.setTranslationY((int) (float) animation.getAnimatedValue()));
        }
        mScrambleAnimator.start();
        invalidateScrambleShadow(true);
    }

    void playScrambleEnterAnimation() {
        if (mScrambleAnimator != null) {
            mScrambleAnimator.cancel();
        }
        mScrambleAnimator = ObjectAnimator.ofFloat(mScrambleText, View.TRANSLATION_Y, 0f);
        mScrambleAnimator.setDuration(300);
        mScrambleAnimator.setInterpolator(new DecelerateInterpolator());
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
            mScrambleAnimator.addUpdateListener(animation -> mScrambleTextShadow.setTranslationY((int) (float) animation.getAnimatedValue()));
        }
        mScrambleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                invalidateScrambleShadow(false);
            }
        });
        mScrambleAnimator.start();
    }

    void playStatsExitAnimation() {
        ObjectAnimator exit = ObjectAnimator.ofFloat(mStatsText, View.ALPHA, 0f);
        exit.setDuration(300);
        ObjectAnimator exit3 = ObjectAnimator.ofFloat(mStatsSolvesText, View.ALPHA, 0f);
        exit3.setDuration(300);
        AnimatorSet scrambleAnimatorSet = new AnimatorSet();
        scrambleAnimatorSet.play(exit).with(exit3);
        scrambleAnimatorSet.start();
    }

    void playStatsEnterAnimation() {
        ObjectAnimator enter = ObjectAnimator.ofFloat(mStatsText, View.ALPHA, 1f);
        enter.setDuration(300);
        ObjectAnimator enter3 = ObjectAnimator.ofFloat(mStatsSolvesText, View.ALPHA, 1f);
        enter3.setDuration(300);
        AnimatorSet scrambleAnimatorSet = new AnimatorSet();
        scrambleAnimatorSet.play(enter).with(enter3);
        scrambleAnimatorSet.start();
    }

    void startHoldTimer() {
        playLastBarExitAnimation();
        mHoldTiming = true;
        mHoldTimerStartTimestamp = System.nanoTime();
        setTextColor(Color.RED);
        mUiHandler.postDelayed(holdTimerRunnable, 550);
    }

    public void stopHoldTimer() {
        mHoldTiming = false;
        mHoldTimerStartTimestamp = 0;
        mUiHandler.removeCallbacks(holdTimerRunnable);
        setTextColorPrimary();
    }

    /**
     * Start inspection; Start Generating Next Scramble
     */
    void startInspection() {
        playLastBarExitAnimation();
        playDynamicStatusBarEnterAnimation();
        mDynamicStatusBarText.setText(R.string.inspecting);
        mInspectionStartTimestamp = System.nanoTime();
        mInspecting = true;
        if (mBldMode) {
            mUiHandler.post(timerRunnable);
        } else {
            mUiHandler.post(inspectionRunnable);
        }
        mRetainedFragment.generateNextScramble();
        enableMenuItems(false);
        showScrambleImage(false);
        getActivityCallback().lockDrawerAndViewPager(true);
        setTextColorPrimary();
    }

    void stopInspection() {
        mInspectionStopTimestamp = System.nanoTime();
        mInspecting = false;
        mUiHandler.removeCallbacks(inspectionRunnable);
    }

    /**
     * Start timing; does not start generating next scramble
     */
    void startTiming() {
        playLastBarExitAnimation();
        playDynamicStatusBarEnterAnimation();
        mTimingStartTimestamp = System.nanoTime();
        mInspecting = false;
        mTiming = true;
        if (!mBldMode) mUiHandler.post(timerRunnable);
        enableMenuItems(false);
        showScrambleImage(false);
        mDynamicStatusBarText.setText(R.string.timing);
        getActivityCallback().lockDrawerAndViewPager(true);
        setTextColorPrimary();
    }

    void resetGenerateScramble() {
        mRetainedFragment.resetScramblerThread();
        mRetainedFragment.generateNextScramble();
        mRetainedFragment.postSetScrambleViewsToCurrent();
    }

    void resetTimer() {
        mUiHandler.removeCallbacksAndMessages(null);
        mHoldTiming = false;
        mTiming = false;
        mLateStartPenalty = false;
        mHoldTimerStartTimestamp = 0;
        mInspectionStartTimestamp = 0;
        mTimingStartTimestamp = 0;
        mInspecting = false;
        setTextColorPrimary();
        playDynamicStatusBarExitAnimation();
    }

    public Handler getUiHandler() {
        return mUiHandler;
    }


    public interface ActivityCallback {
        void lockDrawerAndViewPager(boolean lock);

        void playToolbarEnterAnimation();

        void playToolbarExitAnimation();

        CurrentSessionTimerRetainedFragment getTimerRetainedFragment();

        void enableMenuItems(boolean enable);

        FrameLayout getContentFrameLayout();
    }


}