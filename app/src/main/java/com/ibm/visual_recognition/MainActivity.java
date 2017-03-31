package com.ibm.visual_recognition;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.ibm.watson.developer_cloud.service.exception.ForbiddenException;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.ClassifyImagesOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.DetectedFaces;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.VisualClassification;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.VisualRecognitionOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;

import com.ibm.mobilefirstplatform.clientsdk.android.core.api.BMSClient;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPush;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushException;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushResponseListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushNotificationListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPSimplePushNotification;
import com.ibm.mobilefirstplatform.clientsdk.android.analytics.api.Analytics;
import com.ibm.mobilefirstplatform.clientsdk.android.logger.api.Logger;
import com.ibm.bluemix.appid.android.api.AppID;
import com.ibm.bluemix.appid.android.api.AppIDAuthorizationManager;



public class MainActivity extends AppCompatActivity {

    private MFPPush push;
    private MFPPushNotificationListener notificationListener;
    

    private static final String STATE_IMAGE = "image";
    private static final int REQUEST_CAMERA = 1;
    private static final int REQUEST_GALLERY = 2;

    // Visual Recognition Service has a maximum file size limit that we control by limiting the size of the image.
    private static final float MAX_IMAGE_DIMENSION = 1200;

    private VisualRecognition visualService;
    private RecognitionResultFragment resultFragment;

    private String mSelectedImageUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Using a retained fragment to hold our result from the Recognition Service, create if it doesn't exist.
        resultFragment = (RecognitionResultFragment)getSupportFragmentManager().findFragmentByTag("result");
        if (resultFragment == null) {
            resultFragment = new RecognitionResultFragment();
            resultFragment.setRetainInstance(true);
            getSupportFragmentManager().beginTransaction().add(R.id.fragment_container, resultFragment, "result").commit();
        }

        // While the fragment retains the result from Recognition, we need to handle the selected image ourselves.
        if (savedInstanceState != null) {
            mSelectedImageUri = savedInstanceState.getString(STATE_IMAGE);

            // Re-fetch the selected Bitmap from its Uri, or if null, restore the default image.
            if (mSelectedImageUri != null) {
                Uri imageUri = Uri.parse(mSelectedImageUri);
                Bitmap selectedImage = fetchBitmapFromUri(imageUri);

                ImageView selectedImageView = (ImageView) findViewById(R.id.selectedImageView);
                selectedImageView.setImageBitmap(selectedImage);
            } else {
                ImageView selectedImageView = (ImageView) findViewById(R.id.selectedImageView);
                selectedImageView.setImageDrawable(ContextCompat.getDrawable(this, R.mipmap.bend));
            }
        }

