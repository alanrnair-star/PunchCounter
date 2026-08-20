package com.openai.punchcounter;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextView countText;
    private TextView rateText;
    private TextView statusText;
    private Button recordButton;

    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;

    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private int punchCount = 0;
    private long sessionStartMs = 0L;
    private long lastPunchMs = 0L;

    // Each arm must retract/bend before another extension can count.
    private boolean leftArmed = true;
    private boolean rightArmed = true;

    // Tunable prototype thresholds.
    private static final double EXTENDED_ANGLE = 154.0;
    private static final double REARM_ANGLE = 128.0;
    private static final long GLOBAL_DEBOUNCE_MS = 115L;
    private static final float MIN_LIKELIHOOD = 0.55f;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean camera = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                if (camera) startCamera();
                else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        countText = findViewById(R.id.countText);
        rateText = findViewById(R.id.rateText);
        statusText = findViewById(R.id.statusText);
        recordButton = findViewById(R.id.recordButton);
        Button resetButton = findViewById(R.id.resetButton);

        cameraExecutor = Executors.newSingleThreadExecutor();
        poseDetector = PoseDetection.getClient(
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .build());

        resetButton.setOnClickListener(v -> resetCounter());
        recordButton.setOnClickListener(v -> toggleRecording());

        if (hasCameraPermission()) startCamera();
        else permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                bindUseCases(cameraProvider);
            } catch (Exception e) {
                statusText.setText("Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(
                        Arrays.asList(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        try {
            cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis, videoCapture);
        } catch (Exception backError) {
            selector = CameraSelector.DEFAULT_BACK_CAMERA;
            cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis, videoCapture);
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (imageProxy.getImage() == null || !analyzing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        poseDetector.process(image)
                .addOnSuccessListener(this::evaluatePose)
                .addOnFailureListener(e -> runOnUiThread(() -> statusText.setText("Tracking...")))
                .addOnCompleteListener(task -> {
                    analyzing.set(false);
                    imageProxy.close();
                });
    }

    private void evaluatePose(Pose pose) {
        PoseLandmark ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark le = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW);
        PoseLandmark lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST);
        PoseLandmark rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark re = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW);
        PoseLandmark rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST);

        if (!good(ls, le, lw) && !good(rs, re, rw)) {
            runOnUiThread(() -> statusText.setText("Keep shoulders, elbows and wrists visible"));
            return;
        }

        boolean counted = false;
        if (good(ls, le, lw)) {
            double a = angle(ls, le, lw);
            if (a < REARM_ANGLE) leftArmed = true;
            if (leftArmed && a > EXTENDED_ANGLE && mayCountNow()) {
                leftArmed = false;
                counted = true;
            }
        }

        if (!counted && good(rs, re, rw)) {
            double a = angle(rs, re, rw);
            if (a < REARM_ANGLE) rightArmed = true;
            if (rightArmed && a > EXTENDED_ANGLE && mayCountNow()) {
                rightArmed = false;
                counted = true;
            }
        }

        if (counted) {
            lastPunchMs = SystemClock.elapsedRealtime();
            punchCount++;
            if (sessionStartMs == 0L) sessionStartMs = lastPunchMs;
            updateCounterUi();
        } else {
            runOnUiThread(() -> statusText.setText("Tracking pose • punch when ready"));
        }
    }

    private boolean mayCountNow() {
        long now = SystemClock.elapsedRealtime();
        return now - lastPunchMs >= GLOBAL_DEBOUNCE_MS;
    }

    private boolean good(PoseLandmark... landmarks) {
        for (PoseLandmark lm : landmarks) {
            if (lm == null || lm.getInFrameLikelihood() < MIN_LIKELIHOOD) return false;
        }
        return true;
    }

    private double angle(PoseLandmark a, PoseLandmark b, PoseLandmark c) {
        double abx = a.getPosition().x - b.getPosition().x;
        double aby = a.getPosition().y - b.getPosition().y;
        double cbx = c.getPosition().x - b.getPosition().x;
        double cby = c.getPosition().y - b.getPosition().y;
        double dot = abx * cbx + aby * cby;
        double mag1 = Math.sqrt(abx * abx + aby * aby);
        double mag2 = Math.sqrt(cbx * cbx + cby * cby);
        if (mag1 < 1e-6 || mag2 < 1e-6) return 0.0;
        double cos = Math.max(-1.0, Math.min(1.0, dot / (mag1 * mag2)));
        return Math.toDegrees(Math.acos(cos));
    }

    private void updateCounterUi() {
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - sessionStartMs);
        int rate = (int) Math.round(punchCount * 60000.0 / elapsed);
        runOnUiThread(() -> {
            countText.setText(String.valueOf(punchCount));
            rateText.setText(rate + " punches/min");
            statusText.setText("Punch detected ✓");
        });
    }

    private void resetCounter() {
        punchCount = 0;
        sessionStartMs = 0L;
        lastPunchMs = 0L;
        leftArmed = true;
        rightArmed = true;
        countText.setText("0");
        rateText.setText("0 punches/min");
        statusText.setText("Counter reset");
    }

    private void toggleRecording() {
        if (videoCapture == null) return;
        if (recording != null) {
            recording.stop();
            recording = null;
            recordButton.setText("START RECORDING");
            return;
        }

        String name = "PunchCounter_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PunchCounter");
        }

        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();

        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, outputOptions);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                recordButton.setText("STOP RECORDING");
                statusText.setText("Recording • live counter active");
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                recording = null;
                recordButton.setText("START RECORDING");
                if (fin.hasError()) {
                    statusText.setText("Recording error: " + fin.getError());
                } else {
                    statusText.setText("Saved to Movies/PunchCounter");
                    Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recording != null) recording.stop();
        poseDetector.close();
        cameraExecutor.shutdown();
    }
}
