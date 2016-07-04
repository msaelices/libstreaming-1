/*
 * Copyright (C) 2011-2015 GUIGUI Simon, fyhertz@gmail.com
 *
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 *
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.video;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.WindowManager;

import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.Stream;
import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.hw.NV21Convertor;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;
import net.majorkernelpanic.streaming.utils.SerialExecutor;
import net.majorkernelpanic.streaming.utils.YuvRotator;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;

/**
 * Don't use this class directly.
 */
public abstract class VideoStream extends MediaStream implements SensorEventListener, Camera.AutoFocusCallback {

	protected final static String TAG = "VideoStream";

	protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
	protected VideoQuality mQuality = mRequestedQuality.clone();
	protected SurfaceHolder.Callback mSurfaceHolderCallback = null;
	protected SurfaceView mSurfaceView = null;
	protected SharedPreferences mSettings = null;
	protected int mVideoEncoder, mCameraId = 0;
	protected int mRequestedOrientation = 0, mOrientation = 0;
	protected Camera mCamera;
	protected Thread mCameraThread;
	protected Looper mCameraLooper;

	protected boolean mCameraOpenedManually = true;
	protected boolean mFlashEnabled = false;
	protected boolean mSurfaceReady = false;
	protected boolean mUnlocked = false;
	protected boolean mPreviewStarted = false;
	protected boolean mUpdated = false;

	protected String mMimeType;
	protected String mEncoderName;
	protected int mEncoderColorFormat;
	protected int mCameraImageFormat;
	protected int mMaxFps = 0;
	protected SerialExecutor mExecutor;
	protected BlockingQueue<byte[]> mDataQueue;
	protected WeakReference<Activity> mActivityRef;

	//Modified: some device have problem to auto focus with the FOCUS_MODE_CONTINUOUS_PICTURE mode
	//use ACCELEROMETER sensor to implement the auto focus function
	private Context mContext;
	private Sensor mAccelerometer;
	private SensorManager mSensorManager;
	private float motionX = 0;
	private float motionY = 0;
	private float motionZ = 0;

	private boolean mRequestedPortrait = false;

	/**
	 * Don't use this class directly.
	 * Uses CAMERA_FACING_BACK by default.
	 */
	public VideoStream() {
		this(CameraInfo.CAMERA_FACING_BACK, null);
	}

	/**
	 * Don't use this class directly
	 * @param camera Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 */
	@SuppressLint("InlinedApi")
	public VideoStream(int camera, Context context) {
		super();
		setContext(context);
		setCamera(camera);

		//Modified: some device have problem to auto focus with the FOCUS_MODE_CONTINUOUS_PICTURE mode
		//use ACCELEROMETER sensor to implement the auto focus function
//		mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
//		mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
	}

	/**
	 * Sets the context to use the sensor
	 * @param context
	 */
	public void setContext(Context context) {
		mContext = context;
	}

	/**
	 * Gets the context
	 */
	public Context getContext() {
		return mContext;
	}

	public void setActivityRef(WeakReference<Activity> activityRef) {
		mActivityRef = activityRef;
	}

	/**
	 * Force video output to portrait.
	 * @param portrait If true, output video will be portrait.
	 */
	public void setPortrait(boolean portrait) {
		mRequestedPortrait = portrait;
	}

	/**
	 * Sets the camera that will be used to capture video.
	 * You can call this method at any time and changes will take effect next time you start the stream.
	 * @param camera Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 */
	public void setCamera(int camera) {
		CameraInfo cameraInfo = new CameraInfo();
		int numberOfCameras = Camera.getNumberOfCameras();
		for (int i=0;i<numberOfCameras;i++) {
			Camera.getCameraInfo(i, cameraInfo);
			if (cameraInfo.facing == camera) {
				mCameraId = i;
				break;
			}
		}
	}

	/**	Switch between the front facing and the back facing camera of the phone.
	 * If {@link #startPreview()} has been called, the preview will be  briefly interrupted.
	 * If {@link #start()} has been called, the stream will be  briefly interrupted.
	 * You should not call this method from the main thread if you are already streaming.
	 * @throws IOException
	 * @throws RuntimeException
	 **/
	public void switchCamera() throws RuntimeException, IOException {
		if (Camera.getNumberOfCameras() == 1) throw new IllegalStateException("Phone only has one camera !");
		boolean previewing = mCamera!=null && mCameraOpenedManually;
		mCameraId = (mCameraId == CameraInfo.CAMERA_FACING_BACK) ? CameraInfo.CAMERA_FACING_FRONT : CameraInfo.CAMERA_FACING_BACK;
		setCamera(mCameraId);
		stopPreview();
		mFlashEnabled = false;
		if (previewing) startPreview();
		if (mStreaming) start();
	}