        ImageButton cameraButton = (ImageButton) findViewById(R.id.cameraButton);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                startActivityForResult(cameraIntent, REQUEST_CAMERA);
            }
        });

        ImageButton galleryButton = (ImageButton) findViewById(R.id.galleryButton);
        galleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent galleryIntent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(galleryIntent, REQUEST_GALLERY);
            }
        });

        // Core SDK must be initialized to interact with Bluemix Mobile services.
        BMSClient.getInstance().initialize(getApplicationContext(), BMSClient.REGION_US_SOUTH);

        // In this code example, Analytics is configured to record lifecycle events.
        Analytics.init(getApplication(), getString(R.string.app_name), getString(R.string.analyticsApiKey), false, Analytics.DeviceEvent.LIFECYCLE);

        // Enable Logger (disabled by default), and set level to ERROR (DEBUG by default).
        Logger.storeLogs(true);
        Logger.setLogLevel(Logger.LEVEL.ERROR);

        /* 
         * Initialize the Push Notifications client SDK with the App Guid and Client Secret from your Push Notifications service instance on Bluemix.
         * This enables authenticated interactions with your Push Notifications service instance.
         */
        push = MFPPush.getInstance();
        push.initialize(getApplicationContext(), getString(R.string.pushAppGuid), getString(R.string.pushClientSecret));

        /*  
         * Attempt to register your Android device with your Bluemix Push Notifications service instance.
         * Developers should put their user ID as the first argument.
         */
        push.registerDeviceWithUserId("YOUR_USER_ID", new MFPPushResponseListener<String>() {

            @Override
            public void onSuccess(String response) {
            
                // Split response and convert to JSON object to display User ID confirmation from the backend.
                String[] resp = response.split("Text: ");
                String userId = "";
                try {
                    org.json.JSONObject responseJSON = new org.json.JSONObject(resp[1]);
                    userId = responseJSON.getString("userId");
                } catch (org.json.JSONException e) {
                    e.printStackTrace();
                }

                android.util.Log.i("YOUR_TAG_HERE", "Successfully registered for Bluemix Push Notifications with USER ID: " + userId);
            }

            @Override
            public void onFailure(MFPPushException ex) {
            
                String errLog = "Error registering for Bluemix Push Notifications: ";
                String errMessage = ex.getErrorMessage();
                int statusCode = ex.getStatusCode();

                // Create an error log based on the response code and returned error message.
                if (statusCode == 401) {
                    errLog += "Cannot authenticate successfully with Bluemix Push Notifications service instance. Ensure your CLIENT SECRET is correct.";
                } else if (statusCode == 404 && errMessage.contains("Push GCM Configuration")) {
                    errLog += "Your Bluemix Push Notifications service instance's GCM/FCM Configuration does not exist.\n" + 
                        "Ensure you have configured GCM/FCM Push credentials on your Bluemix Push Notifications dashboard correctly.";
                } else if (statusCode == 404) {
                    errLog += "Cannot find Bluemix Push Notifications service instance, ensure your APP GUID is correct.";
                } else if (statusCode >= 500) {
                    errLog += "Bluemix and/or the Bluemix Push Notifications service are having problems. Please try again later.";
                } else if (statusCode == 0) {
                    errLog += "Request to Bluemix Push Notifications service instance timed out. Ensure your device is connected to the Internet.";
                }

                android.util.Log.e("YOUR_TAG_HERE", errLog);
            }
        });

        // A notification listener is needed to handle any incoming push notifications within the Android application.
        notificationListener = new MFPPushNotificationListener() {

            @Override
            public void onReceive (final MFPSimplePushNotification message) {
                // TODO: Process the message and add your logic here.
                android.util.Log.i("YOUR_TAG_HERE", "Received a push notification: " + message.toString());
                runOnUiThread(new Runnable() {
                    public void run() {
                        android.app.DialogFragment fragment = PushReceiverFragment.newInstance("Push notification received", message.getAlert());
                        fragment.show(getFragmentManager(), "dialog");
                    }
                });
            }
        };

        AppID.getInstance().initialize(this, getString(R.string.authTenantId), BMSClient.REGION_US_SOUTH);
        BMSClient.getInstance().setAuthorizationManager(new AppIDAuthorizationManager(AppID.getInstance()));

        

        
        visualService = new VisualRecognition(VisualRecognition.VERSION_DATE_2016_05_20,
                getString(R.string.watson_visual_recognition_api_key));

        // Immediately on start attempt to validate the user's credentials from watson_credentials.xml.
        ValidateCredentialsTask vct = new ValidateCredentialsTask();
        vct.execute();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Enable the Push Notifications client SDK to listen for push notifications using the predefined notification listener.
        if (push != null) {
            push.listen(notificationListener);
        }
        // Sends analytics data to the Mobile Analytics service. Your analytics data will only show in the Analytics dashboard after this call.
        Analytics.send();
        // Sends Logger data to the Mobile Analytics service. Your Logger data will only show in the Analytics dashboard after this call.
        Logger.send();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (push != null) {
            push.hold();
        }
    }

    @Override
    public void onDestroy() {
        // Have the fragment save its state for recreation on orientation changes.
        resultFragment.saveData();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle savedInstanceState) {
        // Save the URI of the currently selected image for recreation.
        savedInstanceState.putString(STATE_IMAGE, mSelectedImageUri);

        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_GALLERY || requestCode == REQUEST_CAMERA) {
                Uri uri = data.getData();

                mSelectedImageUri = uri.toString();

                // Fetch the Bitmap from the Uri.
                Bitmap selectedImage = fetchBitmapFromUri(uri);

                // Set the UI's Bitmap with the full-sized, rotated Bitmap.
                ImageView resultImage = (ImageView) findViewById(R.id.selectedImageView);
                resultImage.setImageBitmap(selectedImage);

                // Resize the Bitmap to constrain within Watson Image Recognition's Size Limit.
                selectedImage = resizeBitmapForWatson(selectedImage, MAX_IMAGE_DIMENSION);

                // Send the resized, rotated, bitmap to the Classify Task for Classification.
                ClassifyTask ct = new ClassifyTask();
                ct.execute(selectedImage);
            }
        }
    }

    /**
     * Displays an AlertDialogFragment with the given parameters.
     * @param errorTitle Error Title from values/strings.xml.
     * @param errorMessage Error Message either from values/strings.xml or response from server.
     * @param canContinue Whether the application can continue without needing to be rebuilt.
     */
    private void showDialog(int errorTitle, String errorMessage, boolean canContinue) {
        DialogFragment newFragment = AlertDialogFragment.newInstance(errorTitle, errorMessage, canContinue);
        newFragment.show(getFragmentManager(), "dialog");
    }

    /**
     * Asynchronously contacts the Visual Recognition Service to see if provided Credentials are valid.
     */
    private class ValidateCredentialsTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            // Check to see if the user's credentials are valid or not along with other errors.
            try {
                visualService.getClassifiers().execute();
            } catch (Exception ex) {
                if (ex.getClass().equals(ForbiddenException.class) ||
                        ex.getClass().equals(IllegalArgumentException.class)) {
                    showDialog(R.string.error_title_invalid_credentials,
                            getString(R.string.error_message_invalid_credentials), false);
                }
                else if (ex.getCause() != null && ex.getCause().getClass().equals(UnknownHostException.class)) {
                    showDialog(R.string.error_title_bluemix_connection,
                            getString(R.string.error_message_bluemix_connection), true);
                }
                else {
                    showDialog(R.string.error_title_default, ex.getMessage(), true);
                    ex.printStackTrace();
                }
            }
            return null;
        }
    }

    /**
     * Asynchronously sends the selected image to Visual Recognition for Classification then passes the
     * result to our RecognitionResultBuilder to display to the user.
     */
    private class ClassifyTask extends AsyncTask<Bitmap, Void, ClassifyTaskResult> {

        @Override
        protected void onPreExecute() {
            ProgressBar progressSpinner = (ProgressBar)findViewById(R.id.loadingSpinner);
            progressSpinner.setVisibility(View.VISIBLE);

            // Clear the current image tags from our result layout.
            LinearLayout resultLayout = (LinearLayout) findViewById(R.id.recognitionResultLayout);
            resultLayout.removeAllViews();
        }

        @Override
        protected ClassifyTaskResult doInBackground(Bitmap... params) {
            Bitmap createdPhoto = params[0];

            // Reformat Bitmap into a .jpg and save as file to input to Watson.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            createdPhoto.compress(Bitmap.CompressFormat.JPEG, 100, bytes);

            try {
                File tempPhoto = File.createTempFile("photo", ".jpg", getCacheDir());
                FileOutputStream out = new FileOutputStream(tempPhoto);
                out.write(bytes.toByteArray());
                out.close();

                // Two different service calls for objects and for faces.
                ClassifyImagesOptions classifyImagesOptions = new ClassifyImagesOptions.Builder().images(tempPhoto).build();
                VisualRecognitionOptions recognitionOptions = new VisualRecognitionOptions.Builder().images(tempPhoto).build();

                VisualClassification classification = visualService.classify(classifyImagesOptions).execute();
                DetectedFaces faces = visualService.detectFaces(recognitionOptions).execute();

                ClassifyTaskResult result = new ClassifyTaskResult(classification, faces);

                tempPhoto.delete();

                return result;
            } catch (Exception ex) {
                if (ex.getCause() != null && ex.getCause().getClass().equals(UnknownHostException.class)) {
                    showDialog(R.string.error_title_bluemix_connection,
                            getString(R.string.error_message_bluemix_connection), true);
                } else {
                    showDialog(R.string.error_title_default, ex.getMessage(), true);
                    ex.printStackTrace();
                }
                return null;
            }
        }

        @Override
        protected void onPostExecute(ClassifyTaskResult result) {
            ProgressBar progressSpinner = (ProgressBar)findViewById(R.id.loadingSpinner);
            progressSpinner.setVisibility(View.GONE);

            if (result != null) {
                // If not null send the full result from ToneAnalyzer to our UI Builder class.
                RecognitionResultBuilder resultBuilder = new RecognitionResultBuilder(MainActivity.this);
                LinearLayout resultLayout = (LinearLayout) findViewById(R.id.recognitionResultLayout);

                resultLayout.removeAllViews();
                LinearLayout recognitionView = resultBuilder.buildRecognitionResultView(result.getVisualClassification(), result.getDetectedFaces());

                resultLayout.addView(recognitionView);
            }
        }
    }

    /**
     * Holds our output data from the Visual Recognition Service Calls to be passed to onPostExecute.
     */
    private class ClassifyTaskResult {
        private final VisualClassification visualClassification;
        private final DetectedFaces detectedFaces;

        ClassifyTaskResult (VisualClassification vcIn, DetectedFaces dfIn) {
            visualClassification = vcIn;
            detectedFaces = dfIn;
        }

        VisualClassification getVisualClassification() { return visualClassification;}
        DetectedFaces getDetectedFaces() {return detectedFaces;}
    }

    /**
     * Fetches a bitmap image from the device given the image's uri.
     * @param imageUri Uri of the image on the device (either in the gallery or from the camera).
     * @return A Bitmap representation of the image on the device, correctly orientated.
     */
    private Bitmap fetchBitmapFromUri(Uri imageUri) {
        try {
            // Fetch the Bitmap from the Uri.
            Bitmap selectedImage = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);

            // Fetch the orientation of the Bitmap in storage.
            String[] orientationColumn = {MediaStore.Images.Media.ORIENTATION};
            Cursor cursor = getContentResolver().query(imageUri, orientationColumn, null, null, null);
            int orientation = 0;
            if (cursor != null && cursor.moveToFirst()) {
                orientation = cursor.getInt(cursor.getColumnIndex(orientationColumn[0]));
            }
            cursor.close();

            // Rotate the bitmap with the found orientation.
            Matrix matrix = new Matrix();
            matrix.setRotate(orientation);
            selectedImage = Bitmap.createBitmap(selectedImage, 0, 0, selectedImage.getWidth(), selectedImage.getHeight(), matrix, true);

            return selectedImage;

        } catch (IOException e) {
            showDialog(R.string.error_title_default, e.getMessage(), true);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Scales the given image to an image that fits within the size constraints placed by Visual Recognition.
     * @param originalImage Full-sized Bitmap to be scaled.
     * @param maxSize The maximum allowed dimension of the image.
     * @return The original image rescaled so that it's largest dimension is equal to maxSize
     */
    private Bitmap resizeBitmapForWatson(Bitmap originalImage, float maxSize) {

        int originalHeight = originalImage.getHeight();
        int originalWidth = originalImage.getWidth();

        int boundingDimension = (originalHeight > originalWidth) ? originalHeight : originalWidth;

        float scale = maxSize / boundingDimension;

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        originalImage = Bitmap.createBitmap(originalImage, 0, 0, originalWidth, originalHeight, matrix, true);

        return originalImage;
    }
}
