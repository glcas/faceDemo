package com.DicHAc.demo;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.renderscript.Allocation;
import androidx.renderscript.Element;
import androidx.renderscript.RenderScript;
import androidx.renderscript.Script;
import androidx.renderscript.Type;

import com.DicHAc.Demo.ScriptC_yuv420888;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.StrictMath.sqrt;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    PreviewView mCameraView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Face mFace = new Face();
    public int[] bbox = new int[4];
    SharedPreferences sharedPreferences;
    Map<String, String> embs;
    Bitmap faceBMP;
    Bitmap face;
    SurfaceHolder holder;
    SurfaceView surfaceView;
    private int[] landmarks = new int[10];  // 存放人脸关键点
    private int[] smalLandMarks = new int[10];
    private float[] emb;
    private boolean readyFace = false;  // is having face for store?
    private boolean readyDraw;
    private String guessID;
    private Map<String, int[]> guessResult = new HashMap<>();
    private ArrayList<int[]> bboxes = new ArrayList<>();
    private boolean isFront = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.setZOrderOnTop(true);
        holder = surfaceView.getHolder();
        holder.setFormat(PixelFormat.TRANSPARENT);
        holder.addCallback(this);
        // 请求相机权限
        int permissionCheck = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 0);
        } else startCamera();
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        //noinspection SimplifiableIfStatement
        switch (id) {
            case R.id.addMenu:
                Intent intentAdd = new Intent(MainActivity.this, addFace.class);
                startActivity(intentAdd);
                return true;
            case R.id.manageMenu:
                showMultiChoiceDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    void startCamera() {
        mFace.FaceDetectionModelInit("noUse");
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
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
        preview.setSurfaceProvider(mCameraView.createSurfaceProvider());
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

        imageAnalysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
            @SuppressLint("UnsafeExperimentalUsageError")
            @Override
            public void analyze(@NonNull ImageProxy image) {
                sharedPreferences = getSharedPreferences("data", Context.MODE_PRIVATE);
                embs = (Map<String, String>) sharedPreferences.getAll();
                float rotationDegrees = (float) image.getImageInfo().getRotationDegrees();
                // My code here.
                faceBMP = YUV_420_888_toRGB(Objects.requireNonNull(image.getImage()), image.getWidth(), image.getHeight(), rotationDegrees);
                byte[] faceBytes = getPixelsRGBA(faceBMP);
                int[] faceInfo = mFace.FaceDetect(faceBytes, faceBMP.getWidth(), faceBMP.getHeight(), 4);
                readyDraw = false;
                if (faceInfo.length > 1) {
                    readyFace = true;
                    guessResult.clear();
                    bboxes.clear();
                    for (int i = 0; i < faceInfo[0]; i++) {
                        System.arraycopy(faceInfo, 5 + i * 14, landmarks, 0, 10);
                        System.arraycopy(faceInfo, 1 + i * 14, bbox, 0, 4);

                        face = Bitmap.createBitmap(faceBMP, bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]);  // 图像按bbox裁剪
                        System.arraycopy(landmarks, 0, smalLandMarks, 0, 10);
                        for (int j = 0; j < 5; j++) {
                            smalLandMarks[j] -= bbox[0];
                            smalLandMarks[j + 5] -= bbox[1];
                        }
                        emb = mFace.FaceRecognize(getPixelsRGBA(face), face.getWidth(), face.getHeight(), smalLandMarks);

                        guessID = guess(emb, embs);
                        if (guessID != null) {
                            guessResult.put(guessID, new int[]{bbox[2], bbox[3]});
                        }
                        bboxes.add(bbox.clone());
                    }
                    readyDraw = true;
                } else {
                    readyFace = false;
                    readyDraw = true;
                }
                if (isFront) {
                    DrawFocusRect(Color.parseColor("#FF5809"), bboxes);
                }
                image.close();
            }
        });
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void DrawFocusRect(int color, ArrayList<int[]> boxes) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAlpha(200);
        paint.setStrokeWidth(4f);
        Paint tPaint = new Paint();
        tPaint.setColor(color);
        tPaint.setStrokeWidth(1f);
        tPaint.setStyle(Paint.Style.FILL);
        tPaint.setTextSize(64);
        tPaint.setAlpha(200);
        if (readyDraw) {
            Canvas canvas = holder.lockCanvas();
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            if (readyFace) {
                for (String name : guessResult.keySet()) {
                    canvas.drawText(name, (480 - guessResult.get(name)[0]) * 2 + 5, guessResult.get(name)[1] * 2 - 10, tPaint);
                }
                for (int i = 0; i < boxes.size(); i++) {
                    canvas.drawRect((480 - boxes.get(i)[0]) * 2, boxes.get(i)[1] * 2, (480 - boxes.get(i)[2]) * 2, boxes.get(i)[3] * 2, paint);
                }
            }
            holder.unlockCanvasAndPost(canvas);
        }
    }

    private void showMultiChoiceDialog() {
        ArrayList<Integer> yourChoices = new ArrayList<>();
        final String[] items = embs.keySet().toArray(new String[0]);
        // 设置默认选中的选项，全为false默认均未选中
        final boolean[] initChoiceSets = new boolean[items.length];
        Arrays.fill(initChoiceSets, false);
        AlertDialog.Builder multiChoiceDialog =
                new AlertDialog.Builder(MainActivity.this);
        if (items.length == 0) {
            multiChoiceDialog.setTitle("您尚未存储任何面部信息");
            multiChoiceDialog.setNegativeButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                }
            });
        } else {
            multiChoiceDialog.setTitle("请选择想要删除的面部信息：");
            multiChoiceDialog.setMultiChoiceItems(items, initChoiceSets,
                    new DialogInterface.OnMultiChoiceClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which,
                                            boolean isChecked) {
                            if (isChecked) {
                                yourChoices.add(which);
                            } else {
                                yourChoices.remove(which);
                            }
                        }
                    });
            multiChoiceDialog.setPositiveButton("删除",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            int size = yourChoices.size();
                            if (size == 0) {
                                Toast.makeText(MainActivity.this,
                                        "您没有选中任何一个面部信息！",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                ArrayList<String> str = new ArrayList();
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                for (int index : yourChoices) {
                                    editor.remove(items[index]);
                                    str.add(items[index]);
                                }
                                editor.apply();
                                Toast.makeText(MainActivity.this,
                                        "已将" + String.join("、", str) + "的面部信息删除",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            multiChoiceDialog.setNegativeButton("取消",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                        }
                    });
        }
        multiChoiceDialog.show();
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
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode == 0) {// If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission was granted, yay! Do
                startCamera();
            }  // permission denied, boo! Disable the
            // functionality that depends on this permission.
        }
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

    public byte[] getPixelsRGBA(Bitmap image) {
        // calculate how many bytes our image consists of
        int bytes = image.getByteCount();
        ByteBuffer buffer = ByteBuffer.allocate(bytes); // Create a new buffer
        image.copyPixelsToBuffer(buffer); // Move the byte data to the buffer
        return buffer.array();
    }

    private String guess(float[] emb, Map<String, String> embs) {
        ArrayList<String> ids = new ArrayList<String>();
        ArrayList<Double> sims = new ArrayList<Double>();
        for (Map.Entry<String, String> entry : embs.entrySet()) {
            float[] thEmb = string2FloatArray(entry.getValue());
            double similar = calculSimilar(emb, thEmb);  // threshold 0.49
            double dist = calculDist(emb, thEmb);
            if (similar > 0.5) {
                ids.add(entry.getKey());
                sims.add(similar);
            }
        }
        int maxIndex = 0;
        for (int i = 0; i < sims.size(); i++) {
            if (sims.get(i) > sims.get(maxIndex)) {
                maxIndex = i;
            }
        }
        if (ids.isEmpty()) {
            return null;  // 数据库中未找到返回空
        } else {
            return ids.get(maxIndex);
        }

    }

    private double calculSimilar(float[] emb, float[] value) {
        double dot = 0, mod1 = 0, mod2 = 0;
        for (int i = 0; i < emb.length; i++) {
            dot += emb[i] * value[i];
            mod1 += emb[i] * emb[i];
            mod2 += value[i] * value[i];
        }
        return dot / (sqrt(mod1) * sqrt(mod2));
    }

    private double calculDist(float[] emb, float[] value) {
        double dist = 0, diff = 0;
        for (int i = 0; i < emb.length; i++) {
            diff = emb[i] - value[i];
            dist += diff * diff;
        }
        return sqrt(dist);
    }

    public float[] string2FloatArray(String string) {
        String[] strings = string.split("_");
        float[] floats = new float[strings.length];
        for (int i = 0; i < strings.length; i++) {
            floats[i] = Float.parseFloat(strings[i]);
        }
        return floats;
    }
}






