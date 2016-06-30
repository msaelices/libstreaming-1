/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
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

package net.majorkernelpanic.streaming;

import android.content.Context;
import android.hardware.Camera;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import net.majorkernelpanic.streaming.audio.AACStream;
import net.majorkernelpanic.streaming.audio.AMRNBStream;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.audio.AudioStream;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.H263Stream;
import net.majorkernelpanic.streaming.video.H264Stream;
import net.majorkernelpanic.streaming.video.VideoQuality;
import net.majorkernelpanic.streaming.video.VideoStream;

import java.io.IOException;

/**
 * Call {@link #getInstance()} to get access to the SessionBuilder.
 */
public class SessionBuilder {

    public final static String TAG = "SessionBuilder";

    /**
     * Can be used with {@link #setVideoEncoder}.
     */
    public final static int VIDEO_NONE = 0;

    /**
     * Can be used with {@link #setVideoEncoder}.
     */
    public final static int VIDEO_H264 = 1;

    /**
     * Can be used with {@link #setVideoEncoder}.
     */
    public final static int VIDEO_H263 = 2;

    /**
     * Can be used with {@link #setAudioEncoder}.
     */
    public final static int AUDIO_NONE = 0;

    /**
     * Can be used with {@link #setAudioEncoder}.
     */
    public final static int AUDIO_AMRNB = 3;

    /**
     * Can be used with {@link #setAudioEncoder}.
     */
    public final static int AUDIO_AAC = 5;

    // Default configuration
    private VideoQuality mVideoQuality = VideoQuality.DEFAULT_VIDEO_QUALITY;
    private AudioQuality mAudioQuality = AudioQuality.DEFAULT_AUDIO_QUALITY;
    private Context mContext;
    private int mVideoEncoder = VIDEO_H263;
    private int mAudioEncoder = AUDIO_AMRNB;
//    private int mCamera = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int mCamera;
    private int mTimeToLive = 64;
    private int mOrientation;
    private boolean mFlash = false;
    private SurfaceView mSurfaceView = null;
    private String mOrigin = null;
    private String mDestination = null;
    private Session.Callback mCallback = null;

    // Removes the default public constructor
    private SessionBuilder() {
    }


    // The SessionManager implements the singleton pattern
    private static volatile SessionBuilder sInstance = null;

    /**
     * Returns a reference to the {@link SessionBuilder}.
     *
     * @return The reference to the {@link SessionBuilder}
     */
    public final static SessionBuilder getInstance() {
        if ( sInstance == null ) {
            synchronized ( SessionBuilder.class ) {
                if ( sInstance == null ) {
                    SessionBuilder.sInstance = new SessionBuilder();
                }
            }
        }
        return sInstance;
    }


    /**
     * Creates a new {@link Session}.
     *
     * @return The new Session
     * @throws IOException
     */
    public Session build() {
        Session session;
        if ( hasFrontFacingCamera() ) {
                mCamera = Camera.CameraInfo.CAMERA_FACING_FRONT;
            } else if ( hasBackFacingCamera() ){
                mCamera = Camera.CameraInfo.CAMERA_FACING_BACK;
            }


        session = new Session();
        session.setOrigin( mOrigin );
        session.setDestination( mDestination );
        session.setTimeToLive( mTimeToLive );
        session.setCallback( mCallback );

        switch ( mAudioEncoder ) {
            case AUDIO_AAC:
                AACStream stream = new AACStream();
                session.addAudioTrack( stream );
                if ( mContext != null )
                    stream.setPreferences( PreferenceManager.getDefaultSharedPreferences( mContext ) );
                break;
            case AUDIO_AMRNB:
                session.addAudioTrack( new AMRNBStream() );
                break;
        }

        switch ( mVideoEncoder ) {
            case VIDEO_H263:
                session.addVideoTrack( new H263Stream( mCamera, mContext ) );
                break;
            case VIDEO_H264:
                H264Stream stream = new H264Stream( mCamera, mContext );
                if ( mContext != null )
                    stream.setPreferences( PreferenceManager.getDefaultSharedPreferences( mContext ) );
                session.addVideoTrack( stream );
                break;
        }

        if ( session.getVideoTrack() != null ) {
            VideoStream video = session.getVideoTrack();
            video.setFlashState( mFlash );
            video.setVideoQuality( mVideoQuality );
            video.setSurfaceView( mSurfaceView );
            video.setPreviewOrientation( mOrientation );
            Log.d( TAG, "video mOrientation  = " + mOrientation );
            video.setDestinationPorts( 5006 );
        }

        if ( session.getAudioTrack() != null ) {
            AudioStream audio = session.getAudioTrack();
            audio.setAudioQuality( mAudioQuality );
            audio.setDestinationPorts( 5004 );
        }

        return session;

    }

    /**
     * Access to the context is needed for the H264Stream class to store some stuff in the SharedPreferences.
     * Note that you should pass the Application context, not the context of an Activity.
     **/
    public SessionBuilder setContext( Context context ) {
        mContext = context;
        return this;
    }

