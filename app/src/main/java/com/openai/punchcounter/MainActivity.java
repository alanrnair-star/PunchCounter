package com.openai.punchcounter;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PunchCounter";
    private static final int CAMERA_PERMISSION = 1001;

    /*
     * Punch rules:
     * - Valid shoulder/elbow/wrist landmarks are mandatory.
     * - Arm must first be bent/retracted.
     * - Then elbow must extend rapidly.
     * - Wrist must also move, preventing clothing/background jitter.
     * - Each arm is tracked independently.
     */

    private static final float MIN_CONFIDENCE = 0.50f;

    // Karate-speed trajectory detector
    private static final double MIN_OUTWARD_STEP = 0.025;
    private static final double MIN_RETURN_STEP = -0.010;

    // Normalised against shoulder width
    private static final double MIN_PEAK_REACH = 1.15;
    private static final double MIN_PUNCH_TRAVEL = 0.16;

    // Elbow is supporting evidence, not the main trigger
    private static final double MIN_PEAK_ANGLE = 135.0;

    // Arm can re-arm without requiring a very deep guard position
    private static final double REARM_REACH = 1.08;
    private static final double REARM_ANGLE = 132.0;

    // Per-arm only — no global left/right blocking
    private static final long ARM_COOLDOWN_MS = 140L;

    private static final int ARM_READY = 0;
    private static final int ARM_EXTENDING = 1;
    private static final int ARM_RETURNING = 2;

    private PreviewView previewView;
    private TextView punchCountText;
    private TextView rateText;
    private TextView timerText;
    private TextView statusText;
    private Button resetButton;
    private Button recordButton;
    private View controlsPanel;

    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;

    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private OverlayEffect videoOverlayEffect;

    private volatile boolean isRecording = false;
    private volatile int punchCount = 0;
    private volatile long recordingStartMs = 0L;

    private final ArmState leftArm = new ArmState();
    private final ArmState rightArm = new ArmState();

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                updateStats();
                uiHandler.postDelayed(this, 100);
            }
        }
    };

    private static class ArmState {
        int phase = ARM_READY;

        double previousReach = Double.NaN;
        double previousAngle = Double.NaN;

        double startReach = 0.0;
        double peakReach = 0.0;
        double peakAngle = 0.0;

        long lastPunchMs = 0L;

        void reset() {
            phase = ARM_READY;

            previousReach = Double.NaN;
            previousAngle = Double.NaN;

            startReach = 0.0;
            peakReach = 0.0;
            peakAngle = 0.0;

            lastPunchMs = 0L;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        punchCountText = findViewById(R.id.punchCountText);
        rateText = findViewById(R.id.rateText);
        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        resetButton = findViewById(R.id.resetButton);
        recordButton = findViewById(R.id.recordButton);
        controlsPanel = findViewById(R.id.controlsPanel);

        /*
         * Proper Samsung / Android navigation-bar handling.
         * This replaces fixed 90dp / 180dp guesses.
         */
        final int baseBottomPadding = dp(20);
        ViewCompat.setOnApplyWindowInsetsListener(controlsPanel, (view, windowInsets) -> {
            Insets nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
            view.setPadding(
                    dp(20),
                    dp(12),
                    dp(20),
                    baseBottomPadding + nav.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(controlsPanel);

        cameraExecutor = Executors.newSingleThreadExecutor();

        PoseDetectorOptions options =
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .build();

        poseDetector = PoseDetection.getClient(options);

        resetButton.setOnClickListener(v -> {
            if (!isRecording) {
                resetSession();
            }
        });

        recordButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        resetSession();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION
            );
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder().build();
                videoCapture = VideoCapture.withOutput(recorder);

                ImageAnalysis analysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                /*
                 * This effect is applied to VIDEO_CAPTURE itself.
                 * Therefore the count/timer/date are written into
                 * the recorded frames, not merely placed over PreviewView.
                 */
                videoOverlayEffect =
                        new OverlayEffect(
                                CameraEffect.VIDEO_CAPTURE,
                                0,
                                new Handler(Looper.getMainLooper()),
                                throwable -> Log.e(TAG, "OverlayEffect error", throwable)
                        );

                videoOverlayEffect.setOnDrawListener(frame -> {
                    if (!isRecording) {
                        return true;
                    }

                    Canvas canvas = frame.getOverlayCanvas();
                    Rect crop = frame.getCropRect();

                    float scale = Math.max(1f, crop.width() / 1080f);

                    Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
                    background.setColor(Color.argb(185, 0, 0, 0));

                    Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    titlePaint.setColor(Color.WHITE);
                    titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    titlePaint.setTextSize(42f * scale);

                    Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    valuePaint.setColor(Color.WHITE);
                    valuePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    valuePaint.setTextSize(64f * scale);

                    Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    smallPaint.setColor(Color.WHITE);
                    smallPaint.setTextSize(30f * scale);

                    float boxWidth = 390f * scale;
                    float boxHeight = 220f * scale;
                    float margin = 30f * scale;

                    float left = crop.right - boxWidth - margin;
                    float top = crop.top + margin;

                    canvas.drawRoundRect(
                            left,
                            top,
                            left + boxWidth,
                            top + boxHeight,
                            24f * scale,
                            24f * scale,
                            background
                    );

                    canvas.drawText(
                            "PUNCH COUNT",
                            left + 24f * scale,
                            top + 48f * scale,
                            titlePaint
                    );

                    canvas.drawText(
                            String.valueOf(punchCount),
                            left + 24f * scale,
                            top + 120f * scale,
                            valuePaint
                    );

                    long elapsed =
                            Math.max(0L,
                                    SystemClock.elapsedRealtime() - recordingStartMs);

                    canvas.drawText(
                            "TIME  " + formatElapsed(elapsed),
                            left + 24f * scale,
                            top + 165f * scale,
                            smallPaint
                    );

                    String stamp =
                            new SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    Locale.getDefault())
                                    .format(new Date());

                    canvas.drawText(
                            stamp,
                            left + 24f * scale,
                            top + 205f * scale,
                            smallPaint
                    );

                    return true;
                });

                UseCaseGroup group =
                        new UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .addUseCase(analysis)
                                .addUseCase(videoCapture)
                                .addEffect(videoOverlayEffect)
                                .build();

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, group);

                runOnUiThread(() ->
                        statusText.setText("Ready • press START RECORDING"));

            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
                runOnUiThread(() ->
                        Toast.makeText(
                                this,
                                "Camera error: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image =
                InputImage.fromMediaImage(
                        imageProxy.getImage(),
                        imageProxy.getImageInfo().getRotationDegrees());

        poseDetector.process(image)
                .addOnSuccessListener(this::processPose)
                .addOnFailureListener(e ->
                        Log.e(TAG, "Pose detection failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void processPose(Pose pose) {

        /*
         * Absolutely no counting before START RECORDING.
         */
        if (!isRecording) {
            return;
        }

        PoseLandmark ls =
                pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark le =
                pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW);
        PoseLandmark lw =
                pose.getPoseLandmark(PoseLandmark.LEFT_WRIST);

        PoseLandmark rs =
                pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark re =
                pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW);
        PoseLandmark rw =
                pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST);

        /*
         * Body scale prevents tiny background movement or camera noise
         * from being treated as a large motion.
         */
        double shoulderWidth = 0.0;
        if (valid(ls) && valid(rs)) {
            shoulderWidth = distance(ls, rs);
        }

        if (shoulderWidth < 35.0) {
            runOnUiThread(() ->
                    statusText.setText(
                            "Keep shoulders, elbows and wrists visible"));
            return;
        }

        boolean left = processArm(ls, le, lw, shoulderWidth, leftArm);
        boolean right = processArm(rs, re, rw, shoulderWidth, rightArm);

        if (left || right) {
            int current = ++punchCount;

            runOnUiThread(() -> {
                punchCountText.setText(String.valueOf(current));
                statusText.setText("Punch detected");
                updateStats();
            });
        }
    }

    private boolean processArm(
            PoseLandmark shoulder,
            PoseLandmark elbow,
            PoseLandmark wrist,
            double bodyScale,
            ArmState state) {

        if (!valid(shoulder) || !valid(elbow) || !valid(wrist)) {
            return false;
        }

        double angle = jointAngle(shoulder, elbow, wrist);

        /*
         * Distance from shoulder to wrist, normalised by shoulder width.
         * This makes detection less dependent on distance from camera.
         */
        double reach = distance(shoulder, wrist) / bodyScale;

        if (Double.isNaN(state.previousReach)) {
            state.previousReach = reach;
            state.previousAngle = angle;
            return false;
        }

        double deltaReach = reach - state.previousReach;

        long now = SystemClock.elapsedRealtime();
        boolean counted = false;

        switch (state.phase) {

            case ARM_READY:

                /*
                 * Start a punch only when the wrist is clearly travelling
                 * away from the shoulder.
                 */
                if (deltaReach >= MIN_OUTWARD_STEP) {
                    state.phase = ARM_EXTENDING;

                    state.startReach = state.previousReach;
                    state.peakReach = reach;
                    state.peakAngle = angle;
                }

                break;

            case ARM_EXTENDING:

                /*
                 * Track the furthest extension.
                 */
                if (reach > state.peakReach) {
                    state.peakReach = reach;
                    state.peakAngle = angle;
                }

                /*
                 * Count at the point where outward travel reverses.
                 * This represents peak extension.
                 */
                boolean directionReversed =
                        deltaReach <= MIN_RETURN_STEP;

                if (directionReversed) {

                    double totalTravel =
                            state.peakReach - state.startReach;

                    boolean validPunch =
                            state.peakReach >= MIN_PEAK_REACH
                            && totalTravel >= MIN_PUNCH_TRAVEL
                            && state.peakAngle >= MIN_PEAK_ANGLE
                            && now - state.lastPunchMs
                                >= ARM_COOLDOWN_MS;

                    if (validPunch) {
                        state.lastPunchMs = now;
                        state.phase = ARM_RETURNING;
                        counted = true;
                    } else {
                        /*
                         * Movement was too small to be a punch.
                         * Return to READY without counting.
                         */
                        state.phase = ARM_READY;
                    }
                }

                break;

            case ARM_RETURNING:

                /*
                 * The same arm cannot count again until it genuinely
                 * returns toward the body.
                 *
                 * Either wrist reach OR elbow bend can re-arm it.
                 */
                boolean returned =
                        reach <= REARM_REACH
                        || angle <= REARM_ANGLE
                        || reach <= state.peakReach - 0.15;

                if (returned) {
                    state.phase = ARM_READY;

                    state.startReach = reach;
                    state.peakReach = reach;
                    state.peakAngle = angle;
                }

                break;
        }

        state.previousReach = reach;
        state.previousAngle = angle;

        return counted;
    }

    private boolean valid(PoseLandmark landmark) {
        return landmark != null
                && landmark.getInFrameLikelihood() >= MIN_CONFIDENCE;
    }

    private static double distance(
            PoseLandmark a,
            PoseLandmark b) {

        double dx =
                a.getPosition().x - b.getPosition().x;
        double dy =
                a.getPosition().y - b.getPosition().y;

        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double jointAngle(
            PoseLandmark shoulder,
            PoseLandmark elbow,
            PoseLandmark wrist) {

        double ax =
                shoulder.getPosition().x
                        - elbow.getPosition().x;
        double ay =
                shoulder.getPosition().y
                        - elbow.getPosition().y;

        double bx =
                wrist.getPosition().x
                        - elbow.getPosition().x;
        double by =
                wrist.getPosition().y
                        - elbow.getPosition().y;

        double dot = ax * bx + ay * by;

        double magA =
                Math.sqrt(ax * ax + ay * ay);
        double magB =
                Math.sqrt(bx * bx + by * by);

        if (magA < 1e-6 || magB < 1e-6) {
            return 0.0;
        }

        double cosine = dot / (magA * magB);
        cosine = Math.max(-1.0, Math.min(1.0, cosine));

        return Math.toDegrees(Math.acos(cosine));
    }

    private void startRecording() {

        if (videoCapture == null) {
            Toast.makeText(
                    this,
                    "Camera is not ready yet",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        resetSession();

        punchCount = 0;
        recordingStartMs = SystemClock.elapsedRealtime();
        isRecording = true;

        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "PunchCounter_"
                        + System.currentTimeMillis()
                        + ".mp4");
        values.put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4");

        MediaStoreOutputOptions output =
                new MediaStoreOutputOptions.Builder(
                        getContentResolver(),
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        .setContentValues(values)
                        .build();

        PendingRecording pending =
                videoCapture.getOutput()
                        .prepareRecording(this, output);

        activeRecording =
                pending.start(
                        ContextCompat.getMainExecutor(this),
                        event -> {
                            if (event
                                    instanceof VideoRecordEvent.Finalize) {

                                VideoRecordEvent.Finalize finalizeEvent =
                                        (VideoRecordEvent.Finalize) event;

                                if (finalizeEvent.hasError()) {
                                    Toast.makeText(
                                            this,
                                            "Recording error: "
                                                + finalizeEvent.getError(),
                                            Toast.LENGTH_LONG
                                    ).show();
                                } else {
                                    Toast.makeText(
                                            this,
                                            "Video saved with punch count and timer",
                                            Toast.LENGTH_LONG
                                    ).show();
                                }
                            }
                        });

        recordButton.setText("STOP RECORDING");
        resetButton.setEnabled(false);
        statusText.setText("RECORDING • punch when ready");

        uiHandler.removeCallbacks(timerRunnable);
        uiHandler.post(timerRunnable);
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        uiHandler.removeCallbacks(timerRunnable);

        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }

        recordButton.setText("START RECORDING");
        resetButton.setEnabled(true);
        statusText.setText(
                "Stopped • final count " + punchCount);

        updateStats();
    }

    private void resetSession() {
        if (isRecording) {
            return;
        }

        punchCount = 0;
        recordingStartMs = 0L;

        leftArm.reset();
        rightArm.reset();

        if (punchCountText != null) {
            punchCountText.setText("0");
        }

        if (rateText != null) {
            rateText.setText("0 punches/min");
        }

        if (timerText != null) {
            timerText.setText("00:00");
        }

        if (statusText != null) {
            statusText.setText("Ready • press START RECORDING");
        }
    }

    private void updateStats() {
        if (!isRecording || recordingStartMs <= 0L) {
            punchCountText.setText(String.valueOf(punchCount));
            return;
        }

        long elapsed =
                Math.max(
                        1L,
                        SystemClock.elapsedRealtime()
                                - recordingStartMs);

        int rate =
                (int) Math.round(
                        punchCount * 60000.0 / elapsed);

        punchCountText.setText(
                String.valueOf(punchCount));

        rateText.setText(
                rate + " punches/min");

        timerText.setText(
                formatElapsed(elapsed));
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        return String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources()
                        .getDisplayMetrics()
                        .density);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults);

        if (requestCode == CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0]
                    == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else if (requestCode == CAMERA_PERMISSION) {

            Toast.makeText(
                    this,
                    "Camera permission is required",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        uiHandler.removeCallbacksAndMessages(null);

        if (activeRecording != null) {
            activeRecording.close();
            activeRecording = null;
        }

        if (videoOverlayEffect != null) {
            videoOverlayEffect.close();
        }

        if (poseDetector != null) {
            poseDetector.close();
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
