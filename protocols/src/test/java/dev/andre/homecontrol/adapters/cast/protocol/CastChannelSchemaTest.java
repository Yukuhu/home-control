package dev.andre.homecontrol.adapters.cast.protocol;

import com.google.protobuf.Descriptors;
import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CastChannelSchemaTest {

    @Test
    void fieldNumbersMatchTheCastV2WireFormat() {
        Descriptors.Descriptor descriptor = CastMessage.getDescriptor();

        assertThat(descriptor.findFieldByName("protocol_version").getNumber()).isEqualTo(1);
        assertThat(descriptor.findFieldByName("source_id").getNumber()).isEqualTo(2);
        assertThat(descriptor.findFieldByName("destination_id").getNumber()).isEqualTo(3);
        assertThat(descriptor.findFieldByName("namespace").getNumber()).isEqualTo(4);
        assertThat(descriptor.findFieldByName("payload_type").getNumber()).isEqualTo(5);
        assertThat(descriptor.findFieldByName("payload_utf8").getNumber()).isEqualTo(6);
        assertThat(descriptor.findFieldByName("payload_binary").getNumber()).isEqualTo(7);
        assertThat(CastMessage.ProtocolVersion.CASTV2_1_0.getNumber()).isZero();
        assertThat(CastMessage.PayloadType.STRING.getNumber()).isZero();
        assertThat(CastMessage.PayloadType.BINARY.getNumber()).isEqualTo(1);
    }
}
