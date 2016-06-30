/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
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

package net.majorkernelpanic.streaming.hw;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import android.media.MediaCodecInfo;
import android.util.Log;

/**
 * Converts from NV21 to YUV420 semi planar or planar.
 */		
public class NV21Convertor {

	private int mSliceHeight, mHeight;
	private int mStride, mWidth;
	private int mSize;
	private boolean mPlanar, mPanesReversed = false;
	private int mYPadding;
	private byte[] mBuffer; 
	ByteBuffer mCopy;
	
	public void setSize(int width, int height) {
		mHeight = height;
		mWidth = width;
		mSliceHeight = height;
		mStride = width;
		mSize = mWidth*mHeight;
	}
	
	public void setStride(int width) {
		mStride = width;
	}
	
	public void setSliceHeigth(int height) {
		mSliceHeight = height;
	}
	
	public void setPlanar(boolean planar) {
		mPlanar = planar;
	}
	
	public void setYPadding(int padding) {
		mYPadding = padding;
	}
	
	public int getBufferSize() {
		return 3*mSize/2;
	}
	
	public void setEncoderColorFormat(int colorFormat) {
		switch (colorFormat) {
		// NV21 YYYYYYYYVUVU
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar: // 21 NV12 YYYYYYYYUVUV
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar: // 39 NV12 YYYYYYYYUVUV
		case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar: // 0x7f000100 NV12 YYYYYYYYUVUV
		//Modified: add all YUV420 color format
		case MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar: // 0x7fa30c00 NV12 YYYYYYYYUVUV
			setPlanar(false);
			break;
		// YV12 YYYYYYYYVVUU
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar: // 19 I420 YYYYYYYYUUVV
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar: // 20 I420 YYYYYYYYUUVV
			setPlanar(true);
			break;
		}
	}
	
	public void setColorPanesReversed(boolean b) {
		mPanesReversed = b;
	}
	
	public int getStride() {
		return mStride;
	}

	public int getSliceHeigth() {
		return mSliceHeight;
	}

	public int getYPadding() {
		return mYPadding;
	}
	
	
	public boolean getPlanar() {
		return mPlanar;
	}
	
	public boolean getUVPanesReversed() {
		return mPanesReversed;
	}
	
	public void convert(byte[] data, ByteBuffer buffer) {
		byte[] result = convert(data);
		int min = buffer.capacity() < data.length?buffer.capacity() : data.length;
		buffer.put(result, 0, min);
	}
	
	public byte[] convert(byte[] data) {

		// A buffer large enough for every case
		if (mBuffer==null || mBuffer.length != 3*mSliceHeight*mStride/2+mYPadding) {
			mBuffer = new byte[3*mSliceHeight*mStride/2+mYPadding];
		}
		
		if (!mPlanar) {
			if (mSliceHeight == mHeight && mStride == mWidth) {
				// Swaps U and V
				if (!mPanesReversed) {
					//used for COLOR_FormatYUV420SemiPlanar = 21
					//used for COLOR_FormatYUV420PackedSemiPlanar = 39
					for (int i = mSize; i < mSize + mSize / 2; i += 2) {
						mBuffer[0] = data[i + 1];
						data[i + 1] = data[i];
						data[i] = mBuffer[0];
					}

					//Modified: This algorithm used to transfer the color format from
					//YYYYYYYYUVUV to YUYYUYYVYYVY but there is a mediacodec correspond
					//document declare that COLOR_FormatYUV420PackedPlanar is same to
					//COLOR_FormatYUV420Planar, temporarily comment
//					Byte[] byteData = new Byte[data.length];
//					int k = 0;
//					for (byte temp : data) {
//						byteData[k++] = temp;
//					}
//					ArrayList<Byte> srcByteList = new ArrayList<Byte>(Arrays.asList(byteData));
//					for (int i = mSize, j = 0; i < mSize + mSize / 2 - 1; i += 1) {
//						srcByteList.add(1 + 2 * (i - mSize) + j, srcByteList.get(i));
//						srcByteList.remove(i + 1);
//						i += 1;
//						j += 1;
//						if (i + 1 > mSize + mSize / 2 - 1) {
//							srcByteList.add(1 + 2 * (i - mSize) + j, srcByteList.get(i));
//							srcByteList.remove(i + 1);
//						} else {
//							srcByteList.add(1 + 2 * (i - mSize) + j, srcByteList.get(i + 1));
//							srcByteList.remove(i + 2);
//							j += 1;
//						}
//					}
//					Byte[] bytesData = srcByteList.toArray(new Byte[srcByteList.size()]);
//					int l=0;
//					for(Byte b: bytesData) {
//						data[l++] = b.byteValue();
//					}
				}
				if (mYPadding > 0) {
					System.arraycopy(data, 0, mBuffer, 0, mSize);
					System.arraycopy(data, mSize, mBuffer, mSize + mYPadding, mSize / 2);
					return mBuffer;
				}
				return data;
			}
		} else {
			if (mSliceHeight==mHeight && mStride==mWidth) {
				// De-interleave U and V
				if (!mPanesReversed) {
					//used for COLOR_FormatYUV420Planar = 19
					//used for COLOR_FormatYUV420PackedPlanar = 20
					for (int i = 0; i < mSize/4; i+=1) {
						mBuffer[i] = data[mSize+2*i+1];
						mBuffer[mSize/4+i] = data[mSize+2*i];
					}
				} else {
					for (int i = 0; i < mSize/4; i+=1) {
						mBuffer[i] = data[mSize+2*i];
						mBuffer[mSize/4+i] = data[mSize+2*i+1];
					}
				}
				if (mYPadding == 0) {
					System.arraycopy(mBuffer, 0, data, mSize, mSize/2);
				} else {
					System.arraycopy(data, 0, mBuffer, 0, mSize);
					System.arraycopy(mBuffer, 0, mBuffer, mSize+mYPadding, mSize/2);
					return mBuffer;
				}
				return data;
			}
		}
		
		return data;
	}

	@Override
	public String toString() {
		return "width="+mWidth+" height="+mHeight+" stride="+mStride+" sliceHeight="+mSliceHeight+" size="+mSize+" planar="+mPlanar+" panesRev="+mPanesReversed+" ypad="+mYPadding;
	}
}
