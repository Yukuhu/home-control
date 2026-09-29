package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** A {@link StubAdapter} whose {@code connect} waits until {@link #release} for the one device it is told to hold. */
class BlockingAdapter extends StubAdapter {

    final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final String heldDeviceId;

    BlockingAdapter(String id, String heldDeviceId) {
        super(id, DeviceKind.ANDROID_TV, false, false);
        this.heldDeviceId = heldDeviceId;
    }

    void release() {
        released.countDown();
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        if (device.id().equals(heldDeviceId)) {
            entered.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return super.connect(device, onChange);
    }
}
