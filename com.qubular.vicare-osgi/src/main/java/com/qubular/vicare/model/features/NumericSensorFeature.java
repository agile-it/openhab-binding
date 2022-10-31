package com.qubular.vicare.model.features;

import com.qubular.vicare.model.*;
import com.qubular.vicare.model.values.BooleanValue;
import com.qubular.vicare.model.values.DimensionalValue;
import com.qubular.vicare.model.values.StatusValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NumericSensorFeature extends Feature {
    private final Map<String, Value> properties;
    private final String propertyName;

    public NumericSensorFeature(String name, String propertyName, List<CommandDescriptor> commands, DimensionalValue value, StatusValue status, Boolean active) {
        super(name, commands);
        properties = new HashMap<>();
        properties.put(propertyName, value);
        properties.put("status", status);
        if (active != null) {
            properties.put("active", BooleanValue.valueOf(active));
        }
        this.propertyName = propertyName;
    }

    public NumericSensorFeature(String name, String propertyName, DimensionalValue value, StatusValue status, Boolean active) {
        this(name, propertyName, Collections.emptyList(), value, status, active);
    }

    public DimensionalValue getValue() {
        return (DimensionalValue) properties.get(propertyName);
    }

    public StatusValue getStatus() {
        return (StatusValue) properties.get("status");
    }

    public Boolean isActive() {
        BooleanValue active = (BooleanValue) properties.get("active");
        return active == null ? null : active.getValue();
    }

    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public Map<String, ? extends Value> getProperties() {
        return properties;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