	/**
	 * Returns the id of the camera currently selected.
	 * Can be either {@link CameraInfo#CAMERA_FACING_BACK} or
	 * {@link CameraInfo#CAMERA_FACING_FRONT}.
	 */
	public int getCamera() {
		return mCameraId;
	}

	/**
	 * Sets a Surface to show a preview of recorded media (video).
	 * You can call this method at any time and changes will take effect next time you call {@link #start()}.
	 */
	public synchronized void setSurfaceView(SurfaceView view) {
		mSurfaceView = view;
		if (mSurfaceHolderCallback != null && mSurfaceView != null && mSurfaceView.getHolder() != null) {
			mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
		}
		if (mSurfaceView.getHolder() != null) {
			mSurfaceHolderCallback = new Callback() {
				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
					mSurfaceReady = false;
					stopPreview();
					Log.d(TAG,"Surface destroyed !");
				}
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					mSurfaceReady = true;
				}
				@Override
				public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
					Log.d(TAG,"Surface Changed !");
				}
			};
			mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
			mSurfaceReady = true;
		}
	}

	/** Turns the LED on or off if phone has one. */
	public synchronized void setFlashState(boolean state) {
		// If the camera has already been opened, we apply the change immediately
		if (mCamera != null) {

			if (mStreaming && mMode == MODE_MEDIARECORDER_API) {
				lockCamera();
			}

			Parameters parameters = mCamera.getParameters();

			// We test if the phone has a flash
			if (parameters.getFlashMode()==null) {
				// The phone has no flash or the choosen camera can not toggle the flash
				throw new RuntimeException("Can't turn the flash on !");
			} else {
				parameters.setFlashMode(state?Parameters.FLASH_MODE_TORCH:Parameters.FLASH_MODE_OFF);
				try {
					mCamera.setParameters(parameters);
					mFlashEnabled = state;
				} catch (RuntimeException e) {
					mFlashEnabled = false;
					throw new RuntimeException("Can't turn the flash on !");
				} finally {
					if (mStreaming && mMode == MODE_MEDIARECORDER_API) {
						unlockCamera();
					}
				}
			}
		} else {
			mFlashEnabled = state;
		}
	}

	/**
	 * Toggles the LED of the phone if it has one.
	 * You can get the current state of the flash with {@link VideoStream#getFlashState()}.
	 */
	public synchronized void toggleFlash() {
		setFlashState(!mFlashEnabled);
	}

	/** Indicates whether or not the flash of the phone is on. */
	public boolean getFlashState() {
		return mFlashEnabled;
	}

	/**
	 * Sets the orientation of the preview.
	 * @param orientation The orientation of the preview
	 */
	public void setPreviewOrientation(int orientation) {
		mRequestedOrientation = orientation;
		mUpdated = false;
	}

	/**
	 * Sets the configuration of the stream. You can call this method at any time
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param videoQuality Quality of the stream
	 */
	public void setVideoQuality(VideoQuality videoQuality) {
		if (!mRequestedQuality.equals(videoQuality)) {
			mRequestedQuality = videoQuality.clone();
			mUpdated = false;
		}
	}

	/**
	 * Returns the quality of the stream.
	 */
	public VideoQuality getVideoQuality() {
		return mRequestedQuality;
	}

	/**
	 * Some data (SPS and PPS params) needs to be stored when {@link #getSessionDescription()} is called
	 * @param prefs The SharedPreferences that will be used to save SPS and PPS parameters
	 */
	public void setPreferences(SharedPreferences prefs) {
		mSettings = prefs;
	}

	/**
	 * Configures the stream. You need to call this before calling {@link #getSessionDescription()}
	 * to apply your configuration of the stream.
	 */
	public synchronized void configure() throws IllegalStateException, IOException {
		super.configure();
		mOrientation = mRequestedOrientation;
	}

	/**
	 * Starts the stream.
	 * This will also open the camera and display the preview
	 * if {@link #startPreview()} has not already been called.
	 */
	public synchronized void start() throws IllegalStateException, IOException {
		if (!mPreviewStarted) mCameraOpenedManually = false;
		super.start();
		Log.d(TAG,"Stream configuration: FPS: "+mQuality.framerate+" Width: "+mQuality.resX+" Height: "+mQuality.resY);
	}

	/** Stops the stream. */
	public synchronized void stop() {
		//Modified: some device have problem to auto focus with the FOCUS_MODE_CONTINUOUS_PICTURE mode
		//use ACCELEROMETER sensor to implement the auto focus function
		if (mSensorManager != null) {
			mSensorManager.unregisterListener(this, mAccelerometer);
		}
		if (mCamera != null) {
			if (mMode == MODE_MEDIACODEC_API) {
				mCamera.setPreviewCallbackWithBuffer(null);
				if (mDataQueue != null) {
					mDataQueue = null;
				}
				if( mExecutor != null ) {
					mExecutor.shoutDown();
					mExecutor = null;
				}
			}
			if (mMode == MODE_MEDIACODEC_API_2) {
				((SurfaceView)mSurfaceView).removeMediaCodecSurface();
			}
			super.stop();
			// We need to restart the preview
			if (!mCameraOpenedManually) {
				destroyCamera();
			} else {
				try {
					startPreview();
				} catch (RuntimeException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public synchronized void startPreview()
			throws CameraInUseException,
			InvalidSurfaceException,
			RuntimeException {

		mCameraOpenedManually = true;
		if (!mPreviewStarted) {
			createCamera();
			updateCamera();
		}

		//Modified: some device have problem to auto focus with the FOCUS_MODE_CONTINUOUS_PICTURE mode
		//use ACCELEROMETER sensor to implement the auto focus function
		if (mSensorManager != null) {
			mSensorManager.registerListener(this, mAccelerometer
					, SensorManager.SENSOR_DELAY_NORMAL);
		}
	}

	/**
	 * Stops the preview.
	 */
	public synchronized void stopPreview() {
		mCameraOpenedManually = false;
		stop();
	}

	/**
	 * Video encoding is done by a MediaRecorder.
	 */
	protected void encodeWithMediaRecorder() throws IOException, ConfNotSupportedException {

		Log.d(TAG,"Video encoded using the MediaRecorder API");

		// We need a local socket to forward data output by the camera to the packetizer
		createSockets();

		// Reopens the camera if needed
		destroyCamera();
		createCamera();

		// The camera must be unlocked before the MediaRecorder can use it
		unlockCamera();

		try {
			mMediaRecorder = new MediaRecorder();
			mMediaRecorder.setCamera( mCamera );
			mMediaRecorder.setVideoSource( MediaRecorder.VideoSource.CAMERA );
			mMediaRecorder.setOutputFormat( MediaRecorder.OutputFormat.THREE_GPP );
			mMediaRecorder.setVideoEncoder( mVideoEncoder );
			mMediaRecorder.setPreviewDisplay( mSurfaceView.getHolder().getSurface() );
			mMediaRecorder.setVideoSize( mRequestedQuality.resX, mRequestedQuality.resY );
			mMediaRecorder.setVideoFrameRate( mRequestedQuality.framerate );
			mMediaRecorder.setProfile( CamcorderProfile.get(mCameraId,CamcorderProfile.QUALITY_HIGH) );
//			mMediaRecorder.setOrientationHint(90);

			// The bandwidth actually consumed is often above what was requested
			mMediaRecorder.setVideoEncodingBitRate((int)(mRequestedQuality.bitrate*0.8));

			// We write the output of the camera in a local socket instead of a file !
			// This one little trick makes streaming feasible quiet simply: data from the camera
			// can then be manipulated at the other end of the socket
			FileDescriptor fd = null;
			if (sPipeApi == PIPE_API_PFD) {
				fd = mParcelWrite.getFileDescriptor();
			} else  {
				fd = mSender.getFileDescriptor();
			}
			mMediaRecorder.setOutputFile(fd);

			mMediaRecorder.prepare();
			mMediaRecorder.start();

		} catch (Exception e) {
			throw new ConfNotSupportedException(e.getMessage());
		}

		InputStream is = null;

		if (sPipeApi == PIPE_API_PFD) {
			is = new ParcelFileDescriptor.AutoCloseInputStream(mParcelRead);
		} else  {
			is = mReceiver.getInputStream();
		}

		// This will skip the MPEG4 header if this step fails we can't stream anything :(
		try {
			byte buffer[] = new byte[4];
			// Skip all atoms preceding mdat atom
			while (!Thread.interrupted()) {
				while (is.read() != 'm');
				is.read(buffer,0,3);
				if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') break;
			}
		} catch (IOException e) {
			Log.e(TAG,"Couldn't skip mp4 header :/");
			stop();
			throw e;
		}

		// The packetizer encapsulates the bit stream in an RTP stream and send it over the network
		mPacketizer.setInputStream(is);
		mPacketizer.start();

		mStreaming = true;

	}


	/**
	 * Video encoding is done by a MediaCodec.
	 */
	protected void encodeWithMediaCodec() throws RuntimeException, IOException {
		if (mMode == MODE_MEDIACODEC_API_2) {
			// Uses the method MediaCodec.createInputSurface to feed the encoder
			encodeWithMediaCodecMethod2();
		} else {
			// Uses dequeueInputBuffer to feed the encoder
			encodeWithMediaCodecMethod1();
		}
	}

	/**
	 * Video encoding is done by a MediaCodec.
	 */
	@SuppressLint("NewApi")
	protected void encodeWithMediaCodecMethod1() throws RuntimeException, IOException {
		Log.d(TAG,"Video encoded using the MediaCodec API with a buffer");

		// Updates the parameters of the camera if needed
		createCamera();
		updateCamera();

		// Estimates the frame rate of the camera
//		measureFramerate();

		// Starts the preview if needed
		if (!mPreviewStarted) {
			try {
				mCamera.startPreview();
				mPreviewStarted = true;
			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}
		}

		EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);
		final NV21Convertor convertor = debugger.getNV21Convertor();

		String name = debugger.getEncoderName();
		int format = debugger.getEncoderColorFormat();
		Log.d(TAG, "Encode1: "+name+"("+format+") "+mQuality.resX+"x"+mQuality.resY+" "+mQuality.bitrate+"bps"+" "+mQuality.framerate+"fps");
		Log.d(TAG, "Convert: " + convertor.toString());

		mMediaCodec = MediaCodec.createByCodecName(name);
		MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, format);
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mMediaCodec.start();

		mDataQueue = new LinkedBlockingDeque<>();
		mExecutor = new SerialExecutor( new SerialExecutor.ThreadExecutor(  ) );
		mExecutor.execute(new RotationRunner(convertor));

		Camera.PreviewCallback callback = new Camera.PreviewCallback() {
//			long now = System.nanoTime()/1000, oldnow = now, i=0;
//			ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {
//				oldnow = now;
//				now = System.nanoTime()/1000;
//				if (i++>3) {
//					i = 0;
//					//Log.d(TAG,"Measured: "+1000000L/(now-oldnow)+" fps.");
//				}
//				try {
//					int bufferIndex = mMediaCodec.dequeueInputBuffer(500000);
//					if (bufferIndex>=0) {
//						inputBuffers[bufferIndex].clear();
//						if (data == null) Log.e(TAG,"Symptom of the \"Callback buffer was to small\" problem...");
//						else convertor.convert(data, inputBuffers[bufferIndex]);
//						mMediaCodec.queueInputBuffer(bufferIndex, 0, inputBuffers[bufferIndex].position(), now, 0);
//					} else {
//						Log.e(TAG,"No buffer available !");
//					}
//				} finally {
//					mCamera.addCallbackBuffer(data);
//				}
				mDataQueue.add(data);
			}

		};

		for (int i=0;i<10;i++) mCamera.addCallbackBuffer(new byte[convertor.getBufferSize()]);
		mCamera.setPreviewCallbackWithBuffer(callback);

		// The packetizer encapsulates the bit stream in an RTP stream and send it over the network
		mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
		mPacketizer.start();

		mStreaming = true;

	}

	/**
	 * Video encoding is done by a MediaCodec.
	 * But here we will use the buffer-to-surface method
	 */
	@SuppressLint({ "InlinedApi", "NewApi" })
	protected void encodeWithMediaCodecMethod2() throws RuntimeException, IOException {

		Log.d(TAG,"Video encoded using the MediaCodec API with a surface");

		// Updates the parameters of the camera if needed
		createCamera();
		updateCamera();

		// Estimates the frame rate of the camera
//		measureFramerate();

		EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);

		Log.d(TAG, "Encode2: "+mQuality.resX+"x"+mQuality.resY+" "+mQuality.bitrate+"bps"+" "+mQuality.framerate+"fps");

		mMediaCodec = MediaCodec.createByCodecName(debugger.getEncoderName());
		MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		Surface surface = mMediaCodec.createInputSurface();
		((SurfaceView)mSurfaceView).addMediaCodecSurface(surface);
		mMediaCodec.start();

		// The packetizer encapsulates the bit stream in an RTP stream and send it over the network
		mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
		mPacketizer.start();

		mStreaming = true;

	}

	/**
	 * Returns a description of the stream using SDP.
	 * This method can only be called after {@link Stream#configure()}.
	 * @throws IllegalStateException Thrown when {@link Stream#configure()} wa not called.
	 */
	public abstract String getSessionDescription() throws IllegalStateException;

	/**
	 * Opens the camera in a new Looper thread so that the preview callback is not called from the main thread
	 * If an exception is thrown in this Looper thread, we bring it back into the main thread.
	 * @throws RuntimeException Might happen if another app is already using the camera.
	 */
	private void openCamera() throws RuntimeException {
		final Semaphore lock = new Semaphore(0);
		final RuntimeException[] exception = new RuntimeException[1];
		mCameraThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Looper.prepare();
				mCameraLooper = Looper.myLooper();
				try {
					mCamera = Camera.open(mCameraId);
				} catch (RuntimeException e) {
					exception[0] = e;
				} finally {
					lock.release();
					Looper.loop();
				}
			}
		});
		mCameraThread.start();
		lock.acquireUninterruptibly();
		if (exception[0] != null) throw new CameraInUseException(exception[0].getMessage());
	}

	protected synchronized void createCamera() throws RuntimeException {
		if (mSurfaceView == null)
			throw new InvalidSurfaceException("Invalid surface !");
		if (mSurfaceView.getHolder() == null || !mSurfaceReady)
			throw new InvalidSurfaceException("Invalid surface !");

		if (mCamera == null) {
			openCamera();
			mUpdated = false;
			mUnlocked = false;
			mCamera.setErrorCallback(new Camera.ErrorCallback() {
				@Override
				public void onError(int error, Camera camera) {
					// On some phones when trying to use the camera facing front the media server will die
					// Whether or not this callback may be called really depends on the phone
					if (error == Camera.CAMERA_ERROR_SERVER_DIED) {
						// In this case the application must release the camera and instantiate a new one
						Log.e(TAG,"Media server died !");
						// We don't know in what thread we are so stop needs to be synchronized
						mCameraOpenedManually = false;
						stop();
					} else {
						Log.e(TAG,"Error unknown with the camera: "+error);
					}
				}
			});

			try {

				// If the phone has a flash, we turn it on/off according to mFlashEnabled
				// setRecordingHint(true) is a very nice optimization if you plane to only use the Camera for recording
				Parameters parameters = mCamera.getParameters();
				if (parameters.getFlashMode()!=null) {
					parameters.setFlashMode(mFlashEnabled?Parameters.FLASH_MODE_TORCH:Parameters.FLASH_MODE_OFF);
				}
				//Modified: set other parameters.
				String focusMode = Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
				List<String> supportedFocusModes = parameters.getSupportedFocusModes();
				if (supportedFocusModes != null && supportedFocusModes.contains(focusMode)) {
					parameters.setFocusMode(focusMode);
				}
				String colorEffect = Parameters.EFFECT_NONE;
				List<String> supportedColorEffects = parameters.getSupportedColorEffects();
				if (supportedColorEffects != null && supportedColorEffects.contains(colorEffect)) {
					parameters.setColorEffect(colorEffect);
				}
				String sceneMode = Parameters.SCENE_MODE_AUTO;
				List<String> supportedSceneModes = parameters.getSupportedSceneModes();
				if (supportedSceneModes != null && supportedSceneModes.contains(sceneMode)) {
					parameters.setSceneMode(sceneMode);
				}
				String antiBanding = Parameters.ANTIBANDING_AUTO;
				List<String> supportedAntiBanding = parameters.getSupportedAntibanding();
				if (supportedAntiBanding != null && supportedAntiBanding.contains(antiBanding)) {
					parameters.setAntibanding(antiBanding);
				}
				if (parameters.isAutoExposureLockSupported()) {
					parameters.setAutoExposureLock(false);
				}
				if (parameters.isAutoWhiteBalanceLockSupported()) {
					parameters.setAutoWhiteBalanceLock(false);
				}
				if (parameters.isZoomSupported()) {
					parameters.setZoom(0);
				}
				parameters.setRecordingHint( false );
				mCamera.setParameters(parameters);
				setCameraDisplayOrientation();

				try {
					if (mMode == MODE_MEDIACODEC_API_2) {
						mSurfaceView.startGLThread();
						mCamera.setPreviewTexture(mSurfaceView.getSurfaceTexture());
					} else {
						mCamera.setPreviewDisplay(mSurfaceView.getHolder());
					}
				} catch (IOException e) {
					throw new InvalidSurfaceException("Invalid surface !");
				}

			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}

		}
	}

	protected synchronized void destroyCamera() {
		if (mCamera != null) {
			if (mStreaming) super.stop();
			lockCamera();
			mCamera.stopPreview();
			try {
				mCamera.release();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage()!=null?e.getMessage():"unknown error");
			}
			mCamera = null;
			mCameraLooper.quit();
			mUnlocked = false;
			mPreviewStarted = false;
		}
	}

