package com.qubular.openhab.binding.vicare.internal.configuration;

import com.qubular.vicare.VicareConfiguration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.Collections;
import java.util.Map;

import static java.util.Optional.ofNullable;

@Component(service= VicareConfiguration.class)
public class SimpleConfiguration implements VicareConfiguration {
    private Map<String, Object> configurationParameters = Collections.emptyMap();

    @Activate
    public SimpleConfiguration() {

    }

    @Override
    public String getClientId() {
        return String.valueOf(configurationParameters.get("clientId"));
    }

    @Override
    public String getAccessServerURI() {
        return String.valueOf(ofNullable(configurationParameters.get("accessServerUri")).orElse("https://iam.viessmann.com/idp/v2/token"));
    }

    @Override
    public String getIOTServerURI() {
        return String.valueOf(ofNullable(configurationParameters.get("iotServerUri")).orElse("https://api.viessmann.com/iot/v1/"));
    }

    public void setConfigurationParameters(Map<String, Object> configurationParameters) {
        this.configurationParameters = configurationParameters;
    }
}
