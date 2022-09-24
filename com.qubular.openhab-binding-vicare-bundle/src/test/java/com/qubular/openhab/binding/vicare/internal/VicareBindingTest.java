package com.qubular.openhab.binding.vicare.internal;

import com.qubular.openhab.binding.vicare.internal.configuration.SimpleConfiguration;
import com.qubular.vicare.AuthenticationException;
import com.qubular.vicare.VicareService;
import com.qubular.vicare.model.*;
import com.qubular.vicare.model.features.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryListener;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static com.qubular.openhab.binding.vicare.internal.VicareConstants.*;
import static com.qubular.openhab.binding.vicare.internal.VicareUtil.encodeThingId;
import static com.qubular.openhab.binding.vicare.internal.VicareUtil.encodeThingUniqueId;
import static com.qubular.vicare.model.Device.DEVICE_TYPE_HEATING;
import static java.util.Collections.emptyMap;
import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class VicareBindingTest {
    public static final ThingUID THING_UID_BRIDGE = new ThingUID(THING_TYPE_BRIDGE, UUID.randomUUID().toString());
    public static final long INSTALLATION_ID = 1234L;
    public static final String GATEWAY_SERIAL = "SERIAL_1234";
    public static final String DEVICE_1_ID = "0";
    public static final String DEVICE_2_ID = "1";
    @Mock
    private VicareService vicareService;
    private SimpleConfiguration configuration;
    @Mock
    private ThingRegistry thingRegistry;
    @Mock
    private ConfigurationAdmin configurationAdmin;
    private ComponentContext componentContext;
    private BundleContext bundleContext;

    private ThingHandler bridgeHandler;


    private void simpleHeatingInstallation() throws AuthenticationException, IOException {
        Installation installation = mock(Installation.class);
        Gateway gateway = mock(Gateway.class);
        doReturn(GATEWAY_SERIAL).when(gateway).getSerial();
        Device device = mock(Device.class);
        doReturn(DEVICE_1_ID).when(device).getId();
        doReturn(DEVICE_TYPE_HEATING).when(device).getDeviceType();
        doReturn(List.of(device)).when(gateway).getDevices();
        doReturn(List.of(gateway)).when(installation).getGateways();
        doReturn(INSTALLATION_ID).when(installation).getId();
        List<Installation> installations = List.of(installation);
        doReturn(installations).when(vicareService).getInstallations();

        Feature temperatureSensor = new NumericSensorFeature("heating.dhw.sensors.temperature.outlet", new DimensionalValue(new Unit("celsius"), 27.3), new Status("connected"));
        Feature statisticsFeature = new StatisticsFeature("heating.burners.0.statistics", Map.of("starts", new DimensionalValue(new Unit("starts"), 5.0)));
        Feature textFeature = new TextFeature("device.serial", "7723181102527121");
        Feature statusFeature = new StatusSensorFeature("heating.circuits.0.circulation.pump", new Status("on"));
        Feature normalProgramFeature = new NumericSensorFeature("heating.circuits.0.operating.programs.normal", new DimensionalValue(new Unit("celcius"), 21), Status.ON);
        Feature consumptionFeature = new ConsumptionFeature("heating.gas.consumption.summary.dhw",
                new DimensionalValue(new Unit("cubicMeter"), 0.2),
                new DimensionalValue(new Unit("cubicMeter"), 2.1),
                new DimensionalValue(new Unit("cubicMeter"), 1.8),
                new DimensionalValue(new Unit("cubicMeter"), 5.9));
        when(vicareService.getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID))
                .thenReturn(List.of(temperatureSensor, statisticsFeature, textFeature, statusFeature, normalProgramFeature, consumptionFeature));
    }

    private void oneHeatingInstallationTwoBoilers() throws AuthenticationException, IOException {
        Installation installation = mock(Installation.class);
        Gateway gateway = mock(Gateway.class);
        doReturn(GATEWAY_SERIAL).when(gateway).getSerial();
        Device device1 = mock(Device.class);
        doReturn(DEVICE_1_ID).when(device1).getId();
        doReturn(DEVICE_TYPE_HEATING).when(device1).getDeviceType();
        Device device2 = mock(Device.class);
        doReturn(DEVICE_2_ID).when(device2).getId();
        doReturn(DEVICE_TYPE_HEATING).when(device2).getDeviceType();
        doReturn(List.of(device1, device2)).when(gateway).getDevices();
        doReturn(List.of(gateway)).when(installation).getGateways();
        doReturn(INSTALLATION_ID).when(installation).getId();
        List<Installation> installations = List.of(installation);
        doReturn(installations).when(vicareService).getInstallations();
        Feature textFeature1 = new TextFeature("device.serial", "1111111111111111");
        Feature textFeature2 = new TextFeature("device.serial", "2222222222222222");
        when(vicareService.getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID))
                .thenReturn(List.of(textFeature1));
        when(vicareService.getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_2_ID))
                .thenReturn(List.of(textFeature2));
    }

    private Bridge vicareBridge() {
        Bridge bridge = mock(Bridge.class);
        doReturn(THING_UID_BRIDGE).when(bridge).getUID();
        doReturn(THING_TYPE_BRIDGE).when(bridge).getThingTypeUID();
        Configuration openhabConfig = mock(Configuration.class);
        doReturn(openhabConfig).when(bridge).getConfiguration();
        doReturn(Map.of("clientId", "myClientId",
                "accessServerUri", "http://localhost:9000/access",
                "iotServerUri", "http://localhost:9000/iot"))
                .when(openhabConfig).getProperties();
        return bridge;
    }

    @BeforeEach
    void setUp() {
        componentContext = mock(ComponentContext.class);
        bundleContext = mock(BundleContext.class);
        configuration = new SimpleConfiguration();
        doReturn(bundleContext).when(componentContext).getBundleContext();
        Bundle bundle = mock(Bundle.class);
        doReturn(bundle).when(bundleContext).getBundle();
    }

    @AfterEach
    void tearDown() {
        if (bridgeHandler != null) {
            bridgeHandler.dispose();
            bridgeHandler = null;
        }
    }

    @Test
    public void bridgeHandlerReportsDiscoveryService() throws AuthenticationException, IOException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();

        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        activateHandlerFactory(vicareHandlerFactory);

        BridgeHandler handler = (BridgeHandler) vicareHandlerFactory.createHandler(bridge);
        assertNotNull(handler);
        assertTrue(handler.getServices().contains(VicareDiscoveryService.class));
    }

    @Test
    public void canStartAScan() throws AuthenticationException, IOException, InvocationTargetException, NoSuchMethodException, IllegalAccessException, InstantiationException, InterruptedException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();

        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        activateHandlerFactory(vicareHandlerFactory);

        bridgeHandler = vicareHandlerFactory.createHandler(bridge);

        DiscoveryListener discoveryListener = mock(DiscoveryListener.class);
        InOrder inOrder1 = inOrder(vicareService, discoveryListener);
        VicareDiscoveryService discoveryService = VicareDiscoveryService.class.getConstructor().newInstance();
        discoveryService.setThingHandler(bridgeHandler);
        discoveryService.addDiscoveryListener(discoveryListener);

        inOrder1.verify(vicareService, timeout(1000).times(1)).getInstallations();
        ArgumentCaptor<DiscoveryResult> resultArgumentCaptor = forClass(DiscoveryResult.class);
        inOrder1.verify(discoveryListener, calls(1)).thingDiscovered(same(discoveryService), any(DiscoveryResult.class));

        InOrder inOrder2 = inOrder(vicareService, discoveryListener);
        Thread.sleep(1000);

        discoveryService.startScan();
        inOrder2.verify(vicareService, timeout(1000).times(1)).getInstallations();
        inOrder2.verify(discoveryListener, calls(1)).thingDiscovered(same(discoveryService), resultArgumentCaptor.capture());
        DiscoveryResult result = resultArgumentCaptor.getValue();
        assertEquals("vicare:heating:" + THING_UID_BRIDGE.getId() + ":0328bf05-9b58-35fe-9845-edfc5a9b09aa", result.getThingUID().getAsString());
        assertEquals(THING_UID_BRIDGE, result.getBridgeUID());
        assertEquals(encodeThingUniqueId(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID), result.getProperties().get("deviceUniqueId"));
        assertEquals("deviceUniqueId", result.getRepresentationProperty());
    }
    @Test
    public void startsBackgroundScan() throws AuthenticationException, IOException, InvocationTargetException, NoSuchMethodException, IllegalAccessException, InstantiationException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();

        ThingHandlerCallback thingHandlerCallback = mock(ThingHandlerCallback.class);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        bridgeHandler = vicareHandlerFactory.createHandler(bridge);
        bridgeHandler.setCallback(thingHandlerCallback);
        VicareDiscoveryService discoveryService = VicareDiscoveryService.class.getConstructor().newInstance();
        discoveryService.setThingHandler(bridgeHandler);
        DiscoveryListener discoveryListener = mock(DiscoveryListener.class);
        discoveryService.addDiscoveryListener(discoveryListener);
        activateHandlerFactory(vicareHandlerFactory);
        activateDiscoveryService(discoveryService);

        assertTrue(discoveryService.isBackgroundDiscoveryEnabled());
        verify(vicareService, timeout(1000)).getInstallations();
        ArgumentCaptor<DiscoveryResult> resultArgumentCaptor = forClass(DiscoveryResult.class);
        verify(discoveryListener, timeout(1000)).thingDiscovered(same(discoveryService), resultArgumentCaptor.capture());
        DiscoveryResult result = resultArgumentCaptor.getValue();
        assertEquals("vicare:heating:" + THING_UID_BRIDGE.getId() + ":0328bf05-9b58-35fe-9845-edfc5a9b09aa", result.getThingUID().getAsString());
        assertEquals(THING_UID_BRIDGE, result.getBridgeUID());
        assertEquals(encodeThingUniqueId(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID), result.getProperties().get("deviceUniqueId"));
        assertEquals("deviceUniqueId", result.getRepresentationProperty());
        ArgumentCaptor<ThingStatusInfo> statusInfoArgumentCaptor = forClass(ThingStatusInfo.class);
        verify(thingHandlerCallback).statusUpdated(same(bridge), statusInfoArgumentCaptor.capture());
        assertEquals(ThingStatus.ONLINE, statusInfoArgumentCaptor.getValue().getStatus());
    }

    @Test
    public void handlerFactoryCreateDeviceThingHandler() throws AuthenticationException, IOException {
        simpleHeatingInstallation();
        vicareBridge();

        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);

        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);

        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);

        assertNotNull(handler);
    }

    @Test
    public void initializeDeviceHandlerCreatesTemperatureSensor() throws AuthenticationException, IOException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        bridgeHandler.setCallback(mock(ThingHandlerCallback.class));
        when(bridge.getHandler()).thenReturn((BridgeHandler) bridgeHandler);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);
        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);


        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        handler.initialize();
        InOrder inOrder = inOrder(vicareService);
        inOrder.verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<Thing> thingCaptor = forClass(Thing.class);
        verify(callback, timeout(1000).atLeastOnce()).thingUpdated(thingCaptor.capture());
        Channel channel = thingCaptor.getAllValues().stream()
                .flatMap(t -> t.getChannels().stream())
                .filter(c -> c.getUID().getId().equals("heating_dhw_sensors_temperature_outlet"))
                .findFirst()
                .orElse(null);
        assertNotNull(channel);
        assertEquals("heating_dhw_sensors_temperature_outlet", channel.getChannelTypeUID().getId());
        assertEquals("heating.dhw.sensors.temperature.outlet", channel.getProperties().get(PROPERTY_FEATURE_NAME));

        handler.handleCommand(channel.getUID(), RefreshType.REFRESH);
        inOrder.verify(vicareService).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<State> stateCaptor = forClass(State.class);
        verify(callback).stateUpdated(eq(channel.getUID()), stateCaptor.capture());
        assertEquals(27.3, ((DecimalType)stateCaptor.getValue()).doubleValue(), 0.01);
    }

    @Test
    public void initializeDeviceHandlerCreatesOperatingProgramTemperatureSetting() throws AuthenticationException, IOException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        bridgeHandler.setCallback(mock(ThingHandlerCallback.class));
        when(bridge.getHandler()).thenReturn((BridgeHandler) bridgeHandler);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);
        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);


        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        handler.initialize();
        InOrder inOrder = inOrder(vicareService);
        inOrder.verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<Thing> thingCaptor = forClass(Thing.class);
        verify(callback, timeout(1000).atLeastOnce()).thingUpdated(thingCaptor.capture());
        Channel channel = thingCaptor.getAllValues().stream()
                .flatMap(t -> t.getChannels().stream())
                .filter(c -> c.getUID().getId().equals("heating_circuits_0_operating_programs_normal"))
                .findFirst()
                .orElse(null);
        assertNotNull(channel);
        assertEquals("heating_circuits_operating_programs_normal", channel.getChannelTypeUID().getId());
        assertEquals("heating.circuits.0.operating.programs.normal", channel.getProperties().get(PROPERTY_FEATURE_NAME));

        Channel activeChannel = thingCaptor.getAllValues().stream()
                .flatMap(t -> t.getChannels().stream())
                .filter(c -> c.getUID().getId().equals("heating_circuits_0_operating_programs_normal_active"))
                .findFirst()
                .orElse(null);
        assertNotNull(activeChannel);
        assertEquals("heating_circuits_operating_programs_normal_active", activeChannel.getChannelTypeUID().getId());
        assertEquals("heating.circuits.0.operating.programs.normal", activeChannel.getProperties().get(PROPERTY_FEATURE_NAME));

        handler.handleCommand(channel.getUID(), RefreshType.REFRESH);
        inOrder.verify(vicareService).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<State> stateCaptor = forClass(State.class);
        verify(callback).stateUpdated(eq(channel.getUID()), stateCaptor.capture());
        assertEquals(21, ((DecimalType)stateCaptor.getValue()).doubleValue(), 0.01);

        handler.handleCommand(activeChannel.getUID(), RefreshType.REFRESH);
        verify(callback).stateUpdated(eq(activeChannel.getUID()), stateCaptor.capture());
        assertEquals(OnOffType.ON, stateCaptor.getValue());
    }

    @Test
    public void initializeDeviceHandlerCreatesConsumptionChannels() throws AuthenticationException, IOException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        bridgeHandler.setCallback(mock(ThingHandlerCallback.class));
        when(bridge.getHandler()).thenReturn((BridgeHandler) bridgeHandler);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);
        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);


        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        handler.initialize();
        InOrder inOrder = inOrder(vicareService);
        inOrder.verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<Thing> thingCaptor = forClass(Thing.class);
        verify(callback, timeout(1000).atLeastOnce()).thingUpdated(thingCaptor.capture());
        Map<String, Channel> channelsById = thingCaptor.getAllValues().stream()
                .flatMap(t -> t.getChannels().stream())
                .filter(c -> c.getProperties().get(PROPERTY_FEATURE_NAME).equals("heating.gas.consumption.summary.dhw"))
                .collect(Collectors.toMap(c -> c.getChannelTypeUID().getId(), identity()));
        assertTrue(channelsById.containsKey("heating_gas_consumption_summary_dhw_currentDay"));
        assertTrue(channelsById.containsKey("heating_gas_consumption_summary_dhw_lastSevenDays"));
        assertTrue(channelsById.containsKey("heating_gas_consumption_summary_dhw_currentMonth"));
        assertTrue(channelsById.containsKey("heating_gas_consumption_summary_dhw_currentYear"));
        Map<String, Double> expectedValues = Map.of(
                "heating_gas_consumption_summary_dhw_currentDay", 0.2,
                "heating_gas_consumption_summary_dhw_lastSevenDays", 2.1,
                "heating_gas_consumption_summary_dhw_currentMonth", 1.8,
                "heating_gas_consumption_summary_dhw_currentYear", 5.9
        );
        Map<String, String> statisticName = Map.of(
                "heating_gas_consumption_summary_dhw_currentDay", "currentDay",
                "heating_gas_consumption_summary_dhw_lastSevenDays", "lastSevenDays",
                "heating_gas_consumption_summary_dhw_currentMonth", "currentMonth",
                "heating_gas_consumption_summary_dhw_currentYear", "currentYear"
        );
        channelsById.values().forEach(c -> {
                    assertEquals(statisticName.get(c.getChannelTypeUID().getId()), c.getProperties().get(PROPERTY_STATISTIC_NAME));
                    handler.handleCommand(c.getUID(), RefreshType.REFRESH);
                    ArgumentCaptor<State> stateCaptor = forClass(State.class);
                    verify(callback).stateUpdated(eq(c.getUID()), stateCaptor.capture());
                    assertEquals(expectedValues.get(c.getChannelTypeUID().getId()), ((DecimalType)stateCaptor.getValue()).doubleValue(), 0.01);
        });
    }


    @Test
    public void initializeDeviceHandlerCreatesStatisticsSensor() throws AuthenticationException, IOException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        bridgeHandler.setCallback(mock(ThingHandlerCallback.class));
        when(bridge.getHandler()).thenReturn((BridgeHandler) bridgeHandler);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);
        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);


        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        handler.initialize();
        InOrder inOrder = inOrder(vicareService);
        inOrder.verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<Thing> thingCaptor = forClass(Thing.class);
        verify(callback, timeout(1000).atLeastOnce()).thingUpdated(thingCaptor.capture());
        Channel channel = thingCaptor.getAllValues().stream()
                .flatMap(t -> t.getChannels().stream())
                .filter(c -> c.getUID().getId().equals("heating_burners_0_statistics_starts"))
                .findFirst()
                .orElse(null);
        assertNotNull(channel);
        assertEquals("heating_burners_statistics_starts", channel.getChannelTypeUID().getId());
        assertEquals("heating.burners.0.statistics", channel.getProperties().get(PROPERTY_FEATURE_NAME));

        handler.handleCommand(channel.getUID(), RefreshType.REFRESH);
        inOrder.verify(vicareService).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<State> stateCaptor = forClass(State.class);
        verify(callback).stateUpdated(eq(channel.getUID()), stateCaptor.capture());
        assertEquals(5.0, ((DecimalType)stateCaptor.getValue()).doubleValue(), 0.01);
    }

    @Test
    public void initializeDeviceHandlerCreatesTextProperty() throws AuthenticationException, IOException, InterruptedException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        bridgeHandler.setCallback(mock(ThingHandlerCallback.class));
        when(bridge.getHandler()).thenReturn((BridgeHandler) bridgeHandler);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);
        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);


        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        handler.initialize();