//	protected synchronized void updateCamera() throws RuntimeException {
//
//		// The camera is already correctly configured
//		if (mUpdated) return;
//
//		if (mPreviewStarted) {
//			mPreviewStarted = false;
//			mCamera.stopPreview();
//		}
//
//		Parameters parameters = mCamera.getParameters();
//		mQuality = VideoQuality.determineClosestSupportedResolution(parameters, mQuality);
//		int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);
//
//		double ratio = (double)mQuality.resX/(double)mQuality.resY;
//		mSurfaceView.requestAspectRatio(ratio);
//
//		parameters.setPreviewFormat(mCameraImageFormat);
//		parameters.setPreviewSize(mQuality.resX, mQuality.resY);
//		parameters.setPreviewFpsRange(max[0], max[1]);
//
//		try {
//			mCamera.setParameters(parameters);
//			mCamera.setDisplayOrientation(mOrientation);
//			mCamera.startPreview();
//			mPreviewStarted = true;
//			mUpdated = true;
//		} catch (RuntimeException e) {
//			destroyCamera();
//			throw e;
//		}
//	}

	protected synchronized void updateCamera() throws RuntimeException {

		// The camera is already correctly configured
		if (mUpdated) return;

		if (mPreviewStarted) {
			mPreviewStarted = false;
			mCamera.stopPreview();
		}

		Parameters parameters = mCamera.getParameters();
//		mQuality = VideoQuality.determineClosestSupportedResolution(parameters, mQuality);
//		int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);

		// Modified: Find best camera preview resolution and frame rate.
		VideoQuality cameraVQ = VideoQuality.determineClosestSupportedResolution(parameters, getCameraQuality());
//		int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);
//		mQuality.framerate = max[1]/1000;
//		mRequestedQuality.framerate = max[1]/1000;

		double ratio = (double)mQuality.resX/(double)mQuality.resY;
		mSurfaceView.requestAspectRatio(ratio);

//		Log.d(TAG, "Camera : " + cameraVQ.resX + "x" + cameraVQ.resY + " " + max[0]/1000 + "-" + max[1]/1000 + "fps");
		Log.d(TAG, "Camera : " + cameraVQ.resX + "x" + cameraVQ.resY + " " + mQuality.framerate + "fps");
		Log.d(TAG, "Surface: " + mQuality.resX + "x" + mQuality.resY + " " + ratio);

		parameters.setPreviewFormat(mCameraImageFormat);
		parameters.setPreviewSize(cameraVQ.resX, cameraVQ.resY);
//		parameters.setPreviewFpsRange(max[0], max[1]);
		parameters.setPreviewFrameRate(mQuality.framerate);

		try {
			mCamera.setParameters(parameters);
			setCameraDisplayOrientation();
			mCamera.startPreview();
			mPreviewStarted = true;
			mUpdated = true;
		} catch (RuntimeException e) {
			destroyCamera();
			throw e;
		}
	}

    protected void setCameraDisplayOrientation() {
        int result;

        /* check API level. If upper API level 21, re-calculate orientation. */

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.hardware.Camera.CameraInfo info =
                    new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(mCameraId, info);
            int degrees = getRotationalOffset();
            int cameraOrientation = info.orientation;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                /* compensate the mirror */
                result = (cameraOrientation + degrees) % 360;
                result = (360 - result) % 360;
            } else {
                result = (cameraOrientation - degrees + 360) % 360;
            }

        } else {
            /* if API level is lower than 21, use the default value */
            result = mOrientation;
        }

		Log.d(TAG, "Set final display orientation to " + result);
        mCamera.setDisplayOrientation(result);
    }

    /**
     * @see <a
     * href="http://stackoverflow.com/questions/12216148/android-screen-orientation-differs-between-devices">SO
     * post</a>
     */
    private int getRotationalOffset() {
        final int rotationOffset;
        // Check "normal" screen orientation and adjust accordingly
        int naturalOrientation = ((WindowManager) mActivityRef.get().getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRotation();
        if (naturalOrientation == Surface.ROTATION_0) {
            rotationOffset = 0;
        } else if (naturalOrientation == Surface.ROTATION_90) {
            rotationOffset = 90;
        } else if (naturalOrientation == Surface.ROTATION_180) {
            rotationOffset = 180;
        } else if (naturalOrientation == Surface.ROTATION_270) {
            rotationOffset = 270;
        } else {
            // just hope for the best (shouldn't happen)
            rotationOffset = 0;
        }
        return rotationOffset;
    }

	protected void lockCamera() {
		if (mUnlocked) {
			Log.d(TAG,"Locking camera");
			try {
				mCamera.reconnect();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage());
			}
			mUnlocked = false;
		}
	}

	protected void unlockCamera() {
		if (!mUnlocked) {
			Log.d(TAG,"Unlocking camera");
			try {
				mCamera.unlock();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage());
			}
			mUnlocked = true;
		}
	}


	/**
	 * Computes the average frame rate at which the preview callback is called.
	 * We will then use this average frame rate with the MediaCodec.
	 * Blocks the thread in which this function is called.
	 */
