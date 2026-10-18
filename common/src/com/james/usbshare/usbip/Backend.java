package com.james.usbshare.usbip;

import java.util.List;

/** Platform side of the server: enumerates and opens real (or fake) USB devices. */
public interface Backend {

    List<Proto.DeviceInfo> listDevices();

    /**
     * Opens and claims the device for exclusive use by one client.
     * May block (e.g. while the user answers a permission dialog).
     * @throws Exception with a human-readable message when it cannot be opened.
     */
    Attached attach(String busid) throws Exception;

    /** Called by the server for log lines. */
    void log(String msg);

    /** Result callback for one URB. Called exactly once per submit (from any thread). */
    interface Completion {
        /**
         * @param status 0 or negative errno
         * @param inData data read for IN transfers (may be larger than actual), null for OUT
         * @param actualLength bytes transferred
         */
        void done(int status, byte[] inData, int actualLength);
    }

    interface Attached {
        Proto.DeviceInfo info();

        /** Starts a transfer; must not block for long. */
        void submit(Proto.Cmd cmd, Completion done);

        /** Best-effort cancel of an in-flight URB. Its completion will be ignored by the server. */
        void cancel(int seqnum);

        /** Releases the device. Pending completions may still arrive and will be ignored. */
        void close();

        /** True once the physical device was unplugged or failed. */
        boolean isGone();
    }
}
