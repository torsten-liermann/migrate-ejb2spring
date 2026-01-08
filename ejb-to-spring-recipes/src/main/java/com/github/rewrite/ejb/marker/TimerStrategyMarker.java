package com.github.rewrite.ejb.marker;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import lombok.Value;
import org.openrewrite.marker.Marker;

import java.util.UUID;

@Value
public class TimerStrategyMarker implements Marker {
    UUID id;
    ProjectConfiguration.TimerStrategy strategy;
    String reason;

    @Override
    public Marker withId(UUID id) {
        return new TimerStrategyMarker(id, strategy, reason);
    }
}
