package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Android TV Remote v2: the Shield and every other Android TV / Google TV box. */
@Component
public class AndroidTvAdapter implements DeviceAdapter {

    public static final String ID = AndroidTvSettings.ADAPTER_ID;

    private final CertificateStore certificates;
    private final AndroidTvProperties properties;
    private final MdnsDiscovery discovery;

    public AndroidTvAdapter(CertificateStore certificates, AndroidTvProperties properties,
                            MdnsDiscovery discovery) {
        this.certificates = certificates;
        this.properties = properties;
        this.discovery = discovery;
    }

    /** A wrong keystore password must stop startup loudly, not look like "no devices paired". */
    @PostConstruct
    public void verifyCredentialStore() {
        certificates.verifyReadable();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.ANDROID_TV;
    }

    /** The keystore alias is the device id; moving the entry would orphan the pairing. */
    @Override
    public boolean credentialsBoundToDeviceId() {
        return true;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        Optional<ClientCertificate> credential = certificates.load(AndroidTvSettings.certificateAlias(device));
        if (credential.isEmpty()) {
            // Load-only: pairing is the only flow allowed to create a credential (v0.3).
            DeviceState unpaired = DeviceState.unpaired();
            onChange.accept(unpaired);
            return new UnpairedHandle(unpaired);
        }
        AndroidTvSession session = new AndroidTvSession(device, credential.get(), properties, onChange);
        session.start();
        return session;
    }

    @Override
    public void forget(Device device) {
        certificates.delete(AndroidTvSettings.certificateAlias(device));
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return discovery.devices();
    }

    /** A registered device whose credential is gone: visible, controllable only after re-pairing. */
    private record UnpairedHandle(DeviceState state) implements DeviceHandle {

        @Override
        public void execute(Action action) {
            throw new DeviceOfflineException("This device must be paired again before it can be controlled");
        }

        @Override
        public void close() {
        }
    }
}
