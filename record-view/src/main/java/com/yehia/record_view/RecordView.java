package com.yehia.record_view;

import static androidx.core.content.PermissionChecker.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import io.supercharge.shimmerlayout.ShimmerLayout;

/**
 * Edit by Yehia Reda on 05/01/2022.
 */
public class RecordView extends RelativeLayout {

    public static final int DEFAULT_CANCEL_BOUNDS = 8; //8dp
    private ImageView smallBlinkingMic, basketImg;
    private Chronometer counterTime;
    private TextView slideToCancel;
    private ShimmerLayout slideToCancelLayout;
    private ImageView arrow;
    private float initialX, basketInitialY, difX = 0;
    private float cancelBounds = DEFAULT_CANCEL_BOUNDS;
    private long startTime, elapsedTime = 0;
    private final Context context;
    private OnRecordListener recordListener;
    private RecordPermissionHandler recordPermissionHandler;
    private boolean isSwiped, isLessThanSecondAllowed = false;
    private boolean isSoundEnabled = true;
    private int RECORD_START = R.raw.record_start;
    private int RECORD_FINISHED = R.raw.record_finished;
    private int RECORD_ERROR = R.raw.record_error;
    private MediaPlayer player;
    private AnimationHelper animationHelper;
    private boolean isRecordButtonGrowingAnimationEnabled = true;
    private boolean shimmerEffectEnabled = true;
    private long timeLimit = -1;
    private Runnable runnable;
    private Handler handler;
    private RecordButton recordButton;

    private boolean canRecord = true;
    private String recordPath = "";
    public String type = "m4a";
    private Activity activity;

    private AudioRecorder audioRecorder;
    private File recordFile;

    public RecordView(Context context) {
        super(context);
        this.context = context;
        init(context, null, -1, -1);
    }


