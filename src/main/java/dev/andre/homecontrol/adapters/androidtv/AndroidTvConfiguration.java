package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import dev.andre.homecontrol.storage.DataDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.security.SecureRandom;

/** Android TV Remote v2, on unless {@code home-control.androidtv.enabled=false}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.ANDROIDTV)
public class AndroidTvConfiguration {

    /** A configured password is used as it is; otherwise one is generated with the keystore and kept encrypted. */
    @Bean
    public CertificateStore certificateStore(DataDirectory data, AndroidTvProperties properties, DeviceSecrets secrets,
                                             SecureRandom random) {
        Path keystore = data.resolve(DataDirectory.KEYSTORE);
        return new CertificateStore(keystore,
                () -> KeystorePassword.resolve(keystore, properties.keystorePassword(), secrets, random));
    }

    @Bean
    public MdnsDiscovery mdnsDiscovery(MdnsBrowser browser) {
        return new MdnsDiscovery(browser);
    }

    @Bean
    public AndroidTvAdapter androidTvAdapter(CertificateStore certificates, AndroidTvProperties properties,
                                             MdnsDiscovery discovery) {
        return new AndroidTvAdapter(certificates, properties, discovery);
    }

    @Bean
    public PairingService pairingService(CertificateStore certificates, DeviceManager devices, DataDirectory data) {
        return new PairingService(certificates, devices, data);
    }
}
