package com.qubular.binding.glowmarkt.internal;

import com.qubular.glowmarkt.*;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.persistence.*;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.qubular.binding.glowmarkt.internal.GlowmarktConstants.*;
import static com.qubular.glowmarkt.AggregationPeriod.*;
import static java.lang.String.format;
import static java.time.Duration.ofDays;
import static java.util.Optional.of;
import static java.util.stream.StreamSupport.stream;

public class GlowmarktVirtualEntityHandler extends BaseThingHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlowmarktVirtualEntityHandler.class);
    private final GlowmarktService glowmarktService;
    private final PersistenceService persistenceService;
    private final ItemChannelLinkRegistry itemChannelLinkRegistry;

    public GlowmarktVirtualEntityHandler(Thing thing, GlowmarktService glowmarktService, PersistenceService persistenceService, ItemChannelLinkRegistry itemChannelLinkRegistry) {
        super(thing);
        this.glowmarktService = glowmarktService;
        this.persistenceService = persistenceService;
        this.itemChannelLinkRegistry = itemChannelLinkRegistry;
    }


    @Override
    public void initialize() {
        if (!(persistenceService instanceof ModifiablePersistenceService)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    format("Persistence service %s does not implement ModifiablePersistenceService", persistenceService));
            return;
        }
        CompletableFuture.runAsync(() -> {
            GlowmarktBridgeHandler bridgeHandler = (GlowmarktBridgeHandler) getBridge().getHandler();
            String virtualEntityId = getThing().getProperties().get(PROPERTY_VIRTUAL_ENTITY_ID);
            try {
                GlowmarktSession glowmarktSession = bridgeHandler.getGlowmarktSession();
                VirtualEntity virtualEntity = glowmarktService.getVirtualEntity(glowmarktSession, bridgeHandler.getGlowmarktSettings(), virtualEntityId);
                List<Channel> channels = new ArrayList<>();
                for (Resource resource : virtualEntity.getResources()) {
                    ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, channelType(resource));
                    Channel channel = getCallback().createChannelBuilder(new ChannelUID(getThing().getUID(), channelId(resource)), channelTypeUID)
                            .withType(channelTypeUID)
                            .withProperties(Map.of(GlowmarktConstants.PROPERTY_CLASSIFIER, resource.getClassifier(),
                                    GlowmarktConstants.PROPERTY_RESOURCE_ID, resource.getResourceId()))
                            .build();
                    channels.add(channel);
                }
                if (!channels.isEmpty()) {
                    updateThing(editThing().withChannels(channels).build());
                }
                updateStatus(ThingStatus.ONLINE);
            } catch (AuthenticationFailedException e) {
                String msg = "Unable to authenticate with Glowmarkt API: " + e.getMessage();
                updateStatus(ThingStatus.UNINITIALIZED, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                logger.debug(msg, e);
            } catch (IOException e) {
                String msg = "Unable to fetch resources from Glowmarkt API: " + e.getMessage();
                updateStatus(ThingStatus.UNINITIALIZED, ThingStatusDetail.COMMUNICATION_ERROR, msg);
                logger.debug(msg, e);
            }
        }).exceptionally(e -> {
            logger.error("Unexpected error initializing " + getThing().getUID(), e);
            return null;
        });
    }

    private String channelType(Resource resource) {
        return resource.getClassifier().replaceAll("[^\\w-]", "_");
    }

    private String channelId(Resource resource) {
        return resource.getClassifier().replaceAll("[^\\w-]", "_");
    }

    private ModifiablePersistenceService getPersistenceService() {
        return (ModifiablePersistenceService) persistenceService;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH.equals(command)) {
            Channel channel = getThing().getChannel(channelUID);
            String resourceId = channel.getProperties().get(PROPERTY_RESOURCE_ID);
            Set<Item> linkedItems = itemChannelLinkRegistry.getLinkedItems(channelUID);
            try {
                for (var item : linkedItems) {
                    fetchHistoricData(resourceId, item);
                }
                updateStatus(ThingStatus.ONLINE);
            } catch (AuthenticationFailedException e) {
                String msg = "Authentication problem fetching resource data: " + e.getMessage();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                logger.debug(msg, e);
            } catch (IOException e) {
                String msg = "Problem fetching resource data: " + e.getMessage();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, msg);
                logger.debug(msg, e);
            }
        }
    }

    private void fetchHistoricData(String resourceId, Item item) throws AuthenticationFailedException, IOException {
        FilterCriteria filterCriteria = new FilterCriteria();
        ZonedDateTime persistenceQueryStartDate = ZonedDateTime.now().minusYears(1);
        filterCriteria.setBeginDate(persistenceQueryStartDate);
        ZonedDateTime persistenceQueryEndDate = ZonedDateTime.now();
        filterCriteria.setEndDate(persistenceQueryEndDate);
        filterCriteria.setItemName(item.getName());
        Iterable<HistoricItem> dataSeries = getPersistenceService().query(filterCriteria);
        var i = dataSeries.iterator();

        Optional<ZonedDateTime> earliestPersistedTimestamp = stream(dataSeries.spliterator(), true)
                .map(HistoricItem::getTimestamp)
                .reduce((t1, t2) -> t1.isBefore(t2) ? t1 : t2);
        Optional<ZonedDateTime> latestPersistedTimestamp = stream(dataSeries.spliterator(), true)
                .map(HistoricItem::getTimestamp)
                .reduce((t1, t2) -> t1.isAfter(t2) ? t1 : t2);

        if (earliestPersistedTimestamp.map(persistenceQueryStartDate::isBefore).orElse(true)) {
            fetchHistoricDataForMissingPeriod(resourceId, item, persistenceQueryStartDate, earliestPersistedTimestamp);
        }
        if (latestPersistedTimestamp.map(persistenceQueryEndDate::isAfter).orElse(false)) {
            fetchHistoricDataForMissingPeriod(resourceId, item, latestPersistedTimestamp.get(), of(persistenceQueryEndDate));
        }
    }

    private GlowmarktBridgeHandler getBridgeHandler() {
        return (GlowmarktBridgeHandler) getBridge().getHandler();
    }

    private void fetchHistoricDataForMissingPeriod(String resourceId, Item item, ZonedDateTime startDate, Optional<ZonedDateTime> endDate) throws AuthenticationFailedException, IOException {
        Instant firstTime = glowmarktService.getFirstTime(getBridgeHandler().getGlowmarktSession(),
                getBridgeHandler().getGlowmarktSettings(),
                resourceId);
        Instant lastTime = glowmarktService.getLastTime(getBridgeHandler().getGlowmarktSession(),
                getBridgeHandler().getGlowmarktSettings(),
                resourceId);
        Instant fetchStart = !firstTime.isBefore(startDate.toInstant()) ? firstTime : startDate.toInstant();
        Instant fetchEnd;
        if (endDate.isPresent()) {
            fetchEnd = !lastTime.isAfter(endDate.get().toInstant()) ? lastTime : endDate.get().toInstant();
        } else {
            fetchEnd = lastTime;
        }

        if (fetchStart.isBefore(fetchEnd)) {
            batchFetchHistoricData(resourceId, item, fetchStart, fetchEnd);
        }
    }

    private void batchFetchHistoricData(String resourceId, Item item, Instant fetchStart, Instant fetchEnd) throws AuthenticationFailedException, IOException {
        AggregationPeriod aggregationPeriod = PT30M;
        TemporalAmount timeStep = getMaxDuration(aggregationPeriod);
        for (Instant t = fetchStart; t.isBefore(fetchEnd); t = t.plus(timeStep)) {
            Instant t2 = t.plus(timeStep);
            if (t2.isAfter(fetchEnd)) {
                t2 = fetchEnd;
            }
            List<ResourceData> resourceReadings = glowmarktService.getResourceReadings(getBridgeHandler().getGlowmarktSession(),
                    getBridgeHandler().getGlowmarktSettings(),
                    resourceId,
                    t,
                    t2,
                    aggregationPeriod,
                    AggregationFunction.SUM);
            resourceReadings.forEach(r -> {
                        getPersistenceService().store(item, ZonedDateTime.ofInstant(r.getTimestamp(), ZoneId.systemDefault()), new DecimalType(r.getReading()));
                    });
        }
    }

    private TemporalAmount getMaxDuration(AggregationPeriod period) {
        return Map.of(PT30M, ofDays(10),
                PT1H, ofDays(31),
                P1D, ofDays(31),
                P1W, ofDays(6 * 7),
                P1M, ofDays(366),
                P1Y, ofDays(366)).get(period);
    }
}
