// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.os.Bundle;
import android.os.IBinder;
import android.os.SharedMemory;
import android.os.SystemClock;
import com.oas.android.IDeviceBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.graphics.Bitmap;
import android.os.Parcel;

/** Cache one read-only mapping, discard it after service replacement or display resize/release. */
public final class RawFrameReader implements AutoCloseable {
    private IBinder owner;
    private long epoch=-1;
    private SharedMemory memory;
    private ByteBuffer mapping;
    @SuppressWarnings("deprecation")
    public synchronized byte[] read(IDeviceBridge bridge) throws Exception {
        Bundle value=bridge.rawFrameBuffer();
        if(value==null) {close();return null;} // Explicit primary/fake backend compatibility.
        SharedMemory incoming=value.getParcelable("memory");
        if(incoming==null)throw new IllegalStateException("Raw frame memory missing");
        if(bridge.asBinder().queryLocalInterface("com.oas.android.IDeviceBridge")!=null) {
            Parcel parcel=Parcel.obtain();
            try {incoming.writeToParcel(parcel,0);parcel.setDataPosition(0);incoming=SharedMemory.CREATOR.createFromParcel(parcel);}
            finally {parcel.recycle();}
        }
        long next=value.getLong("epoch");
        if(owner!=bridge.asBinder() || next!=epoch || mapping==null) {
            close();memory=incoming;
            try {mapping=memory.mapReadOnly();owner=bridge.asBinder();epoch=next;}
            catch(Exception error) {close();throw error;}
        } else incoming.close();
        return NativeFrames.read(mapping,SystemClock.elapsedRealtime());
    }
    public static Bitmap bitmap(byte[] raw) {
        ByteBuffer header=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if(header.getInt()!=0x4f415352)throw new IllegalArgumentException("Invalid RGB frame");
        int width=header.getInt(),height=header.getInt(),length=header.getInt();
        if(width<=0||height<=0||(long)width*height*3!=length||raw.length!=16+length)throw new IllegalArgumentException("Invalid RGB dimensions");
        int[] pixels=new int[width*height];
        for(int i=0,j=16;i<pixels.length;i++,j+=3)pixels[i]=0xff000000|((raw[j]&255)<<16)|((raw[j+1]&255)<<8)|(raw[j+2]&255);
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }
    @Override public synchronized void close() {
        if(mapping!=null)SharedMemory.unmap(mapping);
        if(memory!=null)memory.close();mapping=null;memory=null;owner=null;epoch=-1;
    }
}
