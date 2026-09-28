package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import dev.andre.homecontrol.storage.DataDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Android TV Remote v2, on unless {@code home-control.androidtv.enabled=false}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.ANDROIDTV)
public class AndroidTvConfiguration {

    @Bean
    public CertificateStore certificateStore(DataDirectory data, AndroidTvProperties properties) {
        return new CertificateStore(data.resolve(DataDirectory.KEYSTORE), properties.keystorePassword().toCharArray());
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
