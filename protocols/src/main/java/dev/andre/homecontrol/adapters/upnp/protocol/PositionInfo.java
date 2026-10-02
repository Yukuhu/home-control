package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Map;

/** A GetPositionInfo answer. The track URI and metadata may carry a credential; never printed. */
public record PositionInfo(String trackUri, String trackMetadata, Double positionSeconds, Double durationSeconds) {

    public static PositionInfo from(Map<String, String> answer) {
        Double duration = UpnpTime.seconds(answer.get("TrackDuration"));
        return new PositionInfo(answer.getOrDefault("TrackURI", "").strip(), answer.getOrDefault("TrackMetaData", ""),
                UpnpTime.seconds(answer.get("RelTime")), duration != null && duration > 0 ? duration : null);
    }

    @Override
    public String toString() {
        return "PositionInfo[position=" + positionSeconds + ", duration=" + durationSeconds + "]";
    }
}