//	private void measureFramerate() {
//		final Semaphore lock = new Semaphore(0);
//
//		final Camera.PreviewCallback callback = new Camera.PreviewCallback() {
//			int i = 0, t = 0;
//			long now, oldnow, count = 0;
//			@Override
//			public void onPreviewFrame(byte[] data, Camera camera) {
//				i++;
//				now = System.nanoTime()/1000;
//				if (i>3) {
//					t += now - oldnow;
//					count++;
//				}
//				if (i>20) {
//					mQuality.framerate = (int) (1000000/(t/count)+1);
//					lock.release();
//				}
//				oldnow = now;
//			}
//		};
//
//		mCamera.setPreviewCallback(callback);
//
//		try {
//			lock.tryAcquire(2,TimeUnit.SECONDS);
//			Log.d(TAG,"Actual framerate: "+mQuality.framerate);
//			if (mSettings != null) {
//				Editor editor = mSettings.edit();
//				editor.putInt(PREF_PREFIX+"fps"+mRequestedQuality.framerate+","+mCameraImageFormat+","+mRequestedQuality.resX+mRequestedQuality.resY, mQuality.framerate);
//				editor.commit();
//			}
//		} catch (InterruptedException e) {}
//
//		mCamera.setPreviewCallback(null);
//
//	}

	private class RotationRunner implements Runnable{

		public RotationRunner( NV21Convertor convertor ){
			mConvertor =  convertor;
		}

		private NV21Convertor mConvertor;

		private void queueFrame( byte[] data ) {
			long now = System.nanoTime()/1000;
			try {
				if( null != mMediaCodec ) {
					ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
					if ( null != inputBuffers ) {
						int bufferIndex = mMediaCodec.dequeueInputBuffer( 500000 );
						if ( bufferIndex >= 0 ) {
							inputBuffers[ bufferIndex ].clear();
							if ( data == null ) {
								Log.e( TAG, "Symptom of the \"Callback buffer was to small\" problem..." );
							} else {
								mConvertor.convert( data, inputBuffers[ bufferIndex ] );
							}
							mMediaCodec.queueInputBuffer( bufferIndex, 0, inputBuffers[ bufferIndex ].position(), now, 0 );
						} else {
							Log.e( TAG, "No buffer available !" );
						}
					}
				}
			} catch (java.lang.IllegalStateException e) {
				e.printStackTrace();
			} catch (NullPointerException e) {
				e.printStackTrace();
			} finally {
				mCamera.addCallbackBuffer(data);
			}
		}

		@Override
		public void run() {
			Log.d(TAG, "RotationRunner start");
			while (/*!Thread.currentThread().isInterrupted() &&*/ mDataQueue != null) {
				try {
					VideoQuality vq = getCameraQuality();
					byte[] data = mDataQueue.take();	// Block until preview data arrives.
					if (mCameraId == CameraInfo.CAMERA_FACING_BACK) {
						data = YuvRotator.yuv420spRotate90( data, vq.resX, vq.resY );
					} else {
						data = YuvRotator.yuv420spRotate270(data, vq.resX, vq.resY);

						// Modified: mirroring the data in NV21
						data = mirrorData(data, vq.resY, vq.resX);

						// Modified: mirroring the data when use the front camera
						// this method need to compress the yuvimage to the jpeg image
						// which is a little bit slow in some low end devices
//						ByteArrayOutputStream os = new ByteArrayOutputStream();
//						YuvImage yuv = new YuvImage(data, ImageFormat.NV21, vq.resY, vq.resX, null);
//						yuv.compressToJpeg(new Rect(0, 0, vq.resY, vq.resX), 100, os);
//						data = os.toByteArray();
//
//						Bitmap newImage = null;
//						Bitmap cameraBitmap;
//						if (data != null) {
//							cameraBitmap = BitmapFactory.decodeByteArray(data, 0, (data != null) ? data.length : 0);
//							// use matrix to reverse image data and keep it normal
//							Matrix mtx = new Matrix();
//							//this will prevent mirror effect
//							mtx.preScale(-1.0f, 1.0f);
//							// Rotating Bitmap , create real image that we want
//							newImage = Bitmap.createBitmap(cameraBitmap, 0, 0, cameraBitmap.getWidth(), cameraBitmap.getHeight(), mtx, true);
//						}
////						int size = newImage.getRowBytes() * newImage.getHeight();
////						ByteBuffer byteBuffer = ByteBuffer.allocate(size);
////						newImage.copyPixelsToBuffer(byteBuffer);
////						data = byteBuffer.array();
//						data = getNV21(newImage.getWidth(), newImage.getHeight(), newImage);
					}
					queueFrame( data );
				}
				catch ( InterruptedException e ) {
					e.printStackTrace();
				}
			}
			Log.d(TAG, "RotationRunner end");
		}

	}

	// Modified: mirroring the data
	private byte[] mirrorData(byte[] src, int srcWidth, int srcHeight) {
		byte[] dst = new byte[ srcWidth * srcHeight * 3 / 2 ];
		int wh;
		int uvHeight;
		wh = srcWidth * srcHeight;
		uvHeight = srcHeight >> 1;

		int k = 0;
		int nPos = 0;
		for (int i = 0; i < srcHeight; i++) {
			nPos += srcWidth;
			for (int j = 0; j < srcWidth; j++) {
				dst[k] = src[nPos - j - 1];
				k++;
			}
		}
		nPos = wh + srcWidth - 1;
		for (int i = 0; i < uvHeight; i++) {
			for (int j = 0; j < srcWidth; j += 2) {
				dst[k] = src[nPos - j - 1];
				dst[k + 1] = src[nPos - j];
				k += 2;
			}
			nPos += srcWidth;
		}
		return dst;
	}

	// Modified: convert the data from bitmap to NV21
	private byte[] getNV21(int inputWidth, int inputHeight, Bitmap scaled) {
		int[] argb = new int[inputWidth * inputHeight];
		scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);
		byte[] yuv = new byte[inputWidth*inputHeight*3/2];
		encodeYUV420SP(yuv, argb, inputWidth, inputHeight);
		scaled.recycle();
		return yuv;
	}

	private void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
		final int frameSize = width * height;
		int yIndex = 0;
		int uvIndex = frameSize;
		int a, R, G, B, Y, U, V;
		int index = 0;
		for (int j = 0; j < height; j++) {
			for (int i = 0; i < width; i++) {
				a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
				R = (argb[index] & 0xff0000) >> 16;
				G = (argb[index] & 0xff00) >> 8;
				B = (argb[index] & 0xff) >> 0;

				// well known RGB to YUV algorithm
				Y = ( (  66 * R + 129 * G +  25 * B + 128) >> 8) +  16;
				U = ( ( -38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
				V = ( ( 112 * R -  94 * G -  18 * B + 128) >> 8) + 128;

				// NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
				// meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
				// pixel AND every other scanline.
				yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
				if (j % 2 == 0 && index % 2 == 0) {
					yuv420sp[uvIndex++] = (byte)((V<0) ? 0 : ((V > 255) ? 255 : V));
					yuv420sp[uvIndex++] = (byte)((U<0) ? 0 : ((U > 255) ? 255 : U));
				}

				index ++;
			}
		}
	}

	private VideoQuality getCameraQuality() {
		VideoQuality vq = mQuality.clone();
		if (mRequestedPortrait && mQuality.resX < mQuality.resY) {
			vq.resX = mQuality.resY;
			vq.resY = mQuality.resX;
		}
		return vq;
	}

	//Modified: some device have problem to auto focus with the FOCUS_MODE_CONTINUOUS_PICTURE mode
	//use ACCELEROMETER sensor to implement the auto focus function
	public void onAccuracyChanged(Sensor arg0, int arg1) { }

	public void onSensorChanged(SensorEvent event) {
		if(Math.abs(event.values[0] - motionX) > 0.2
				|| Math.abs(event.values[1] - motionY) > 0.2
				|| Math.abs(event.values[2] - motionZ) > 0.2 ) {
			try {
				mCamera.autoFocus(this);
			} catch (RuntimeException e) { }
		}

		motionX = event.values[0];
		motionY = event.values[1];
		motionZ = event.values[2];
	}

	public void onAutoFocus(boolean success, Camera camera) {

	}

}