    public RecordView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        init(context, attrs, -1, -1);
    }

    public RecordView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        init(context, attrs, defStyleAttr, -1);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        View view = View.inflate(context, R.layout.record_view_layout, null);
        addView(view);

        audioRecorder = new AudioRecorder();

        ViewGroup viewGroup = (ViewGroup) view.getParent();
        viewGroup.setClipChildren(false);

        arrow = view.findViewById(R.id.arrow);
        slideToCancel = view.findViewById(R.id.slide_to_cancel);
        smallBlinkingMic = view.findViewById(R.id.glowing_mic);
        counterTime = view.findViewById(R.id.counter_tv);
        basketImg = view.findViewById(R.id.basket_img);
        slideToCancelLayout = view.findViewById(R.id.shimmer_layout);

        hideViews(true);

        if (attrs != null && defStyleAttr == -1 && defStyleRes == -1) {
            TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.RecordView, defStyleAttr, defStyleRes);

            int slideArrowResource = typedArray.getResourceId(R.styleable.RecordView_slide_to_cancel_arrow, -1);
            String slideToCancelText = typedArray.getString(R.styleable.RecordView_slide_to_cancel_text);
            int slideMarginRight = (int) typedArray.getDimension(R.styleable.RecordView_slide_to_cancel_margin_right, 30);
            int counterTimeColor = typedArray.getColor(R.styleable.RecordView_counter_time_color, -1);
            int arrowColor = typedArray.getColor(R.styleable.RecordView_slide_to_cancel_arrow_color, -1);
            int cancelBounds = typedArray.getDimensionPixelSize(R.styleable.RecordView_slide_to_cancel_bounds, -1);

            if (cancelBounds != -1)
                setCancelBounds(cancelBounds, false);//don't convert it to pixels since it's already in pixels

            if (slideArrowResource != -1) {
                Drawable slideArrow = AppCompatResources.getDrawable(getContext(), slideArrowResource);
                arrow.setImageDrawable(slideArrow);
            }

            if (slideToCancelText != null) slideToCancel.setText(slideToCancelText);

            if (counterTimeColor != -1) setCounterTimeColor(counterTimeColor);

            if (arrowColor != -1) setSlideToCancelArrowColor(arrowColor);

            setMarginRight(slideMarginRight);
            typedArray.recycle();
        }

        animationHelper = new AnimationHelper(context, basketImg, smallBlinkingMic, isRecordButtonGrowingAnimationEnabled);
    }

    private boolean isTimeLimitValid() {
        return timeLimit > 0;
    }

    private void initTimeLimitHandler() {
        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                if (recordListener != null && !isSwiped) {
                    stopRecording(false);

                    recordListener.onFinish(elapsedTime, false, recordFile.getPath());
                }

                removeTimeLimitCallbacks();

                animationHelper.setStartRecorded(false);

                if (!isSwiped) playSound(RECORD_FINISHED);

                if (recordButton != null) {
                    resetRecord(recordButton);
                }
                isSwiped = true;
            }
        };
    }

    private void hideViews(boolean hideSmallMic) {
        slideToCancelLayout.setVisibility(GONE);
        counterTime.setVisibility(GONE);
        if (hideSmallMic) smallBlinkingMic.setVisibility(GONE);
    }

    private void showViews() {
        slideToCancelLayout.setVisibility(VISIBLE);
        smallBlinkingMic.setVisibility(VISIBLE);
        counterTime.setVisibility(VISIBLE);
    }

    private boolean isLessThanOneSecond(long time) {
        return time <= 1000;
    }

    private void playSound(int soundRes) {
        if (isSoundEnabled) {
            if (soundRes == 0) return;

            try {
                player = new MediaPlayer();
                AssetFileDescriptor afd = context.getResources().openRawResourceFd(soundRes);
                if (afd == null) return;
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                player.prepare();
                player.start();
                player.setOnCompletionListener(MediaPlayer::release);
                player.setLooping(false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    protected void onActionDown(RecordButton recordBtn, MotionEvent motionEvent) {
        boolean audio = EX.checkPermission(Manifest.permission.RECORD_AUDIO, getContext());
        boolean readStorage = EX.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE, getContext());
        boolean writeStorage = EX.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, getContext());

        if (!audio || (Build.VERSION.SDK_INT < 33 && (!readStorage || !writeStorage))) {
            onPermission();
            return;
        }

        if (!isRecordPermissionGranted()) {
            return;
        }

        this.recordButton = recordBtn;

        if (recordListener != null) {
            startRecord();
            recordListener.onStart();
        }

        if (isTimeLimitValid()) {
            removeTimeLimitCallbacks();
            handler.postDelayed(runnable, timeLimit);
        }

        animationHelper.setStartRecorded(true);
        animationHelper.resetBasketAnimation();
        animationHelper.resetSmallMic();

        if (isRecordButtonGrowingAnimationEnabled) {
            recordBtn.startScale();
        }

        if (shimmerEffectEnabled) {
            slideToCancelLayout.startShimmerAnimation();
        }

        initialX = recordBtn.getX();

        basketInitialY = basketImg.getY() + 90;

        playSound(RECORD_START);

        showViews();

        animationHelper.animateSmallMicAlpha();
        counterTime.setBase(SystemClock.elapsedRealtime());
        startTime = System.currentTimeMillis();
        counterTime.start();
        isSwiped = false;
    }

    public void onPermission() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{Manifest.permission.RECORD_AUDIO};
        } else {
            perms = new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        }

        ActivityCompat.requestPermissions(activity, perms, 100);
    }

    private void startRecord() {
        recordFile = new File(context.getFilesDir(), UUID.randomUUID().toString() + "." + type);
        try {
            audioRecorder.start(recordFile.getPath(), context);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        stopRecording(true);
    }

    private long getRecordDuration() {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Uri uri = Uri.parse(recordPath);
        retriever.setDataSource(context, uri);

        String time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        return Long.parseLong(time);
    }

    protected void onActionMove(RecordButton recordBtn, MotionEvent motionEvent) {
        if (!canRecord) {
            return;
        }

        long time = System.currentTimeMillis() - startTime;

        if (!isSwiped) {
            //Swipe To Cancel
            if (slideToCancelLayout.getX() != 0 && slideToCancelLayout.getX() <= counterTime.getRight() + cancelBounds) {

                //if the time was less than one second then do not start basket animation
                if (isLessThanOneSecond(time)) {
                    hideViews(true);
                    animationHelper.clearAlphaAnimation(false);

                    animationHelper.onAnimationEnd();
                } else {
                    hideViews(false);
                    animationHelper.animateBasket(basketInitialY);
                }

                animationHelper.moveRecordButtonAndSlideToCancelBack(recordBtn, slideToCancelLayout, initialX, difX);

                counterTime.stop();
                if (shimmerEffectEnabled) {
                    slideToCancelLayout.stopShimmerAnimation();
                }

                isSwiped = true;
                animationHelper.setStartRecorded(false);

                if (recordListener != null) {
                    stopRecording();
                    recordListener.onCancel();
                }

                if (isTimeLimitValid()) {
                    removeTimeLimitCallbacks();
                }
            } else {
                //if statement is to Prevent Swiping out of bounds
                if (motionEvent.getRawX() < initialX) {
                    recordBtn.animate().x(motionEvent.getRawX()).setDuration(0).start();
                    if (difX == 0) difX = (initialX - slideToCancelLayout.getX());

                    slideToCancelLayout.animate().x(motionEvent.getRawX() - difX).setDuration(0).start();
                }
            }
        }
    }

    protected void onActionUp(RecordButton recordBtn) {
        boolean audio = EX.checkPermission(Manifest.permission.RECORD_AUDIO, getContext());
        boolean readStorage = EX.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE, getContext());
        boolean writeStorage = EX.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, getContext());

        if (!audio || (Build.VERSION.SDK_INT < 33 && (!readStorage || !writeStorage))) {
            return;
        }
        if (!canRecord) {
            return;
        }
        elapsedTime = System.currentTimeMillis() - startTime;

        if (!isLessThanSecondAllowed && isLessThanOneSecond(elapsedTime) && !isSwiped) {
            if (recordListener != null) {
                stopRecording(true);
                recordListener.onLessThanSecond();
            }

            removeTimeLimitCallbacks();
            animationHelper.setStartRecorded(false);

            playSound(RECORD_ERROR);
        } else {
            if (recordListener != null && !isSwiped) {
                stopRecording(false);

                recordListener.onFinish(elapsedTime, false, recordFile.getPath());
            }

            removeTimeLimitCallbacks();

            animationHelper.setStartRecorded(false);

            if (!isSwiped) playSound(RECORD_FINISHED);
        }

        resetRecord(recordBtn);
    }

    @SuppressLint("Recycle")
    private String getRealPathFromUri(Context context, Uri contentUri) {
        Cursor cursor = null;
        try {
            String[] projd = {MediaStore.Images.Media.DATA};
            cursor = context.getContentResolver().query(contentUri, projd, null, null, null);
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            return cursor.getString(columnIndex);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            return "";
        }
    }

    private void resetRecord(RecordButton recordBtn) {
        //if user has swiped then do not hide SmallMic since it will be hidden after swipe Animation
        hideViews(!isSwiped);

        if (!isSwiped) animationHelper.clearAlphaAnimation(true);

        animationHelper.moveRecordButtonAndSlideToCancelBack(recordBtn, slideToCancelLayout, initialX, difX);
        counterTime.stop();
        if (shimmerEffectEnabled) {
            slideToCancelLayout.stopShimmerAnimation();
        }
    }

    private void removeTimeLimitCallbacks() {
        if (isTimeLimitValid()) {
            handler.removeCallbacks(runnable);
        }
    }

    private boolean isRecordPermissionGranted() {
        if (recordPermissionHandler == null) {
            canRecord = true;
        } else {
            canRecord = recordPermissionHandler.isPermissionGranted();
        }

        return canRecord;
    }

    private void setMarginRight(int marginRight) {
        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) slideToCancelLayout.getLayoutParams();
        layoutParams.rightMargin = (int) DpUtil.toPixel(marginRight, context);

        slideToCancelLayout.setLayoutParams(layoutParams);
    }

    public void setOnRecordListener(final Activity activity, OnRecordListener recrodListener) {
        this.activity = activity;
        setRecordPermissionHandler(() -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return true;
            }
            boolean recordPermissionAvailable = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PERMISSION_GRANTED;
            if (recordPermissionAvailable) {
                return true;
            }

            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, 0);
            return false;
        });
        this.recordListener = recrodListener;
    }

    public void setRecordPermissionHandler(RecordPermissionHandler recordPermissionHandler) {
        this.recordPermissionHandler = recordPermissionHandler;
    }

    public void setOnBasketAnimationEndListener(OnBasketAnimationEnd onBasketAnimationEndListener) {
        animationHelper.setOnBasketAnimationEndListener(onBasketAnimationEndListener);
    }

    public void setSoundEnabled(boolean isEnabled) {
        isSoundEnabled = isEnabled;
    }

    public void setLessThanSecondAllowed(boolean isAllowed) {
        isLessThanSecondAllowed = isAllowed;
    }

    public void setSlideToCancelText(String text) {
        slideToCancel.setText(text);
    }

    public void setSlideToCancelTextColor(int color) {
        slideToCancel.setTextColor(color);
    }

    public void setSmallMicColor(int color) {
        smallBlinkingMic.setColorFilter(color);
    }

    public void setSmallMicIcon(int icon) {
        smallBlinkingMic.setImageResource(icon);
    }

    public void setSlideMarginRight(int marginRight) {
        setMarginRight(marginRight);
    }

    public void setCustomSounds(int startSound, int finishedSound, int errorSound) {
        //0 means do not play sound
        RECORD_START = startSound;
        RECORD_FINISHED = finishedSound;
        RECORD_ERROR = errorSound;
    }

    public float getCancelBounds() {
        return cancelBounds;
    }

    public void setCancelBounds(float cancelBounds) {
        setCancelBounds(cancelBounds, true);
    }

    //set Chronometer color
    public void setCounterTimeColor(int color) {
        counterTime.setTextColor(color);
    }

    public void setSlideToCancelArrowColor(int color) {
        arrow.setColorFilter(color);
    }


    private void setCancelBounds(float cancelBounds, boolean convertDpToPixel) {
        float bounds = convertDpToPixel ? DpUtil.toPixel(cancelBounds, context) : cancelBounds;
        this.cancelBounds = bounds;
    }

    public boolean isRecordButtonGrowingAnimationEnabled() {
        return isRecordButtonGrowingAnimationEnabled;
    }

    public void setRecordButtonGrowingAnimationEnabled(boolean recordButtonGrowingAnimationEnabled) {
        isRecordButtonGrowingAnimationEnabled = recordButtonGrowingAnimationEnabled;
        animationHelper.setRecordButtonGrowingAnimationEnabled(recordButtonGrowingAnimationEnabled);
    }

    public boolean isShimmerEffectEnabled() {
        return shimmerEffectEnabled;
    }

    public void setShimmerEffectEnabled(boolean shimmerEffectEnabled) {
        this.shimmerEffectEnabled = shimmerEffectEnabled;
    }

    public long getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(long timeLimit) {
        this.timeLimit = timeLimit;

        if (handler != null && runnable != null) {
            removeTimeLimitCallbacks();
        }
        initTimeLimitHandler();
    }

    public void setTrashIconColor(int color) {
        animationHelper.setTrashIconColor(color);
    }

    private void stopRecording(boolean deleteFile) {
        audioRecorder.stop();
        if (recordFile != null && deleteFile) {
            recordFile.delete();
        }
    }
}

