package com.qubular.openhab.binding.vicare.internal;

import com.qubular.openhab.binding.vicare.VicareServiceProvider;
import com.qubular.vicare.AuthenticationException;
import com.qubular.vicare.CommandFailureException;
import com.qubular.vicare.VicareService;
import com.qubular.vicare.model.CommandDescriptor;
import com.qubular.vicare.model.values.DimensionalValue;
import com.qubular.vicare.model.Feature;
import com.qubular.vicare.model.values.StatusValue;
import com.qubular.vicare.model.features.*;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.qubular.openhab.binding.vicare.internal.DeviceDiscoveryEvent.generateTopic;
import static com.qubular.openhab.binding.vicare.internal.VicareConstants.*;
import static com.qubular.openhab.binding.vicare.internal.VicareUtil.decodeThingUniqueId;
import static com.qubular.openhab.binding.vicare.internal.VicareUtil.escapeUIDSegment;
import static java.lang.String.format;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toMap;
import static org.osgi.service.event.EventConstants.EVENT_TOPIC;

public class VicareDeviceThingHandler extends BaseThingHandler {
    private static final Map<ConsumptionFeature.Stat, String> CONSUMPTION_CHANNEL_NAMES_BY_STAT = Map.of(
            ConsumptionFeature.Stat.CURRENT_DAY, "currentDay",
            ConsumptionFeature.Stat.CURRENT_WEEK, "currentWeek",
            ConsumptionFeature.Stat.CURRENT_MONTH, "currentMonth",
            ConsumptionFeature.Stat.CURRENT_YEAR, "currentYear",
            ConsumptionFeature.Stat.LAST_SEVEN_DAYS, "lastSevenDays",
            ConsumptionFeature.Stat.PREVIOUS_DAY, "previousDay",
            ConsumptionFeature.Stat.PREVIOUS_WEEK, "previousWeek",
            ConsumptionFeature.Stat.PREVIOUS_MONTH, "previousMonth",
            ConsumptionFeature.Stat.PREVIOUS_YEAR, "previousYear"
    );
    private static final Set<String> DEVICE_PROPERTIES = Set.of(
            PROPERTY_DEVICE_TYPE,
            PROPERTY_GATEWAY_SERIAL,
            PROPERTY_DEVICE_UNIQUE_ID,
            PROPERTY_MODEL_ID,
            PROPERTY_BOILER_SERIAL
    );

    private static final Logger logger = LoggerFactory.getLogger(VicareDeviceThingHandler.class);
    private final VicareService vicareService;
    private final VicareServiceProvider vicareServiceProvider;
    private ServiceRegistration<EventHandler> discoveryListenerRegistration;

    private static final Map<String, ConsumptionFeature.Stat> CONSUMPTION_STATS_BY_CHANNEL_NAME =
            CONSUMPTION_CHANNEL_NAMES_BY_STAT.entrySet().stream()
                    .collect(toMap(Map.Entry::getValue, Map.Entry::getKey));

    public VicareDeviceThingHandler(VicareServiceProvider vicareServiceProvider,
                                    Thing thing,
                                    VicareService vicareService) {
        super(thing);
        this.vicareServiceProvider = vicareServiceProvider;
        logger.info("Creating handler for {}", thing.getUID());
        this.vicareService = vicareService;
    }

    @Override
    public void dispose() {
        if (discoveryListenerRegistration != null) {
            discoveryListenerRegistration.unregister();
            discoveryListenerRegistration = null;
        }
        super.dispose();
    }

