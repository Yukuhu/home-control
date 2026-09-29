package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static dev.andre.homecontrol.testsupport.Planners.unroutableReason;
import static org.assertj.core.api.Assertions.assertThat;

/** What a person reads when nothing routes a workflow Cast reference. */
class WorkflowCastRefTest {

    @Test
    void aWorkflowReferenceNeedsACastReceiver() {
        assertThat(unroutableReason(new WorkflowCastRef("wf", 1, "entry"), EnumSet.noneOf(Capability.class)))
                .isEqualTo("this device is not a Cast receiver");
    }
}
