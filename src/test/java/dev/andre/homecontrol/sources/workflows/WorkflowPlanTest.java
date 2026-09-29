package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static dev.andre.homecontrol.sources.workflows.WorkflowPlan.Phase.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowPlanTest {
    private static final URI BASE = URI.create("https://api.example");

    @Test void phasesFollowWhatUsesEachCall() {
        var plan = WorkflowPlan.of(WorkflowFixtures.chain(BASE));
        assertThat(plan.phase("list")).isEqualTo(REFRESH_AND_PLAY);
        assertThat(plan.phase("images")).isEqualTo(REFRESH);
        assertThat(plan.phase("stream")).isEqualTo(PLAY);
        assertThat(REFRESH_AND_PLAY.label()).isEqualTo("Runs at refresh and Play");
    }

    @Test void eachRunExecutesOnlyTheCallsItNeeds() {
        var plan = WorkflowPlan.of(WorkflowFixtures.chain(BASE));
        assertThat(plan.refreshCalls(CallScope.SHARED)).extracting(Call::name).containsExactly("list");
        assertThat(plan.refreshCalls(CallScope.ENTRY)).extracting(Call::name).containsExactly("images");
        assertThat(plan.playCalls(CallScope.SHARED)).extracting(Call::name).containsExactly("list");
        assertThat(plan.playCalls(CallScope.ENTRY)).extracting(Call::name).containsExactly("stream");
        assertThat(plan.dependencies("stream")).containsExactly("list");
        assertThat(plan.dependencies("list")).isEmpty();
        assertThat(plan.refreshEntryValues()).containsExactly("id");
        assertThat(plan.playEntryValues()).containsExactly("id");
        assertThat(plan.variables()).containsExactly("token", "art", "path", "id");
        assertThat(plan.sensitive("token")).isTrue();
        assertThat(plan.sensitive("art")).isFalse();
    }

    @Test void singleTileCallsAllRunAtPlay() {
        var plan = WorkflowPlan.of(WorkflowFixtures.single(BASE));
        assertThat(plan.phase("main")).isEqualTo(PLAY);
        assertThat(plan.refreshCalls(CallScope.SHARED)).isEmpty();
    }

    @Test void aCallCannotUseAValueFromACallFurtherDown() {
        var chain = WorkflowFixtures.chain(BASE);
        var reordered = new ArrayList<>(chain.calls());
        reordered.add(0, reordered.remove(2)); // stream before list
        assertThatThrownBy(() -> WorkflowPlan.of(with(chain, reordered)))
                .isInstanceOf(WorkflowException.class)
                .hasMessage("Workflow: call stream: uses {id}, which is defined by a call further down");
    }

    @Test void aSharedCallCannotUseAnEntryValue() {
        var chain = WorkflowFixtures.chain(BASE);
        var calls = new ArrayList<>(chain.calls());
        var stream = calls.get(2);
        calls.set(2, new Call(stream.name(), CallScope.SHARED, stream.url(), stream.headers(), stream.variables()));
        assertThatThrownBy(() -> WorkflowPlan.of(with(chain, calls)))
                .hasMessage("Workflow: call stream: uses the entry value {id}; make it a per-entry call");
    }

    @Test void aCallCannotUseItsOwnValues() {
        var draft = WorkflowFixtures.singleWith(List.of(new Call("self", CallScope.SHARED,
                "https://api.example/x?t={t}", List.of(), List.of(new Variable("t", "/t", false)))));
        assertThatThrownBy(() -> WorkflowPlan.of(draft))
                .hasMessage("Workflow: call self: uses {t}, which is defined by a call further down");
    }

    @Test void aHeaderPlaceholderMustNameAValue() {
        var draft = WorkflowFixtures.singleWith(List.of(new Call("main", CallScope.SHARED, "https://api.example/x",
                List.of(new Header("Authorization", "Bearer {nothing}")), List.of())));
        assertThatThrownBy(() -> WorkflowPlan.of(draft))
                .hasMessage("Workflow: call main: uses {nothing}, which no call defines");
    }

    @Test void aTileFieldCannotShowASensitiveValue() {
        var chain = WorkflowFixtures.chain(BASE);
        var l = chain.listing();
        var leaking = new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                new Field(null, "token"), l.artwork(), l.variables());
        assertThatThrownBy(() -> WorkflowPlan.of(new WorkflowDraft(chain.name(), true, chain.mode(), chain.kind(),
                chain.calls(), leaking, null, chain.cast())))
                .hasMessage("Workflow: entry subtitle variable {token} is marked sensitive");
    }

    @Test void saveRejectsAnUnusedCallButAStoredOneRunsAtPlay() {
        var chain = WorkflowFixtures.chain(BASE);
        var calls = new ArrayList<>(chain.calls());
        calls.add(new Call("spare", CallScope.SHARED, "https://api.example/spare", List.of(), List.of()));
        var draft = with(chain, calls);
        WorkflowValidator.validateStored(draft);
        assertThatThrownBy(() -> WorkflowValidator.validate(draft)).hasMessage("Workflow: call spare: nothing uses this call");
        var plan = WorkflowPlan.of(draft);
        assertThat(plan.phase("spare")).isEqualTo(UNUSED);
        assertThat(plan.playCalls(CallScope.SHARED)).extracting(Call::name).containsExactly("list", "spare");
    }

    private static WorkflowDraft with(WorkflowDraft d, List<Call> calls) {
        return new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), calls, d.listing(), d.tile(), d.cast());
    }
}