    /**
     * Create the channels
     */
    @Override
    public void initialize() {
        String deviceUniqueId = getDeviceUniqueId(thing);
        VicareUtil.IGD igd = decodeThingUniqueId(deviceUniqueId);
        Hashtable<String, Object> subscriptionProps = new Hashtable<>();
        subscriptionProps.put(EVENT_TOPIC, generateTopic(thing.getUID()));
        discoveryListenerRegistration = vicareServiceProvider.getBundleContext().registerService(
                EventHandler.class, new DiscoveryEventHandler(), subscriptionProps);
        CompletableFuture.runAsync(() -> {
            try {
                List<Feature> features = vicareService.getFeatures(igd.installationId, igd.gatewaySerial, igd.deviceId);
                List<Channel> channels = new ArrayList<>();
                Map<String, String> newPropValues = new HashMap<>(getThing().getProperties());
                for (Feature feature : features) {
                    feature.accept(new Feature.Visitor() {
                        @Override
                        public void visit(ConsumptionFeature f) {
                            String id = escapeUIDSegment(f.getName());
                            CONSUMPTION_CHANNEL_NAMES_BY_STAT.entrySet()
                                    .stream()
                                    .filter(e -> f.getConsumption(e.getKey()).isPresent())
                                    .forEach(e -> channels.add(consumptionChannel(id, f, e.getValue())));
                        }

                        private Channel consumptionChannel(String id, Feature feature, String statName) {
                            return ChannelBuilder.create(new ChannelUID(thing.getUID(), id + "_" + statName))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(id + "_" + statName)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, feature.getName(),
                                            PROPERTY_PROP_NAME, statName))
                                    .build();
                        }

                        @Override
                        public void visit(NumericSensorFeature f) {
                            String id = escapeUIDSegment(f.getName());
                            Map<String, String> props = new HashMap<>();
                            props.put(PROPERTY_FEATURE_NAME, feature.getName());
                            maybeAddPropertiesForSetter(f, f.getPropertyName(), props);
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), id))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(id)))
                                    .withProperties(props)
                                    .build());
                            if (f.getStatus() != null && !StatusValue.NA.equals(f.getStatus())) {
                                String statusId = id + "_status";
                                channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), statusId))
                                        .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(statusId)))
                                        .withProperties(Map.of(PROPERTY_FEATURE_NAME, feature.getName(),
                                                PROPERTY_PROP_NAME, "status"))
                                        .build());
                            } else if (f.isActive() != null) {
                                String activeId = id + "_active";
                                channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), activeId))
                                        .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(activeId)))
                                        .withProperties(Map.of(PROPERTY_FEATURE_NAME, feature.getName(),
                                                PROPERTY_PROP_NAME, "active"))
                                        .build());
                            }
                        }

                        @Override
                        public void visit(MultiValueFeature f) {
                            f.getValues().forEach((name, value) -> {
                                Map<String, String> props = new HashMap<>();
                                props.put(PROPERTY_FEATURE_NAME, feature.getName());
                                props.put(PROPERTY_PROP_NAME, name);
                                maybeAddPropertiesForSetter(f, name, props);
                                String id = escapeUIDSegment(f.getName() + "_" + name);
                                channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), id))
                                        .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(id)))
                                        .withProperties(props)
                                        .build());
                            });
                        }

                        private void maybeAddPropertiesForSetter(Feature f, String name, Map<String, String> props) {
                            Optional<CommandDescriptor> setter = f.getCommands().stream()
                                    .filter(cd -> cd.getName().equalsIgnoreCase("set" + name))
                                    .findFirst();
                            if (!setter.isPresent() && "value".equals(name)) {
                                setter = f.getCommands().stream()
                                        .filter(cd -> cd.getParams().size() == 1)
                                        .findFirst();
                            }
                            if (setter.isPresent()) {
                                CommandDescriptor command = setter.get();
                                props.put(PROPERTY_COMMAND_NAME, command.getName());
                                props.put(PROPERTY_PARAM_NAME, command.getParams().get(0).getName());
                            }
                        }

                        @Override
                        public void visit(StatusSensorFeature f) {
                            String activeId = escapeUIDSegment(f.getName() + "_active");
                            String statusId = escapeUIDSegment(f.getName() + "_status");
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), activeId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(activeId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, f.getName(),
                                            PROPERTY_PROP_NAME, "active"))
                                    .build());
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), statusId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(statusId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, f.getName(),
                                            PROPERTY_PROP_NAME, "status"))
                                    .build());
                        }

                        @Override
                        public void visit(TextFeature f) {
                            String id = escapeUIDSegment(f.getName());
                            ChannelUID channelUID = new ChannelUID(thing.getUID(), id);
                            Map<String, String> props = new HashMap<>();
                            props.put(PROPERTY_FEATURE_NAME, f.getName());
                            if (f.getCommands().size() == 1 &&
                                f.getCommands().get(0).getParams().size() == 1) {
                                CommandDescriptor command = feature.getCommands().get(0);
                                props.put(PROPERTY_COMMAND_NAME, command.getName());
                                props.put(PROPERTY_PARAM_NAME, command.getParams().get(0).getName());
                            }
                            channels.add(ChannelBuilder.create(channelUID)
                                                 .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(id)))
                                                 .withProperties(props)
                                                 .build());
                        }

                        @Override
                        public void visit(CurveFeature f) {
                            String slopeId = escapeUIDSegment(f.getName() + "_slope");
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), slopeId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(slopeId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, f.getName(),
                                            PROPERTY_PROP_NAME, "slope"))
                                    .build());
                            String shiftId = escapeUIDSegment(f.getName() + "_shift");
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), shiftId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(shiftId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, f.getName(),
                                            PROPERTY_PROP_NAME, "shift"))
                                    .build());
                        }

                        @Override
                        public void visit(DatePeriodFeature datePeriodFeature) {
                            String activeId = escapeUIDSegment(feature.getName() + "_active");
                            String startId = escapeUIDSegment(feature.getName() + "_start");
                            String endId = escapeUIDSegment(feature.getName() + "_end");
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), activeId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(activeId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, feature.getName(),
                                            PROPERTY_PROP_NAME, "active"))
                                    .build());
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), startId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(startId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, feature.getName(),
                                            PROPERTY_PROP_NAME, "start"))
                                    .build());
                            channels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), endId))
                                    .withType(new ChannelTypeUID(BINDING_ID, channelIdToChannelType(endId)))
                                    .withProperties(Map.of(PROPERTY_FEATURE_NAME, feature.getName(),
                                            PROPERTY_PROP_NAME, "end"))
                                    .build());
                        }
                    });
                }
                if (!channels.isEmpty() || !newPropValues.isEmpty()) {
                    ThingBuilder thingBuilder = editThing();
                    if (!newPropValues.isEmpty()) {
                        thingBuilder = thingBuilder.withProperties(newPropValues);
                    }
                    if (!channels.isEmpty()) {
                        var sortedChannels = channels.stream()
                                .sorted(Comparator.comparing(c -> c.getUID().getId()))
                                .collect(Collectors.toList());
                        thingBuilder = thingBuilder.withChannels(sortedChannels);
                    }
                    updateThing(thingBuilder.build());
                }
                updateStatus(ThingStatus.ONLINE);
            } catch (AuthenticationException e) {
                logger.warn("Unable to authenticate while fetching device features", e);
                updateStatus(ThingStatus.OFFLINE,
                        ThingStatusDetail.COMMUNICATION_ERROR,
                        "Authentication problem fetching device features: " + e.getMessage());
            } catch (IOException e) {
                logger.warn("IOException while fetching device features", e);
                updateStatus(ThingStatus.OFFLINE,
                        ThingStatusDetail.COMMUNICATION_ERROR,
                        "Communication problem fetching device features: " + e.getMessage());
            }

        }).exceptionally(t -> { logger.warn("Unexpected error initializing Thing", t); return null; });
    }

    static String getDeviceUniqueId(Thing t) {
        // Hidden configuration option for testing purposes
        String deviceUniqueId = (String) t.getConfiguration().get("deviceUniqueId");
        if (deviceUniqueId != null) {
            return deviceUniqueId;
        }
        return t.getProperties().get(PROPERTY_DEVICE_UNIQUE_ID);
    }

    private String channelIdToChannelType(String channelId) {
        return channelId.replaceAll("(_[\\d+])(_?)", "$2");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        CompletableFuture.runAsync(() -> syncHandleCommand(channelUID, command))
                .exceptionally(t -> {
                    logger.warn(format("Unexpected exception handling command %s for channel %s", command, channelUID), t);
                    return null;
                });
    }

    public void syncHandleCommand(ChannelUID channelUID, Command command) {
        try {
            Optional<Feature> feature = ((VicareBridgeHandler) getBridge().getHandler()).handleBridgedDeviceCommand(channelUID, command);
            feature.ifPresent(f -> {
                f.accept(new Feature.Visitor() {
                    @Override
                    public void visit(ConsumptionFeature f) {
                        Channel channel = getThing().getChannel(channelUID);
                        String statName = channel.getProperties().get(PROPERTY_PROP_NAME);
                        updateConsumptionStat(() -> f.getConsumption(CONSUMPTION_STATS_BY_CHANNEL_NAME.get(statName))
                                .map(DimensionalValue::getValue).orElse(0.0));
                    }

                    private void updateConsumptionStat(Supplier<Double> valueSupplier) {
                        updateState(channelUID, new DecimalType(valueSupplier.get()));
                    }

                    @Override
                    public void visit(NumericSensorFeature f) {
                        Channel channel = getThing().getChannel(channelUID);
                        String propName = channel.getProperties().get(PROPERTY_PROP_NAME);
                        if ("active".equals(propName)) {
                            updateState(channelUID, f.isActive() ? OnOffType.ON : OnOffType.OFF);
                        } else if ("status".equals(propName)) {
                            updateState(channelUID, StringType.valueOf(f.getStatus() == null ? null : f.getStatus().getName()));
                        } else {
                            double value = f.getValue().getValue();
                            updateState(channelUID, new DecimalType(value));
                        }
                    }

                    @Override
                    public void visit(MultiValueFeature f) {
                        Channel channel = getThing().getChannel(channelUID);
                        String statisticName = channel.getProperties().get(PROPERTY_PROP_NAME);
                        double value = f.getValues().get(statisticName).getValue();
                        updateState(channelUID, new DecimalType(value));
                    }

                    @Override
                    public void visit(StatusSensorFeature f) {
                        Channel channel = getThing().getChannel(channelUID);
                        String propertyName = channel.getProperties().get(PROPERTY_PROP_NAME);
                        State state = null;
                        if ("active".equals(propertyName)) {
                            if (f.isActive() == null) {
                                state = UnDefType.UNDEF;
                            } else {
                                state = f.isActive() ? OnOffType.ON : OnOffType.OFF;
                            }
                        } else if ("status".equals(propertyName)) {
                            state = StringType.valueOf(f.getStatus() == null ? null : f.getStatus().getName());
                        }
                        updateState(channelUID, state);
                    }

                    @Override
                    public void visit(TextFeature f) {
                        logger.info("Update {} with {}", channelUID, f.getValue());
                        updateState(channelUID, new StringType(f.getValue()));
                    }

                    @Override
                    public void visit(CurveFeature f) {
                        Channel channel = getThing().getChannel(channelUID);
                        switch (channel.getProperties().get(PROPERTY_PROP_NAME)) {
                            case "slope":
                                State slopeState = new DecimalType(f.getSlope().getValue());
                                updateState(channelUID, slopeState);
                                break;
                            case "shift":
                                State shiftState = new DecimalType(f.getShift().getValue());
                                updateState(channelUID, shiftState);
                                break;
                        }
                    }

                    @Override
                    public void visit(DatePeriodFeature datePeriodFeature) {
                        Channel channel = getThing().getChannel(channelUID);
                        State newState = UnDefType.UNDEF;
                        switch (channel.getProperties().get(PROPERTY_PROP_NAME)) {
                            case "active":
                                newState = StatusValue.ON.equals(datePeriodFeature.getActive()) ? OnOffType.ON : OnOffType.OFF;
                                break;
                            case "start":
                                LocalDate startDate = datePeriodFeature.getStart();
                                if (startDate != null) {
                                    newState = new DateTimeType(startDate.atStartOfDay(ZoneId.systemDefault()));
                                }
                                break;
                            case "end":
                                LocalDate endDate = datePeriodFeature.getEnd();
                                if (endDate != null) {
                                    newState = new DateTimeType(endDate.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()));
                                }
                                break;
                        }
                        updateState(channelUID, newState);
                    }
                });
            });
            if (thing.getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (AuthenticationException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Unable to authenticate with Viessmann API: " + e.getMessage());
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Unable to communicate with Viessmann API: " + e.getMessage());
        } catch (CommandFailureException e) {
            logger.warn("Unable to perform command {} for channel {} {}: {}", command, channelUID, e.getReason(), e.getMessage());
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return singleton(DeviceDynamicCommandDescriptionProvider.class);
    }

    protected @Nullable VicareBridgeHandler getBridgeHandler() {
        return (VicareBridgeHandler) getBridge().getHandler();
    }

    private class DiscoveryEventHandler implements EventHandler {
        @Override
        public void handleEvent(Event event) {
            Map<String, String> oldProps = editProperties();

            Set<String> propsToWipe = new HashSet<>(oldProps.keySet());
            propsToWipe.removeAll(DEVICE_PROPERTIES);
            propsToWipe.forEach(key -> oldProps.put(key, null));

            Arrays.stream(event.getPropertyNames()).forEach(name -> {
                if (!EVENT_TOPIC.equals(name)) {
                    Object value = event.getProperty(name);
                    if (value instanceof String) {
                        oldProps.put(name, (String) value);
                    }
                }
            });

            updateProperties(oldProps);
        }
    }
}
