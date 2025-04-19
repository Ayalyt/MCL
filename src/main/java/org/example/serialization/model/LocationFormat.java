package org.example.serialization.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class LocationFormat {
    private String name;
    private Map<String, String> invariant; // 暂时用不着
    private boolean accepting;
}