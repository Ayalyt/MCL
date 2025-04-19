package org.example.serialization.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class TransitionFormat {
    private String source;
    private String action;
    private Map<String, String> guard; // Key: clock name, Value: interval string
    private List<String> reset;
    private String target;
}