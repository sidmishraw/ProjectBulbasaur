package io.sidmishraw.bulbasaur;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {
    
    private static final Logger logger = Logger.getLogger(MainActivity.class.getName());
    
    private Button      snapButton;
    private TextureView imageView;
    
    //
    // State orientation of output image
    //
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    
    //
    // Camera related stuff
    //
    private String                 rearCamId;
    private CameraDevice           rearCam;
    private CameraCaptureSession   captureSession;
    private CaptureRequest.Builder reqBuilder;
    private Size                   imageDims;
    private ImageReader            imgReader;
    
    //
    // Background handler thread - to prevent main thread from freezing
    //
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private boolean          isFlashSupported;
    private Handler          cameraHandler;
    private HandlerThread    cameraThread;
    
    //
    // Camera Properties for computing distance
    //
    private SizeF camSensorPhysicalSize;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imageView = (TextureView) findViewById(R.id.imageArea);
        imageView.setSurfaceTextureListener(surfaceListener);
        snapButton = (Button) findViewById(R.id.snapButton);
        snapButton.setOnClickListener(new View.OnClickListener() {
            
            @Override
            public void onClick(View view) {
                computeDistance();
            }
        });
    }
    
    /**
     * <p>
     * Computes the distance of the phone from the object in the preview.
     * </p>
     */
    private void computeDistance() {
        if (null == rearCam) {
            logger.info("Camera Device is not ready, no device connected!");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            this.rearCamId = manager.getCameraIdList()[0];
            CameraCharacteristics cameraProps = manager.getCameraCharacteristics(rearCamId);
            Size[] jpegSizes = null;
            if (null != cameraProps) {
                jpegSizes =
                        cameraProps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(
                                ImageFormat.JPEG);
                
                //
                // camera sensor physical sizes
                //
                this.camSensorPhysicalSize = cameraProps.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                
                //
                // Capture image with custom size
                //
                int width = 640;
                int height = 480;
                if (null != jpegSizes && jpegSizes.length > 0) {
                    width = jpegSizes[0].getWidth();
                    height = jpegSizes[0].getHeight();
                }
                this.imgReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                List<Surface> opSurfaces = new ArrayList<>();
                opSurfaces.add(this.imgReader.getSurface());
                opSurfaces.add(new Surface(this.imageView.getSurfaceTexture()));
                
                //
                // Capture request
                //
                this.reqBuilder = rearCam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                this.reqBuilder.addTarget(this.imgReader.getSurface());
                this.reqBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                
                //
                // Orientation of device
                //
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                this.reqBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
                
                //
                // CameraCaptureSession - begins
                //
                this.imgReader.setOnImageAvailableListener(this.imageReadyListener, this.cameraHandler);
                final CameraCaptureSession.CaptureCallback captureListener =
                        new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                super.onCaptureCompleted(session, request, result);
                                float imgHeight = imgReader.getHeight(); // image actual height
                                float imgWidth = imgReader.getWidth(); // image actual width
                                String msg =
                                        String.format("Focal length: %f mm, \n Sensor Height: %f mm, \n Sensor Width:"
                                                + " %f mm, \n Image Height: %f pixels, \n Image Width: %f pixels, \n "
                                                + "Focus Distance: %f mm", result.get(result.LENS_FOCAL_LENGTH),
                                                camSensorPhysicalSize.getHeight(), camSensorPhysicalSize.getWidth(),
                                                imgHeight, imgWidth, result.get(
                                                        TotalCaptureResult.LENS_FOCUS_DISTANCE));
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                                createCameraPreview();
                            }
                        };
                
                this.rearCam.createCaptureSession(opSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        try {
                            cameraCaptureSession.capture(reqBuilder.build(), captureListener, cameraHandler);
                        } catch (Exception e) {
                            logger.severe(e.getMessage());
                        }
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {}
                }, cameraHandler);
            }
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
        
    }
    
    /**
     * Create Camera Preview on {@link SurfaceTexture}.
     */
    private void createCameraPreview() {
        try {
            SurfaceTexture texture = this.imageView.getSurfaceTexture();
            if (null != texture) {
                texture.setDefaultBufferSize(this.imageDims.getWidth(), this.imageDims.getHeight());
                Surface surface = new Surface(texture);
                this.reqBuilder = this.rearCam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                this.reqBuilder.addTarget(surface);
                this.rearCam.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        if (null == rearCam) {
                            return;
                        }
                        captureSession = cameraCaptureSession;
                        updatePreview();
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        Toast.makeText(MainActivity.this, "Failed!", Toast.LENGTH_SHORT).show();
                    }
                }, null);
            }
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }
    
    private void updatePreview() {
        if (this.rearCam == null) {
            return;
        }
        this.reqBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        try {
            this.captureSession.setRepeatingRequest(this.reqBuilder.build(), null, cameraHandler);
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }
    
    /**
     * <p>
     * {@link TextureView.SurfaceTextureListener} used for listening to changes to the {@link TextureView}.
     * </p>
     */
    private TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            openCamera();
        }
        
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {}
        
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }
        
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
    };
    
    /**
     * <p>
     * This listener listens to image ready event.
     * </p>
     */
    private ImageReader.OnImageAvailableListener imageReadyListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Image img = null;
            try {
                img = imageReader.acquireLatestImage();
            } catch (Exception e) {
                logger.severe(e.getMessage());
            } finally {
                if (null != img) {
                    img.close();
                }
            }
        }
    };
    
    /**
     * <p>
     * Open the camera
     * </p>
     */
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            this.rearCamId = manager.getCameraIdList()[0];
            CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(this.rearCamId);
            StreamConfigurationMap streamConfigurationMap =
                    cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert streamConfigurationMap != null;
            this.imageDims = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];
            manager.openCamera(this.rearCamId, this.camStateCallback, null);
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }
    
    /**
     * Camera device callback. Camera2 API has moved over to asynchronous-callback mechanism.
     */
    private CameraDevice.StateCallback camStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            rearCam = cameraDevice;
            createCameraPreview();
        }
        
        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
        }
        
        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            rearCam = null; // re-initialize
        }
    };
    
    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (this.imageView.isAvailable()) {
            openCamera();
        } else {
            this.imageView.setSurfaceTextureListener(this.surfaceListener);
        }
    }
    
    @Override
    protected void onPause() {
        stopBackgroundThread();
        super.onPause();
    }
    
    private void stopBackgroundThread() {
        this.cameraThread.quitSafely();
        try {
            this.cameraThread.join();
            this.cameraThread = null;
            this.cameraHandler = null;
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }
    
    private void startBackgroundThread() {
        this.cameraThread = new HandlerThread("Camera handler thread");
        this.cameraThread.start();
        this.cameraHandler = new Handler(this.cameraThread.getLooper());
    }
    
}
