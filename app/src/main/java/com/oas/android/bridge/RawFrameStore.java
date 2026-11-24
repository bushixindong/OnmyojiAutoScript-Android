// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.os.SharedMemory;
import android.system.OsConstants;
import java.nio.ByteBuffer;

/** One writer, three reusable RGB slots. Binder recipients can only map the store read-only. */
final class RawFrameStore implements AutoCloseable {
    final SharedMemory memory;
    final ByteBuffer mapping;
    RawFrameStore() throws Exception {
        memory=SharedMemory.create("OASA RGB frames",NativeFrames.storeSize());
        mapping=memory.mapReadWrite();NativeFrames.initialize(mapping);
        if(!memory.setProtect(OsConstants.PROT_READ)) {close();throw new IllegalStateException("Cannot protect shared frame memory");}
    }
    @Override public void close() { SharedMemory.unmap(mapping);memory.close(); }
}
