package com.jstappdev.dbclf;


import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.widget.Toast;

import com.jstappdev.dbclf.env.ImageUtils;

import java.io.IOException;
import java.util.List;


public class ClassifierActivity extends CameraActivity implements OnImageAvailableListener {
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;

    // mobilenet: 224, inception_v3: 299
    private static final int INPUT_SIZE = 299;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128;
    // mobilenet: input, inception_v3: Mul, Keras: input_1
    private static final String INPUT_NAME = "Mul";
    // output, inception(tf): final_result, Keras: output/Softmax
    private static final String OUTPUT_NAME = "final_result";

    private static final String MODEL_FILE = "file:///android_asset/stripped.pb";

    private static final boolean MAINTAIN_ASPECT = true;

    private static final Size DESIRED_PREVIEW_SIZE = new Size(INPUT_SIZE, INPUT_SIZE);

    private Matrix frameToCropTransform;

    private Classifier classifier;

    @Override
    void handleSendImage(Intent data) {
        final Uri imageUri = data.getParcelableExtra(Intent.EXTRA_STREAM);
        classifyLoadedImage(imageUri);
    }

    /**
     * user has chosen a picture from the image gallery
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && requestCode == PICK_IMAGE)
            classifyLoadedImage(data.getData());
    }

    private void classifyLoadedImage(Uri imageUri) {
        updateResults(null);

        final int orientation = getOrientation(getApplicationContext(), imageUri);
        final ContentResolver contentResolver = this.getContentResolver();

        try {
            final Bitmap croppedFromGallery;
            croppedFromGallery = resizeCropAndRotate(MediaStore.Images.Media.getBitmap(contentResolver, imageUri), orientation);

            runOnUiThread(() -> {
                setImage(croppedFromGallery);
                inferenceTask = new InferenceTask();
                inferenceTask.execute(croppedFromGallery);
            });
        } catch (IOException e) {
            Toast.makeText(getApplicationContext(), "Unable to load image", Toast.LENGTH_LONG).show();
        }
    }

    protected class InferenceTask extends AsyncTask<Bitmap, Void, List<Classifier.Recognition>> {
        @Override
        protected void onPreExecute() {
            if (!continuousInference)
                progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected List<Classifier.Recognition> doInBackground(Bitmap... bitmaps) {
            initClassifier();

            if (!isCancelled() && classifier != null) {
                return classifier.recognizeImage(bitmaps[0]);
            }

            return null;
        }

        @Override
        protected void onPostExecute(List<Classifier.Recognition> recognitions) {
            progressBar.setVisibility(View.GONE);

            if (!isCancelled())
                updateResults(recognitions);

            readyForNextImage();
        }
    }

    // get orientation of picture
    public int getOrientation(Context context, Uri photoUri) {
        /* it's on the external media. */
        try (final Cursor cursor = context.getContentResolver().query(photoUri,
                new String[]{MediaStore.Images.ImageColumns.ORIENTATION}, null, null, null);
        ) {
            if (cursor.getCount() != 1) {
                cursor.close();
                return -1;
            }

            if (cursor != null && cursor.moveToFirst()) {
                final int r = cursor.getInt(0);
                cursor.close();
                return r;
            }

        } catch (Exception e) {
            return -1;
        }
        return -1;
    }

    private Bitmap resizeCropAndRotate(Bitmap originalImage, int orientation) {
        Bitmap result = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

        final float originalWidth = originalImage.getWidth();
        final float originalHeight = originalImage.getHeight();

        final Canvas canvas = new Canvas(result);

        final float scale = INPUT_SIZE / originalWidth;

        final float xTranslation = 0.0f;
        final float yTranslation = (INPUT_SIZE - originalHeight * scale) / 2.0f;

        final Matrix transformation = new Matrix();
        transformation.postTranslate(xTranslation, yTranslation);
        transformation.preScale(scale, scale);

        final Paint paint = new Paint();
        paint.setFilterBitmap(true);

        canvas.drawBitmap(originalImage, transformation, paint);

        /*
         * if the orientation is not 0 (or -1, which means we don't know), we
         * have to do a rotation.
         */
        if (orientation > 0) {
            final Matrix matrix = new Matrix();
            matrix.postRotate(orientation);

            result = Bitmap.createBitmap(result, 0, 0, INPUT_SIZE,
                    INPUT_SIZE, matrix, true);
        }

        return result;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        final int sensorOrientation = rotation - getScreenOrientation();

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                INPUT_SIZE, INPUT_SIZE,
                sensorOrientation, MAINTAIN_ASPECT);

        final Matrix cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
    }

    protected synchronized void initClassifier() {
        if (classifier == null)
            try {
                classifier =
                        TensorFlowImageClassifier.create(
                                getAssets(),
                                MODEL_FILE,
                                getResources().getStringArray(R.array.breeds_array),
                                INPUT_SIZE,
                                IMAGE_MEAN,
                                IMAGE_STD,
                                INPUT_NAME,
                                OUTPUT_NAME);
            } catch (OutOfMemoryError e) {
                runOnUiThread(() -> {
                    cameraButton.setEnabled(true);
                    continuousInferenceButton.setChecked(false);
                    Toast.makeText(getApplicationContext(), R.string.error_tf_init, Toast.LENGTH_LONG).show();
                });
            }
    }

    @Override
    protected void processImage() {
        if (!snapShot.get() && !continuousInference && !imageSet) {
            readyForNextImage();
            return;
        }

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        if (snapShot.compareAndSet(true, false) || continuousInference) {
            final Bitmap finalCroppedBitmap = croppedBitmap.copy(croppedBitmap.getConfig(), false);

            runOnUiThread(() -> {
                if (!continuousInference && !imageSet)
                    setImage(finalCroppedBitmap);

                inferenceTask = new InferenceTask();
                inferenceTask.execute(finalCroppedBitmap);
            });
        }
    }

}