    /**
     * Sets the destination of the session.
     */
    public SessionBuilder setDestination( String destination ) {
        mDestination = destination;
        return this;
    }

    /**
     * Sets the origin of the session. It appears in the SDP of the session.
     */
    public SessionBuilder setOrigin( String origin ) {
        mOrigin = origin;
        return this;
    }

    /**
     * Sets the video stream quality.
     */
    public SessionBuilder setVideoQuality( VideoQuality quality ) {
        mVideoQuality = quality.clone();
        return this;
    }

    /**
     * Sets the audio encoder.
     */
    public SessionBuilder setAudioEncoder( int encoder ) {
        mAudioEncoder = encoder;
        return this;
    }

    /**
     * Sets the audio quality.
     */
    public SessionBuilder setAudioQuality( AudioQuality quality ) {
        mAudioQuality = quality.clone();
        return this;
    }

    /**
     * Sets the default video encoder.
     */
    public SessionBuilder setVideoEncoder( int encoder ) {
        mVideoEncoder = encoder;
        return this;
    }

    public SessionBuilder setFlashEnabled( boolean enabled ) {
        mFlash = enabled;
        return this;
    }

    public SessionBuilder setCamera( int camera ) {
        mCamera = camera;
        return this;
    }

    public SessionBuilder setTimeToLive( int ttl ) {
        mTimeToLive = ttl;
        return this;
    }

    /**
     * Sets the SurfaceView required to preview the video stream.
     **/
    public SessionBuilder setSurfaceView( SurfaceView surfaceView ) {
        mSurfaceView = surfaceView;
        return this;
    }

    /**
     * Sets the orientation of the preview.
     *
     * @param orientation The orientation of the preview
     */
    public SessionBuilder setPreviewOrientation( int orientation ) {

        mOrientation = orientation;

        return this;
    }

    public SessionBuilder setCallback( Session.Callback callback ) {
        mCallback = callback;
        return this;
    }

    /**
     * Returns the context set with {@link #setContext(Context)}
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Returns the destination ip address set with {@link #setDestination(String)}.
     */
    public String getDestination() {
        return mDestination;
    }

    /**
     * Returns the origin ip address set with {@link #setOrigin(String)}.
     */
    public String getOrigin() {
        return mOrigin;
    }

    /**
     * Returns the audio encoder set with {@link #setAudioEncoder(int)}.
     */
    public int getAudioEncoder() {
        return mAudioEncoder;
    }

    /**
     * Returns the id of the {@link android.hardware.Camera} set with {@link #setCamera(int)}.
     */
    public int getCamera() {
        return mCamera;
    }

    /**
     * Returns the video encoder set with {@link #setVideoEncoder(int)}.
     */
    public int getVideoEncoder() {
        return mVideoEncoder;
    }

    /**
     * Returns the VideoQuality set with {@link #setVideoQuality(VideoQuality)}.
     */
    public VideoQuality getVideoQuality() {
        return mVideoQuality;
    }

    /**
     * Returns the AudioQuality set with {@link #setAudioQuality(AudioQuality)}.
     */
    public AudioQuality getAudioQuality() {
        return mAudioQuality;
    }

    /**
     * Returns the flash state set with {@link #setFlashEnabled(boolean)}.
     */
    public boolean getFlashState() {
        return mFlash;
    }

    /**
     * Returns the SurfaceView set with {@link #setSurfaceView(SurfaceView)}.
     */
    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }


    /**
     * Returns the time to live set with {@link #setTimeToLive(int)}.
     */
    public int getTimeToLive() {
        return mTimeToLive;
    }

    /**
     * Returns a new {@link SessionBuilder} with the same configuration.
     */
    public SessionBuilder clone() {
        Log.d( TAG, "SessionBuilder clone mOrientation  = " + mOrientation );
        return new SessionBuilder()
                .setDestination( mDestination )
                .setOrigin( mOrigin )
                .setSurfaceView( mSurfaceView )
                .setPreviewOrientation( mOrientation )
                .setVideoQuality( mVideoQuality )
                .setVideoEncoder( mVideoEncoder )
                .setFlashEnabled( mFlash )
                .setCamera( mCamera )
                .setTimeToLive( mTimeToLive )
                .setAudioEncoder( mAudioEncoder )
                .setAudioQuality( mAudioQuality )
                .setContext( mContext )
                .setCallback( mCallback );
    }

    public static boolean checkCameraFacing( final int facing ) {
        if ( Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD ) {
            return false;
        }
        final int cameraCount = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for ( int i = 0; i < cameraCount; i++ ) {
            Camera.getCameraInfo( i, info );
            Log.d( TAG, "the cameraCount = " + cameraCount );
            if ( facing == info.facing ) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasBackFacingCamera() {
        final int CAMERA_FACING_BACK = 0;
        return checkCameraFacing( CAMERA_FACING_BACK );
    }

    public static boolean hasFrontFacingCamera() {
        final int CAMERA_FACING_FRONT = 0;
        return checkCameraFacing( CAMERA_FACING_FRONT );
    }

}