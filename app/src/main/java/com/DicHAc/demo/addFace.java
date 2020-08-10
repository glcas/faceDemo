package com.DicHAc.demo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;
import androidx.renderscript.Allocation;
import androidx.renderscript.Element;
import androidx.renderscript.RenderScript;
import androidx.renderscript.Script;
import androidx.renderscript.Type;

import com.DicHAc.Demo.ScriptC_yuv420888;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class addFace extends AppCompatActivity implements SurfaceHolder.Callback {

    PreviewView mCameraView;
    Camera camera;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Face mFace = new Face();
    public int[] bbox = new int[4];
    private int[] landmarks = new int[10];  // 存放人脸关键点
    SurfaceHolder holder;
    SurfaceView surfaceView;
    private int[] smalLandMarks = new int[10];
    private float[] emb;
    private boolean readyFace = false;  // is having face for store?
    private boolean isFront = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_face);
        mFace.FaceDetectionModelInit("noUse");
        startCamera();
        @SuppressLint("ShowToast") Toast enterToast = Toast.makeText(this, getString(R.string.dialog_add_content), Toast.LENGTH_LONG);
        enterToast.show();
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.setZOrderOnTop(true);
        holder = surfaceView.getHolder();
        holder.setFormat(PixelFormat.TRANSPARENT);
        holder.addCallback(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        isFront = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        isFront = false;
    }

    void startCamera() {
        mCameraView = findViewById(R.id.preview_view);
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().setTargetResolution(new Size(960, 1280)).build();
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
        preview.setSurfaceProvider(mCameraView.createSurfaceProvider());
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview);

        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

        imageAnalysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                float degree = (float) image.getImageInfo().getRotationDegrees();
                @SuppressLint("UnsafeExperimentalUsageError") Bitmap faceBMP = YUV_420_888_toRGB(Objects.requireNonNull(image.getImage()), image.getWidth(), image.getHeight(), degree);
                byte[] faceBytes = getPixelsRGBA(faceBMP);
                // My code here.
                int[] faceInfo = mFace.MaxFaceDetect(faceBytes, faceBMP.getWidth(), faceBMP.getHeight(), 4);
                // detected bingo
                if (faceInfo.length > 1) {
                    System.arraycopy(faceInfo, 5, landmarks, 0, 10);
                    System.arraycopy(faceInfo, 1, bbox, 0, 4);

                    Bitmap face = Bitmap.createBitmap(faceBMP, bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]);  //图像按bbox裁剪
                    System.arraycopy(landmarks, 0, smalLandMarks, 0, 10);
                    for (int j = 0; j < 5; j++) {
                        smalLandMarks[j] -= bbox[0];
                        smalLandMarks[j + 5] -= bbox[1];
                    }
                    emb = mFace.FaceRecognize(getPixelsRGBA(face), face.getWidth(), face.getHeight(), smalLandMarks);
                    readyFace = true;
                } else {
                    readyFace = false;
                }
                if (isFront) {
                    DrawFocusRect(Color.parseColor("#FF5809"));
                }
                image.close();
            }
        });
        cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, preview);
    }

    /* 按下添加按钮触发，可能：1添加成功，2重复添加，3未找到人脸，4未输入id */
    public void addEmb(View view) {
        if (readyFace) {
            EditText editText = findViewById(R.id.editTextTextPersonName);
            String addName = editText.getText().toString();
            if (addName.isEmpty()) {
                @SuppressLint("ShowToast") Toast emptyNameToast = Toast.makeText(this, getString(R.string.emptyNameToast), Toast.LENGTH_LONG);
                emptyNameToast.show();
                return;
            }
            SharedPreferences sharedPreferences = getSharedPreferences("data", Context.MODE_PRIVATE);
            Map<String, ?> embs = sharedPreferences.getAll();
            for (Map.Entry<String, ?> entry : embs.entrySet()) {
                if (entry.getKey().equals(addName)) {
                    @SuppressLint("ShowToast") Toast existedToast = Toast.makeText(this, getString(R.string.existedToast), Toast.LENGTH_LONG);
                    existedToast.show();
                    editText.getText().clear();
                    return;
                }
            }
            String embSet = floatArray2String(emb);
            @SuppressLint("CommitPrefEdits") SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(addName, embSet);
            editor.apply();
            @SuppressLint("ShowToast") Toast successToast = Toast.makeText(this, getString(R.string.addSuccessToast), Toast.LENGTH_SHORT);
            successToast.show();
            editText.getText().clear();
        } else {
            @SuppressLint("ShowToast") Toast noFaceToast = Toast.makeText(this, getString(R.string.noFaceToast), Toast.LENGTH_LONG);
            noFaceToast.show();
        }
    }

    private void DrawFocusRect(int color) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setAlpha(200);
        Canvas canvas = holder.lockCanvas();
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        if (readyFace) {
            canvas.drawRect((480 - bbox[0]) * 2, bbox[1] * 2, (480 - bbox[2]) * 2, bbox[3] * 2, paint);
        }
        holder.unlockCanvasAndPost(canvas);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.addMenu) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private Bitmap YUV_420_888_toRGB(Image image, int width, int height, float rotationDegrees) {
        // Get the three image planes
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] y = new byte[buffer.remaining()];
        buffer.get(y);

        buffer = planes[1].getBuffer();
        byte[] u = new byte[buffer.remaining()];
        buffer.get(u);

        buffer = planes[2].getBuffer();
        byte[] v = new byte[buffer.remaining()];
        buffer.get(v);

        // get the relevant RowStrides and PixelStrides
        // (we know from documentation that PixelStride is 1 for y)
        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();  // we know from   documentation that RowStride is the same for u and v.
        int uvPixelStride = planes[1].getPixelStride();  // we know from   documentation that PixelStride is the same for u and v.


        // rs creation just for demo. Create rs just once in onCreate and use it again.
        RenderScript rs = RenderScript.create(this);
        //RenderScript rs = MainActivity.rs;
        ScriptC_yuv420888 mYuv420 = new ScriptC_yuv420888(rs);

        // Y,U,V are defined as global allocations, the out-Allocation is the Bitmap.
        // Note also that uAlloc and vAlloc are 1-dimensional while yAlloc is 2-dimensional.
        Type.Builder typeUcharY = new Type.Builder(rs, Element.U8(rs));
        typeUcharY.setX(yRowStride).setY(height);
        Allocation yAlloc = Allocation.createTyped(rs, typeUcharY.create());
        yAlloc.copyFrom(y);
        mYuv420.set_ypsIn(yAlloc);

        Type.Builder typeUcharUV = new Type.Builder(rs, Element.U8(rs));
        // note that the size of the u's and v's are as follows:
        //      (  (width/2)*PixelStride + padding  ) * (height/2)
        // =    (RowStride                          ) * (height/2)
        // but I noted that on the S7 it is 1 less...
        typeUcharUV.setX(u.length);
        Allocation uAlloc = Allocation.createTyped(rs, typeUcharUV.create());
        uAlloc.copyFrom(u);
        mYuv420.set_uIn(uAlloc);

        Allocation vAlloc = Allocation.createTyped(rs, typeUcharUV.create());
        vAlloc.copyFrom(v);
        mYuv420.set_vIn(vAlloc);

        // handover parameters
        mYuv420.set_picWidth(width);
        mYuv420.set_uvRowStride(uvRowStride);
        mYuv420.set_uvPixelStride(uvPixelStride);

        Bitmap outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Allocation outAlloc = Allocation.createFromBitmap(rs, outBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

        Script.LaunchOptions lo = new Script.LaunchOptions();
        lo.setX(0, width);  // by this we ignore the y’s padding zone, i.e. the right side of x between width and yRowStride
        lo.setY(0, height);

        mYuv420.forEach_doConvert(outAlloc, lo);
        outAlloc.copyTo(outBitmap);

        Bitmap bitmap = outBitmap;
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(outBitmap,
                    outBitmap.getWidth(), outBitmap.getHeight(), true);
            bitmap = Bitmap.createBitmap(scaledBitmap, 0, 0,
                    scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
        }

        return bitmap;
    }

    //提取像素点
    public byte[] getPixelsRGBA(Bitmap image) {
        // calculate how many bytes our image consists of
        int bytes = image.getByteCount();
        ByteBuffer buffer = ByteBuffer.allocate(bytes); // Create a new buffer
        image.copyPixelsToBuffer(buffer); // Move the byte data to the buffer
        return buffer.array();
    }

    public String floatArray2String(float[] floats) {
        String[] strings = new String[floats.length];
        for (int i = 0; i < floats.length; i++) {
            strings[i] = String.valueOf(floats[i]);
        }
        return String.join("_", strings);
    }

}