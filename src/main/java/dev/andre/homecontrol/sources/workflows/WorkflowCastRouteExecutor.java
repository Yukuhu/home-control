package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.CastLoads;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.device.DeviceManager;

/** Resolves fresh credentials only on Play, then serializes final dispatch with definition mutations. */
public final class WorkflowCastRouteExecutor implements RouteExecutor {
    private final WorkflowStore store;
    private final WorkflowRunner runner;
    private final DeviceManager devices;
    private final RailPreferences preferences;

    public WorkflowCastRouteExecutor(WorkflowStore store, WorkflowRunner runner, DeviceManager devices,
                                     RailPreferences preferences) {
        this.store = store;
        this.runner = runner;
        this.devices = devices;
        this.preferences = preferences;
    }

    @Override public boolean executes(Route route) { return route instanceof Route.WorkflowCast; }

    @Override public void execute(Route route, Device device) {
        if (!(route instanceof Route.WorkflowCast workflow)) throw new IllegalArgumentException("Not a workflow route");
        try {
            requireSource();
            requireCast(device);
            var definition = store.find(workflow.workflowId())
                    .filter(d -> d.draft().enabled() && d.revision() == workflow.revision())
                    .orElseThrow(() -> new WorkflowException(WorkflowException.Stage.WORKFLOW,
                            "Workflow changed; reopen this item"));
            // resolve owns media-address validation; no fetch or DNS work happens under the store lock.
            var resolved = runner.resolve(definition, workflow.entryKey());
            var stream = new PlayableRef.StreamUrl(resolved.url(), resolved.mimeType());
            var action = new Action.CastLoad(CastLoads.DEFAULT_MEDIA_RECEIVER,
                    CastLoads.defaultMediaReceiver(stream, resolved.title()));
            store.ifCurrent(workflow.workflowId(), workflow.revision(), () -> {
                requireSource();
                requireCast(device);
                try {
                    devices.execute(device.id(), action);
                } catch (ActionFailedException refused) {
                    // A receiver can echo the secret media URL in its error reason.
                    throw new ActionFailedException("Workflow: Cast receiver could not start playback");
                }
            });
        } catch (WorkflowException failure) {
            throw new ActionFailedException(failure.getMessage());
        }
    }

    private void requireSource() {
        if (!preferences.sourceEnabled(WorkflowContentSource.ID)) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "source is disabled");
        }
    }

    private void requireCast(Device device) {
        if (!devices.capabilities(device.id()).contains(Capability.CAST_RECEIVER)) {
            throw new UnsupportedActionException("This device is not a Cast receiver");
        }
    }
}
