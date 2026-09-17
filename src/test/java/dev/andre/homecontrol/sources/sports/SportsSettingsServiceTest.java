package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SportsSettingsServiceTest {

    @Test
    void loadsLazilyOnceAndCaches() {
        JsonFileSportsStore store = mock(JsonFileSportsStore.class);
        given(store.load()).willReturn(SportsSettings.empty());
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        SportsSettingsService service = new SportsSettingsService(store, events);

        service.current();
        service.current();

        verify(store, times(1)).load();
    }

    @Test
    void updateSavesAndPublishesAfterwards() {
        JsonFileSportsStore store = mock(JsonFileSportsStore.class);
        given(store.load()).willReturn(SportsSettings.empty());
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        SportsSettingsService service = new SportsSettingsService(store, events);

        SportsSettings result = service.update(s -> s.withTimeZone("Europe/London"));

        assertThat(result.timeZone()).isEqualTo("Europe/London");
        InOrderCheck.verifySaveThenPublish(store, events, result);
    }

    @Test
    void aFailingSaveKeepsTheOldStateAndPublishesNothing() {
        JsonFileSportsStore store = mock(JsonFileSportsStore.class);
        given(store.load()).willReturn(SportsSettings.empty());
        org.mockito.Mockito.doThrow(new StorageException("disk full", null)).when(store).save(any());
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        SportsSettingsService service = new SportsSettingsService(store, events);

        assertThatThrownBy(() -> service.update(s -> s.withTimeZone("Europe/London")))
                .isInstanceOf(StorageException.class);

        assertThat(service.current()).isEqualTo(SportsSettings.empty());
        verify(events, times(0)).publishEvent(any());
    }

    private static final class InOrderCheck {
        static void verifySaveThenPublish(JsonFileSportsStore store, ApplicationEventPublisher events, SportsSettings result) {
            var order = inOrder(store, events);
            order.verify(store).save(result);
            order.verify(events).publishEvent(new ContentChangedEvent("sports"));
        }
    }
}
