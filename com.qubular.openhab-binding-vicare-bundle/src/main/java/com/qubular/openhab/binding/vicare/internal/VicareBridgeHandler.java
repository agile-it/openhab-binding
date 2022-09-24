package com.qubular.openhab.binding.vicare.internal;

import com.qubular.openhab.binding.vicare.internal.configuration.SimpleConfiguration;
import com.qubular.vicare.AuthenticationException;
import com.qubular.vicare.VicareConfiguration;
import com.qubular.vicare.VicareService;
import com.qubular.vicare.model.Feature;
import de.jollyday.util.Cache;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.qubular.openhab.binding.vicare.internal.VicareConstants.PROPERTY_DEVICE_UNIQUE_ID;
import static com.qubular.openhab.binding.vicare.internal.VicareConstants.PROPERTY_FEATURE_NAME;
import static com.qubular.openhab.binding.vicare.internal.VicareUtil.decodeThingUniqueId;

public class VicareBridgeHandler extends BaseBridgeHandler {
    private static final Logger logger = LoggerFactory.getLogger(VicareBridgeHandler.class);
    public static final int POLLING_STARTUP_DELAY_SECS = 10;
    private final ThingRegistry thingRegistry;
    private final VicareConfiguration config;

    private final VicareService vicareService;
    private final Map<String, CachedResponse> cachedResponses = new HashMap<>();

    private static class CachedResponse {
        final List<Feature> response;
        final Instant responseTimestamp;

        public CachedResponse(List<Feature> response, Instant responseTimestamp) {
            this.response = response;
            this.responseTimestamp = responseTimestamp;
        }
    }

    private static final int REQUEST_INTERVAL_SECS = 90;

    private ScheduledFuture<?> featurePollingJob;

    /**
     * @param thingRegistry
     * @param bridge
     * @see BaseThingHandler
     */
    public VicareBridgeHandler(VicareService vicareService,
                               ThingRegistry thingRegistry,
                               Bridge bridge,
                               VicareConfiguration config) {
        super(bridge);
        this.vicareService = vicareService;
        this.thingRegistry = thingRegistry;
        this.config = config;
        ((SimpleConfiguration)config).setConfigurationParameters(getConfig().getProperties());
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        featurePollingJob = scheduler.scheduleAtFixedRate(featurePoller(), POLLING_STARTUP_DELAY_SECS, getPollingInterval(), TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        if (featurePollingJob != null) {
            featurePollingJob.cancel(false);
        }
        super.dispose();
    }

    private int getPollingInterval() {
        BigDecimal pollingInterval = (BigDecimal) getConfig().getProperties().get("pollingInterval");
        return pollingInterval == null ? REQUEST_INTERVAL_SECS : pollingInterval.intValue();
    }

    @Override
    public void updateStatus(ThingStatus status) {
        super.updateStatus(status);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

    }

    private Runnable featurePoller() {
        return () -> {
            for (Thing thing : getThing().getThings()) {
                VicareDeviceThingHandler handler = (VicareDeviceThingHandler) thing.getHandler();
                for (Channel channel : thing.getChannels()) {
                    handler.handleCommand(channel.getUID(), RefreshType.REFRESH);
                }
            }
        };
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        super.handleConfigurationUpdate(configurationParameters);
        ((SimpleConfiguration) config).setConfigurationParameters(configurationParameters);
    }

    public Optional<Feature> handleBridgedDeviceCommand(ChannelUID channelUID, Command command) throws AuthenticationException, IOException {
        logger.debug("Handling command {} for channel {} from thing {}", command, channelUID, channelUID.getThingUID());
        Thing targetThing = thingRegistry.get(channelUID.getThingUID());
        if (command instanceof RefreshType) {
            try {
                Channel channel = targetThing.getChannel(channelUID);
                List<Feature> features = getFeatures(targetThing);
                String featureName = channel.getProperties().get(PROPERTY_FEATURE_NAME);
                if (getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
                return features.stream()
                        .filter(f -> f.getName().equals(featureName))
                        .findAny();
            } catch (AuthenticationException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Unable to authenticate with Viessmann API: " + e.getMessage());
                throw e;
            } catch (IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Unable to communicate with Viessmann API: " + e.getMessage());
                throw e;
            }
        } else {
            return Optional.empty();
        }
    }

    private synchronized List<Feature> getFeatures(Thing thing) throws AuthenticationException, IOException {
        Instant now = Instant.now();
        String key = thing.getUID().getId();
        CachedResponse response = cachedResponses.get(key);
        if (response != null && now.isBefore(response.responseTimestamp.plusSeconds(getPollingInterval() - 1))) {
            return response.response;
        }

        VicareUtil.IGD s = decodeThingUniqueId(thing.getProperties().get(PROPERTY_DEVICE_UNIQUE_ID));
        List<Feature> features = vicareService.getFeatures(s.installationId, s.gatewaySerial, s.deviceId);
        cachedResponses.put(key, new CachedResponse(features, now));
        return features;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(VicareDiscoveryService.class);
    }

    VicareService getVicareService() {
        return vicareService;
    }
}