//        Thread.sleep(1000);
        InOrder inOrder = inOrder(vicareService);
        inOrder.verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<Thing> thingCaptor = forClass(Thing.class);
        verify(callback, timeout(1000).atLeastOnce()).thingUpdated(thingCaptor.capture());

        assertEquals("7723181102527121", thingCaptor.getValue().getProperties().get("device.serial"));
    }

    private ThingHandlerCallback simpleHandlerCallback(Bridge bridge, ThingHandler handler) {
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);

        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        return callback;
    }

    @Test
    public void initializeDeviceHandlerCreatesStatusSensor() throws AuthenticationException, IOException, InterruptedException {
        simpleHeatingInstallation();
        Bridge bridge = vicareBridge();
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        bridgeHandler.setCallback(mock(ThingHandlerCallback.class));
        when(bridge.getHandler()).thenReturn((BridgeHandler) bridgeHandler);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        Thing deviceThing = heatingDeviceThing(DEVICE_1_ID);
        ThingHandler handler = vicareHandlerFactory.createHandler(deviceThing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(THING_UID_BRIDGE)).thenReturn(bridge);
        handler.setCallback(callback);

        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingUID uid = thing.getUID();
            doReturn(thing).when(thingRegistry).get(uid);
            return null;
        }).when(callback).thingUpdated(any(Thing.class));
        handler.initialize();
        InOrder inOrder = inOrder(vicareService);
        inOrder.verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<Thing> thingCaptor = forClass(Thing.class);
        verify(callback, timeout(1000).atLeastOnce()).thingUpdated(thingCaptor.capture());

        Channel channel = thingCaptor.getAllValues().stream()
                .flatMap(t -> t.getChannels().stream())
                .filter(c -> c.getUID().getId().equals("heating_circuits_0_circulation_pump"))
                .findFirst()
                .orElse(null);
        assertNotNull(channel);
        assertEquals("heating_circuits_circulation_pump", channel.getChannelTypeUID().getId());
        assertEquals("heating.circuits.0.circulation.pump", channel.getProperties().get(PROPERTY_FEATURE_NAME));

        handler.handleCommand(channel.getUID(), RefreshType.REFRESH);
        inOrder.verify(vicareService).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);
        ArgumentCaptor<State> stateCaptor = forClass(State.class);
        verify(callback).stateUpdated(eq(channel.getUID()), stateCaptor.capture());
        assertEquals(OnOffType.ON, stateCaptor.getValue());
    }

    @Test
    public void initializeBridgeSetsStatusToUnknown() {
        Bridge bridge = vicareBridge();
        VicareBridgeHandler vicareBridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        vicareBridgeHandler.setCallback(callback);
        vicareBridgeHandler.initialize();

        ArgumentCaptor<ThingStatusInfo> thingStatusCaptor = ArgumentCaptor.forClass(ThingStatusInfo.class);
        verify(callback).statusUpdated(same(bridge), thingStatusCaptor.capture());
        assertEquals(ThingStatus.UNKNOWN, thingStatusCaptor.getValue().getStatus());

    }

    @Test
    public void bridgeCachesResponsesPerDevice() throws AuthenticationException, IOException {
        oneHeatingInstallationTwoBoilers();
        Bridge bridge = vicareBridge();
        Thing boiler1 = heatingDeviceThing(DEVICE_1_ID);
        Thing boiler2 = heatingDeviceThing(DEVICE_2_ID);
        VicareHandlerFactory vicareHandlerFactory = new VicareHandlerFactory(bundleContext, thingRegistry, vicareService, configurationAdmin, configuration);
        bridgeHandler = new VicareBridgeHandler(vicareService, thingRegistry, bridge, configuration);
        ThingHandler boilerHandler1 = vicareHandlerFactory.createHandler(boiler1);
        ThingHandler boilerHandler2 = vicareHandlerFactory.createHandler(boiler2);
        ThingHandlerCallback thingHandlerCallback1 = simpleHandlerCallback(bridge, boilerHandler1);
        boilerHandler1.setCallback(thingHandlerCallback1);
        ThingHandlerCallback thingHandlerCallback2 = simpleHandlerCallback(bridge, boilerHandler2);
        boilerHandler2.setCallback(thingHandlerCallback2);

        boilerHandler1.initialize();
        verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_1_ID);

        boilerHandler2.initialize();
        verify(vicareService, timeout(1000)).getFeatures(INSTALLATION_ID, GATEWAY_SERIAL, DEVICE_2_ID);

        verify(thingHandlerCallback1, timeout(1000)).thingUpdated(any(Thing.class));
        assertEquals("1111111111111111", boilerHandler1.getThing().getProperties().get("device.serial"));

        verify(thingHandlerCallback2, timeout(1000)).thingUpdated(any(Thing.class));
        assertEquals("2222222222222222", boilerHandler2.getThing().getProperties().get("device.serial"));
    }


    private Thing heatingDeviceThing(String deviceId) {
        Thing deviceThing = mock(Thing.class);
        doReturn(THING_UID_BRIDGE).when(deviceThing).getBridgeUID();
        doReturn(THING_TYPE_HEATING).when(deviceThing).getThingTypeUID();
        ThingUID deviceThingId = new ThingUID(THING_TYPE_HEATING, THING_UID_BRIDGE, encodeThingId(INSTALLATION_ID, GATEWAY_SERIAL, deviceId));
        doReturn(deviceThingId).when(deviceThing).getUID();
        Map<String, String> propMap = new HashMap<>(Map.of(PROPERTY_DEVICE_UNIQUE_ID, encodeThingUniqueId(INSTALLATION_ID, GATEWAY_SERIAL, deviceId)));
        doReturn(propMap).when(deviceThing).getProperties();
        doAnswer(i -> {
            propMap.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(deviceThing).setProperty(anyString(), nullable(String.class));
        doReturn(THING_UID_BRIDGE).when(deviceThing).getBridgeUID();
        doReturn(deviceThing).when(thingRegistry).get(deviceThingId);
        return deviceThing;
    }

    private void activateHandlerFactory(VicareHandlerFactory vicareHandlerFactory) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method activate = BaseThingHandlerFactory.class.getDeclaredMethod("activate", ComponentContext.class);
        activate.setAccessible(true);
        activate.invoke(vicareHandlerFactory, componentContext);
    }

    private void activateDiscoveryService(VicareDiscoveryService discoveryService) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Method activate = AbstractDiscoveryService.class.getDeclaredMethod("activate", Map.class);
        activate.setAccessible(true);
        activate.invoke(discoveryService, emptyMap());
    }
}
