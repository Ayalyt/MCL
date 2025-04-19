package org.example.serialization.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
public class DTAFormat {
    private String name;
    private List<String> clocks;
    private List<String> actions;
    private List<LocationFormat> locations;
    private List<TransitionFormat> transitions;
    @JsonProperty("init_location")
    private String initLocation;
}