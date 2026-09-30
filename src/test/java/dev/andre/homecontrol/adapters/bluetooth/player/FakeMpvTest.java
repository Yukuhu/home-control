package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeMpvTest {

    @TempDir
    Path dir;

    @Test
    void finishesRegistrationBeforeHandlingCommands() throws Exception {
        Path socket = dir.resolve("mpv.sock");
        CompletableFuture<FakeMpv> registering = new CompletableFuture<>();
        CompletableFuture<Void> finishRegistration = new CompletableFuture<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> FakeMpv.serve(socket, FakeMpv.Options.defaults(), 50, line -> { }, fake -> {
                registering.complete(fake);
                finishRegistration.join();
            }));
            try (FakeMpv fake = registering.get(2, TimeUnit.SECONDS);
                 MpvIpc ipc = MpvIpc.connect(socket, Duration.ofSeconds(2), () -> true, new MpvIpc.EventListener() {
                     @Override
                     public void onEvent(JsonNode event) {
                         // This test observes command replies, not playback events.
                     }

                     @Override
                     public void onClosed() {
                         // Closing is handled by the try-with-resources block.
                     }
                 })) {
                assertThatThrownBy(() -> ipc.command(Duration.ofMillis(200), "get_property", "volume"))
                        .isInstanceOf(IOException.class).hasMessageContaining("did not answer get_property");

                finishRegistration.complete(null);
                assertThat(server.get(2, TimeUnit.SECONDS)).isSameAs(fake);
                assertThat(ipc.command(Duration.ofSeconds(2), "get_property", "volume").asDouble(-1)).isEqualTo(50.0);
            } finally {
                finishRegistration.complete(null);
                server.cancel(true);
            }
        }
    }
}
