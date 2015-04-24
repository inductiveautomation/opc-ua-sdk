/*
 * Copyright 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpetri.opcua.sdk.server.subscriptions;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.digitalpetri.opcua.sdk.core.AttributeIds;
import com.digitalpetri.opcua.sdk.core.NumericRange;
import com.digitalpetri.opcua.sdk.server.OpcUaServer;
import com.digitalpetri.opcua.sdk.server.Session;
import com.digitalpetri.opcua.sdk.server.api.DataItem;
import com.digitalpetri.opcua.sdk.server.api.EventItem;
import com.digitalpetri.opcua.sdk.server.api.MonitoredItem;
import com.digitalpetri.opcua.sdk.server.api.Namespace;
import com.digitalpetri.opcua.sdk.server.items.BaseMonitoredItem;
import com.digitalpetri.opcua.sdk.server.items.MonitoredDataItem;
import com.digitalpetri.opcua.sdk.server.items.MonitoredEventItem;
import com.digitalpetri.opcua.sdk.server.subscriptions.Subscription.State;
import com.digitalpetri.opcua.stack.core.StatusCodes;
import com.digitalpetri.opcua.stack.core.UaException;
import com.digitalpetri.opcua.stack.core.application.services.ServiceRequest;
import com.digitalpetri.opcua.stack.core.types.builtin.DiagnosticInfo;
import com.digitalpetri.opcua.stack.core.types.builtin.NodeId;
import com.digitalpetri.opcua.stack.core.types.builtin.QualifiedName;
import com.digitalpetri.opcua.stack.core.types.builtin.StatusCode;
import com.digitalpetri.opcua.stack.core.types.builtin.unsigned.UInteger;
import com.digitalpetri.opcua.stack.core.types.builtin.unsigned.UShort;
import com.digitalpetri.opcua.stack.core.types.enumerated.MonitoringMode;
import com.digitalpetri.opcua.stack.core.types.enumerated.TimestampsToReturn;
import com.digitalpetri.opcua.stack.core.types.structured.CreateMonitoredItemsRequest;
import com.digitalpetri.opcua.stack.core.types.structured.CreateMonitoredItemsResponse;
import com.digitalpetri.opcua.stack.core.types.structured.CreateSubscriptionRequest;
import com.digitalpetri.opcua.stack.core.types.structured.CreateSubscriptionResponse;
import com.digitalpetri.opcua.stack.core.types.structured.DeleteMonitoredItemsRequest;
import com.digitalpetri.opcua.stack.core.types.structured.DeleteMonitoredItemsResponse;
import com.digitalpetri.opcua.stack.core.types.structured.DeleteSubscriptionsRequest;
import com.digitalpetri.opcua.stack.core.types.structured.DeleteSubscriptionsResponse;
import com.digitalpetri.opcua.stack.core.types.structured.ModifyMonitoredItemsRequest;
import com.digitalpetri.opcua.stack.core.types.structured.ModifyMonitoredItemsResponse;
import com.digitalpetri.opcua.stack.core.types.structured.ModifySubscriptionRequest;
import com.digitalpetri.opcua.stack.core.types.structured.ModifySubscriptionResponse;
import com.digitalpetri.opcua.stack.core.types.structured.MonitoredItemCreateRequest;
import com.digitalpetri.opcua.stack.core.types.structured.MonitoredItemCreateResult;
import com.digitalpetri.opcua.stack.core.types.structured.MonitoredItemModifyRequest;
import com.digitalpetri.opcua.stack.core.types.structured.MonitoredItemModifyResult;
import com.digitalpetri.opcua.stack.core.types.structured.MonitoringParameters;
import com.digitalpetri.opcua.stack.core.types.structured.NotificationMessage;
import com.digitalpetri.opcua.stack.core.types.structured.PublishRequest;
import com.digitalpetri.opcua.stack.core.types.structured.PublishResponse;
import com.digitalpetri.opcua.stack.core.types.structured.RepublishRequest;
import com.digitalpetri.opcua.stack.core.types.structured.RepublishResponse;
import com.digitalpetri.opcua.stack.core.types.structured.ResponseHeader;
import com.digitalpetri.opcua.stack.core.types.structured.SetMonitoringModeRequest;
import com.digitalpetri.opcua.stack.core.types.structured.SetMonitoringModeResponse;
import com.digitalpetri.opcua.stack.core.types.structured.SetPublishingModeRequest;
import com.digitalpetri.opcua.stack.core.types.structured.SetPublishingModeResponse;
import com.digitalpetri.opcua.stack.core.types.structured.SetTriggeringRequest;
import com.digitalpetri.opcua.stack.core.types.structured.SetTriggeringResponse;
import com.digitalpetri.opcua.stack.core.types.structured.SubscriptionAcknowledgement;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.digitalpetri.opcua.sdk.core.util.ConversionUtil.a;
import static com.digitalpetri.opcua.sdk.server.util.FutureUtils.sequence;
import static com.digitalpetri.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static java.util.stream.Collectors.toList;

public class SubscriptionManager {

    private static final QualifiedName DEFAULT_BINARY_ENCODING = new QualifiedName(0, "DefaultBinary");
    private static final QualifiedName DEFAULT_XML_ENCODING = new QualifiedName(0, "DefaultXML");

    private static final AtomicLong SUBSCRIPTION_IDS = new AtomicLong(0L);

    private static UInteger nextSubscriptionId() {
        return uint(SUBSCRIPTION_IDS.incrementAndGet());
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Map<UInteger, StatusCode[]> acknowledgeResults = Maps.newConcurrentMap();

    private final PublishQueue publishQueue = new PublishQueue();

    private final Map<UInteger, Subscription> subscriptions = Maps.newConcurrentMap();
    private final List<Subscription> transferred = Lists.newCopyOnWriteArrayList();

    private final Session session;
    private final OpcUaServer server;

    public SubscriptionManager(Session session, OpcUaServer server) {
        this.session = session;
        this.server = server;
    }

    public Session getSession() {
        return session;
    }

    public PublishQueue getPublishQueue() {
        return publishQueue;
    }

    public OpcUaServer getServer() {
        return server;
    }

    public void createSubscription(ServiceRequest<CreateSubscriptionRequest, CreateSubscriptionResponse> service) {
        CreateSubscriptionRequest request = service.getRequest();

        UInteger subscriptionId = nextSubscriptionId();

        Subscription subscription = new Subscription(
                this,
                subscriptionId,
                request.getRequestedPublishingInterval(),
                request.getRequestedMaxKeepAliveCount().longValue(),
                request.getRequestedLifetimeCount().longValue(),
                request.getMaxNotificationsPerPublish().longValue(),
                request.getPublishingEnabled(),
                request.getPriority().intValue()
        );

        subscriptions.put(subscriptionId, subscription);
        server.getSubscriptions().put(subscriptionId, subscription);

        subscription.setStateListener((s, ps, cs) -> {
            if (cs == State.Closed) {
                subscriptions.remove(s.getId());
                server.getSubscriptions().remove(s.getId());
            }
        });

        subscription.startPublishingTimer();

        ResponseHeader header = service.createResponseHeader();

        CreateSubscriptionResponse response = new CreateSubscriptionResponse(
                header, subscriptionId,
                subscription.getPublishingInterval(),
                uint(subscription.getLifetimeCount()),
                uint(subscription.getMaxKeepAliveCount())
        );

        service.setResponse(response);
    }

    public void modifySubscription(ServiceRequest<ModifySubscriptionRequest, ModifySubscriptionResponse> service) {
        ModifySubscriptionRequest request = service.getRequest();
        UInteger subscriptionId = request.getSubscriptionId();

        try {
            Subscription subscription = subscriptions.get(subscriptionId);

            if (subscription == null) {
                throw new UaException(StatusCodes.Bad_SubscriptionIdInvalid);
            }

            subscription.modifySubscription(request);

            ResponseHeader header = service.createResponseHeader();

            ModifySubscriptionResponse response = new ModifySubscriptionResponse(
                    header,
                    subscription.getPublishingInterval(),
                    uint(subscription.getLifetimeCount()),
                    uint(subscription.getMaxKeepAliveCount())
            );

            service.setResponse(response);
        } catch (UaException e) {
            service.setServiceFault(e);
        }
    }

    public void deleteSubscription(ServiceRequest<DeleteSubscriptionsRequest, DeleteSubscriptionsResponse> service) {
        DeleteSubscriptionsRequest request = service.getRequest();
        UInteger[] subscriptionIds = request.getSubscriptionIds();

        if (subscriptionIds.length == 0) {
            service.setServiceFault(StatusCodes.Bad_NothingToDo);
            return;
        }

        StatusCode[] results = new StatusCode[subscriptionIds.length];

        for (int i = 0; i < subscriptionIds.length; i++) {
            Subscription subscription = subscriptions.remove(subscriptionIds[i]);

            if (subscription != null) {
                List<BaseMonitoredItem<?>> deletedItems = subscription.deleteSubscription();

                /*
                * Notify namespaces of the items we just deleted.
                */

                Map<UShort, List<BaseMonitoredItem<?>>> byNamespace = deletedItems.stream()
                        .collect(Collectors.groupingBy(item -> item.getReadValueId().getNodeId().getNamespaceIndex()));

                byNamespace.entrySet().forEach(entry -> {
                    UShort namespaceIndex = entry.getKey();

                    List<BaseMonitoredItem<?>> items = entry.getValue();
                    List<DataItem> dataItems = Lists.newArrayList();
                    List<EventItem> eventItems = Lists.newArrayList();


                    for (BaseMonitoredItem<?> item : items) {
                        if (item instanceof MonitoredDataItem) {
                            dataItems.add((DataItem) item);
                        } else if (item instanceof MonitoredEventItem) {
                            eventItems.add((EventItem) item);
                        }
                    }

                    if (!dataItems.isEmpty()) {
                        server.getNamespaceManager().getNamespace(namespaceIndex).onDataItemsDeleted(dataItems);
                    }
                    if (!eventItems.isEmpty()) {
                        server.getNamespaceManager().getNamespace(namespaceIndex).onEventItemsDeleted(eventItems);
                    }
                });

                results[i] = StatusCode.GOOD;
            } else {
                results[i] = new StatusCode(StatusCodes.Bad_SubscriptionIdInvalid);
            }
        }

        ResponseHeader header = service.createResponseHeader();
        DeleteSubscriptionsResponse response = new DeleteSubscriptionsResponse(
                header, results, new DiagnosticInfo[0]);

        service.setResponse(response);

        while (subscriptions.isEmpty() && publishQueue.isNotEmpty()) {
            ServiceRequest<PublishRequest, PublishResponse> publishService = publishQueue.poll();
            if (publishService != null) {
                publishService.setServiceFault(StatusCodes.Bad_NoSubscription);
            }
        }
    }

    public void setPublishingMode(ServiceRequest<SetPublishingModeRequest, SetPublishingModeResponse> service) {
        SetPublishingModeRequest request = service.getRequest();
        UInteger[] subscriptionIds = request.getSubscriptionIds();
        StatusCode[] results = new StatusCode[subscriptionIds.length];

        for (int i = 0; i < subscriptionIds.length; i++) {
            Subscription subscription = subscriptions.get(subscriptionIds[i]);
            if (subscription == null) {
                results[i] = new StatusCode(StatusCodes.Bad_SubscriptionIdInvalid);
            } else {
                subscription.setPublishingMode(request);
                results[i] = StatusCode.GOOD;
            }
        }

        ResponseHeader header = service.createResponseHeader();
        SetPublishingModeResponse response = new SetPublishingModeResponse(
                header, results, new DiagnosticInfo[0]);

        service.setResponse(response);
    }

    public void createMonitoredItems(ServiceRequest<CreateMonitoredItemsRequest, CreateMonitoredItemsResponse> service) {
        CreateMonitoredItemsRequest request = service.getRequest();
        UInteger subscriptionId = request.getSubscriptionId();

        try {
            Subscription subscription = subscriptions.get(subscriptionId);
            TimestampsToReturn timestamps = service.getRequest().getTimestampsToReturn();
            MonitoredItemCreateRequest[] itemsToCreate = service.getRequest().getItemsToCreate();

            if (subscription == null) {
                throw new UaException(StatusCodes.Bad_SubscriptionIdInvalid);
            }
            if (timestamps == null) {
                throw new UaException(StatusCodes.Bad_TimestampsToReturnInvalid);
            }
            if (itemsToCreate.length == 0) {
                throw new UaException(StatusCodes.Bad_NothingToDo);
            }

            List<BaseMonitoredItem<?>> createdItems = newArrayListWithCapacity(itemsToCreate.length);

            List<PendingItemCreation> pending = Arrays.stream(itemsToCreate)
                    .map(PendingItemCreation::new)
                    .collect(toList());

            for (PendingItemCreation p : pending) {
                MonitoredItemCreateRequest r = p.getRequest();
                NodeId nodeId = r.getItemToMonitor().getNodeId();
                UInteger attributeId = r.getItemToMonitor().getAttributeId();
                QualifiedName dataEncoding = r.getItemToMonitor().getDataEncoding();

                if (dataEncoding.isNotNull()) {
                    if (attributeId.intValue() != AttributeIds.Value) {
                        MonitoredItemCreateResult result = new MonitoredItemCreateResult(
                                new StatusCode(StatusCodes.Bad_DataEncodingInvalid),
                                uint(0), 0d, uint(0), null);

                        p.getResultFuture().complete(result);
                        continue;
                    }
                    if (!dataEncoding.equals(DEFAULT_BINARY_ENCODING) &&
                            !dataEncoding.equals(DEFAULT_XML_ENCODING)) {
                        MonitoredItemCreateResult result = new MonitoredItemCreateResult(
                                new StatusCode(StatusCodes.Bad_DataEncodingUnsupported),
                                uint(0), 0d, uint(0), null);

                        p.getResultFuture().complete(result);
                        continue;
                    }
                }

                Namespace namespace = server.getNamespaceManager().getNamespace(nodeId.getNamespaceIndex());

                double samplingInterval = r.getRequestedParameters().getSamplingInterval();
                double minSupportedSampleRate = server.getConfig().getLimits().getMinSupportedSampleRate();
                double maxSupportedSampleRate = server.getConfig().getLimits().getMaxSupportedSampleRate();

                if (samplingInterval < 0) samplingInterval = subscription.getPublishingInterval();
                if (samplingInterval < minSupportedSampleRate) samplingInterval = minSupportedSampleRate;
                if (samplingInterval > maxSupportedSampleRate) samplingInterval = maxSupportedSampleRate;

                namespace.onCreateMonitoredItem(nodeId, attributeId, samplingInterval, p.getCreateFuture());

                p.getCreateFuture().whenComplete((revisedSamplingInterval, ex) -> {
                    if (revisedSamplingInterval != null) {
                        try {
                            String indexRange = r.getItemToMonitor().getIndexRange();
                            if (indexRange != null) NumericRange.parse(indexRange);

                            MonitoredDataItem item = new MonitoredDataItem(
                                    uint(subscription.nextItemId()),
                                    r.getItemToMonitor(),
                                    r.getMonitoringMode(),
                                    timestamps,
                                    r.getRequestedParameters().getClientHandle(),
                                    revisedSamplingInterval,
                                    r.getRequestedParameters().getFilter(),
                                    r.getRequestedParameters().getQueueSize(),
                                    r.getRequestedParameters().getDiscardOldest());

                            createdItems.add(item);

                            MonitoredItemCreateResult result = new MonitoredItemCreateResult(
                                    StatusCode.GOOD,
                                    item.getId(),
                                    item.getSamplingInterval(),
                                    uint(item.getQueueSize()),
                                    item.getFilterResult());

                            p.getResultFuture().complete(result);
                        } catch (UaException e) {
                            MonitoredItemCreateResult result =
                                    new MonitoredItemCreateResult(e.getStatusCode(), uint(0), 0d, uint(0), null);

                            p.getResultFuture().complete(result);
                        }
                    } else {
                        StatusCode statusCode = StatusCode.BAD;

                        if (ex instanceof UaException) {
                            statusCode = ((UaException) ex).getStatusCode();
                        }

                        MonitoredItemCreateResult result =
                                new MonitoredItemCreateResult(statusCode, uint(0), 0d, uint(0), null);

                        p.getResultFuture().complete(result);
                    }
                });
            }

            List<CompletableFuture<MonitoredItemCreateResult>> futures = pending.stream()
                    .map(PendingItemCreation::getResultFuture)
                    .collect(toList());

            sequence(futures).thenAccept(results -> {
                subscription.addMonitoredItems(createdItems);

                // Notify namespaces of the items we just created.
                Map<UShort, List<BaseMonitoredItem<?>>> byNamespace = createdItems.stream()
                        .collect(Collectors.groupingBy(item -> item.getReadValueId().getNodeId().getNamespaceIndex()));

                byNamespace.entrySet().forEach(entry -> {
                    UShort namespaceIndex = entry.getKey();

                    List<BaseMonitoredItem<?>> items = entry.getValue();
                    List<DataItem> dataItems = Lists.newArrayList();
                    List<EventItem> eventItems = Lists.newArrayList();

                    for (BaseMonitoredItem<?> item : items) {
                        if (item instanceof MonitoredDataItem) {
                            dataItems.add((DataItem) item);
                        } else if (item instanceof MonitoredEventItem) {
                            eventItems.add((EventItem) item);
                        }
                    }

                    if (!dataItems.isEmpty()) {
                        server.getNamespaceManager().getNamespace(namespaceIndex).onDataItemsCreated(dataItems);
                    }
                    if (!eventItems.isEmpty()) {
                        server.getNamespaceManager().getNamespace(namespaceIndex).onEventItemsCreated(eventItems);
                    }
                });

                ResponseHeader header = service.createResponseHeader();

                CreateMonitoredItemsResponse response = new CreateMonitoredItemsResponse(
                        header, a(results, MonitoredItemCreateResult.class), new DiagnosticInfo[0]);

                service.setResponse(response);
            });
        } catch (UaException e) {
            service.setServiceFault(e);
        }
    }

    public void modifyMonitoredItems(ServiceRequest<ModifyMonitoredItemsRequest, ModifyMonitoredItemsResponse> service) {
        ModifyMonitoredItemsRequest request = service.getRequest();
        UInteger subscriptionId = request.getSubscriptionId();

        try {
            Subscription subscription = subscriptions.get(subscriptionId);
            TimestampsToReturn timestamps = service.getRequest().getTimestampsToReturn();
            MonitoredItemModifyRequest[] itemsToModify = service.getRequest().getItemsToModify();

            if (subscription == null) {
                throw new UaException(StatusCodes.Bad_SubscriptionIdInvalid);
            }
            if (timestamps == null) {
                throw new UaException(StatusCodes.Bad_TimestampsToReturnInvalid);
            }
            if (itemsToModify.length == 0) {
                throw new UaException(StatusCodes.Bad_NothingToDo);
            }

            List<PendingItemModification> pending = Arrays.stream(itemsToModify)
                    .map(PendingItemModification::new)
                    .collect(toList());

            List<BaseMonitoredItem<?>> modifiedItems = newArrayListWithCapacity(itemsToModify.length);

            /*
             * Modify requested items and prepare results.
             */

            for (PendingItemModification p : pending) {
                MonitoredItemModifyRequest r = p.getRequest();
                UInteger itemId = r.getMonitoredItemId();
                MonitoringParameters parameters = r.getRequestedParameters();

                BaseMonitoredItem<?> item = subscription.getMonitoredItems().get(itemId);

                if (item == null) {
                    MonitoredItemModifyResult result = new MonitoredItemModifyResult(
                            new StatusCode(StatusCodes.Bad_MonitoredItemIdInvalid),
                            0d, uint(0), null);

                    p.getResultFuture().complete(result);
                } else {
                    double samplingInterval = parameters.getSamplingInterval();
                    double minSupportedSampleRate = server.getConfig().getLimits().getMinSupportedSampleRate();
                    double maxSupportedSampleRate = server.getConfig().getLimits().getMaxSupportedSampleRate();

                    if (samplingInterval < 0) samplingInterval = subscription.getPublishingInterval();
                    if (samplingInterval < minSupportedSampleRate) samplingInterval = minSupportedSampleRate;
                    if (samplingInterval > maxSupportedSampleRate) samplingInterval = maxSupportedSampleRate;

                    NodeId nodeId = item.getReadValueId().getNodeId();
                    Namespace namespace = server.getNamespaceManager().getNamespace(nodeId.getNamespaceIndex());

                    namespace.onModifyMonitoredItem(samplingInterval, p.getCreateFuture());

                    p.getCreateFuture().whenComplete((revisedSamplingInterval, ex) -> {
                        if (revisedSamplingInterval != null) {
                            try {
                                item.modify(
                                        timestamps,
                                        parameters.getClientHandle(),
                                        revisedSamplingInterval,
                                        parameters.getFilter(),
                                        parameters.getQueueSize(),
                                        parameters.getDiscardOldest());

                                modifiedItems.add(item);

                                MonitoredItemModifyResult result = new MonitoredItemModifyResult(
                                        StatusCode.GOOD,
                                        item.getSamplingInterval(),
                                        uint(item.getQueueSize()),
                                        item.getFilterResult());

                                p.getResultFuture().complete(result);
                            } catch (UaException e) {
                                MonitoredItemModifyResult result = new MonitoredItemModifyResult(
                                        e.getStatusCode(),
                                        item.getSamplingInterval(),
                                        uint(item.getQueueSize()),
                                        item.getFilterResult());

                                p.getResultFuture().complete(result);
                            }
                        } else {
                            StatusCode statusCode = StatusCode.BAD;

                            if (ex instanceof UaException) {
                                statusCode = ((UaException) ex).getStatusCode();
                            }

                            MonitoredItemModifyResult result = new MonitoredItemModifyResult(
                                    statusCode,
                                    item.getSamplingInterval(),
                                    uint(item.getQueueSize()),
                                    item.getFilterResult());

                            p.getResultFuture().complete(result);
                        }
                    });
                }
            }

            subscription.resetLifetimeCounter();

            /*
             * Notify namespaces of the items we just modified.
             */

            List<CompletableFuture<MonitoredItemModifyResult>> futures = pending.stream()
                    .map(PendingItemModification::getResultFuture)
                    .collect(toList());

            sequence(futures).thenAccept(results -> {
                Map<UShort, List<BaseMonitoredItem<?>>> byNamespace = modifiedItems.stream()
                        .collect(Collectors.groupingBy(item -> item.getReadValueId().getNodeId().getNamespaceIndex()));

                byNamespace.entrySet().forEach(entry -> {
                    UShort namespaceIndex = entry.getKey();

                    List<BaseMonitoredItem<?>> items = entry.getValue();
                    List<DataItem> dataItems = Lists.newArrayList();
                    List<EventItem> eventItems = Lists.newArrayList();


                    for (BaseMonitoredItem<?> item : items) {
                        if (item instanceof MonitoredDataItem) {
                            dataItems.add((DataItem) item);
                        } else if (item instanceof MonitoredEventItem) {
                            eventItems.add((EventItem) item);
                        }
                    }

                    if (!dataItems.isEmpty()) {
                        server.getNamespaceManager().getNamespace(namespaceIndex).onDataItemsModified(dataItems);
                    }
                    if (!eventItems.isEmpty()) {
                        server.getNamespaceManager().getNamespace(namespaceIndex).onEventItemsModified(eventItems);
                    }
                });

                /*
                 * Namespaces have been notified; send response.
                 */

                ResponseHeader header = service.createResponseHeader();
                ModifyMonitoredItemsResponse response = new ModifyMonitoredItemsResponse(
                        header, a(results, MonitoredItemModifyResult.class), new DiagnosticInfo[0]);

                service.setResponse(response);
            });
        } catch (UaException e) {
            service.setServiceFault(e);
        }
    }

    public void deleteMonitoredItems(ServiceRequest<DeleteMonitoredItemsRequest, DeleteMonitoredItemsResponse> service) {
        DeleteMonitoredItemsRequest request = service.getRequest();
        UInteger subscriptionId = request.getSubscriptionId();

        try {
            Subscription subscription = subscriptions.get(subscriptionId);
            UInteger[] itemsToDelete = service.getRequest().getMonitoredItemIds();

            if (subscription == null) {
                throw new UaException(StatusCodes.Bad_SubscriptionIdInvalid);
            }
            if (itemsToDelete.length == 0) {
                throw new UaException(StatusCodes.Bad_NothingToDo);
            }

            StatusCode[] deleteResults = new StatusCode[itemsToDelete.length];
            List<BaseMonitoredItem<?>> deletedItems = newArrayListWithCapacity(itemsToDelete.length);

            synchronized (subscription) {
                for (int i = 0; i < itemsToDelete.length; i++) {
                    UInteger itemId = itemsToDelete[i];
                    BaseMonitoredItem<?> item = subscription.getMonitoredItems().get(itemId);

                    if (item == null) {
                        deleteResults[i] = new StatusCode(StatusCodes.Bad_MonitoredItemIdInvalid);
                    } else {
                        deletedItems.add(item);

                        deleteResults[i] = StatusCode.GOOD;
                    }
                }

                subscription.removeMonitoredItems(deletedItems);
            }

            /*
             * Notify namespaces of the items that have been deleted.
             */

            Map<UShort, List<BaseMonitoredItem<?>>> byNamespace = deletedItems.stream()
                    .collect(Collectors.groupingBy(item -> item.getReadValueId().getNodeId().getNamespaceIndex()));

            byNamespace.entrySet().forEach(entry -> {
                UShort namespaceIndex = entry.getKey();

                List<BaseMonitoredItem<?>> items = entry.getValue();
                List<DataItem> dataItems = Lists.newArrayList();
                List<EventItem> eventItems = Lists.newArrayList();

                for (BaseMonitoredItem<?> item : items) {
                    if (item instanceof MonitoredDataItem) {
                        dataItems.add((DataItem) item);
                    } else if (item instanceof MonitoredEventItem) {
                        eventItems.add((EventItem) item);
                    }
                }

                if (!dataItems.isEmpty()) {
                    server.getNamespaceManager().getNamespace(namespaceIndex).onDataItemsDeleted(dataItems);
                }
                if (!eventItems.isEmpty()) {
                    server.getNamespaceManager().getNamespace(namespaceIndex).onEventItemsDeleted(eventItems);
                }
            });

            /*
             * Build and return results.
             */
            ResponseHeader header = service.createResponseHeader();
            DeleteMonitoredItemsResponse response = new DeleteMonitoredItemsResponse(
                    header, deleteResults, new DiagnosticInfo[0]);

            service.setResponse(response);
        } catch (UaException e) {
            service.setServiceFault(e);
        }
    }

    public void setMonitoringMode(ServiceRequest<SetMonitoringModeRequest, SetMonitoringModeResponse> service) {
        SetMonitoringModeRequest request = service.getRequest();
        UInteger subscriptionId = request.getSubscriptionId();

        try {
            Subscription subscription = subscriptions.get(subscriptionId);
            UInteger[] itemsToModify = request.getMonitoredItemIds();

            if (subscription == null) {
                throw new UaException(StatusCodes.Bad_SubscriptionIdInvalid);
            }
            if (itemsToModify.length == 0) {
                throw new UaException(StatusCodes.Bad_NothingToDo);
            }

            /*
             * Set MonitoringMode on each monitored item, if it exists.
             */

            MonitoringMode monitoringMode = request.getMonitoringMode();
            StatusCode[] results = new StatusCode[itemsToModify.length];
            List<BaseMonitoredItem<?>> modified = newArrayListWithCapacity(itemsToModify.length);

            for (int i = 0; i < itemsToModify.length; i++) {
                UInteger itemId = itemsToModify[i];
                BaseMonitoredItem<?> item = subscription.getMonitoredItems().get(itemId);

                if (item != null) {
                    item.setMonitoringMode(monitoringMode);

                    modified.add(item);

                    results[i] = StatusCode.GOOD;
                } else {
                    results[i] = new StatusCode(StatusCodes.Bad_MonitoredItemIdInvalid);
                }
            }

            /*
             * Notify namespaces of the items whose MonitoringMode has been modified.
             */

            Map<UShort, List<MonitoredItem>> byNamespace = modified.stream()
                    .collect(Collectors.groupingBy(item -> item.getReadValueId().getNodeId().getNamespaceIndex()));

            byNamespace.keySet().forEach(namespaceIndex -> {
                List<MonitoredItem> items = byNamespace.get(namespaceIndex);
                server.getNamespaceManager().getNamespace(namespaceIndex).onMonitoringModeChanged(items);
            });

            /*
             * Build and return results.
             */

            ResponseHeader header = service.createResponseHeader();
            SetMonitoringModeResponse response = new SetMonitoringModeResponse(
                    header, results, new DiagnosticInfo[0]);

            service.setResponse(response);
        } catch (UaException e) {
            service.setServiceFault(e);
        }
    }

    public void publish(ServiceRequest<PublishRequest, PublishResponse> service) {
        PublishRequest request = service.getRequest();

        if (!transferred.isEmpty()) {
            Subscription subscription = transferred.remove(0);
            subscription.returnStatusChangeNotification(service);
            return;
        }

        if (subscriptions.isEmpty()) {
            service.setServiceFault(StatusCodes.Bad_NoSubscription);
            return;
        }

        SubscriptionAcknowledgement[] acknowledgements = request.getSubscriptionAcknowledgements();
        StatusCode[] results = new StatusCode[acknowledgements.length];

        for (int i = 0; i < acknowledgements.length; i++) {
            SubscriptionAcknowledgement acknowledgement = acknowledgements[i];


            UInteger sequenceNumber = acknowledgement.getSequenceNumber();
            UInteger subscriptionId = acknowledgement.getSubscriptionId();

            logger.debug("Acknowledging sequenceNumber={} on subscriptionId={}", sequenceNumber, subscriptionId);

            Subscription subscription = subscriptions.get(subscriptionId);

            if (subscription == null) {
                results[i] = new StatusCode(StatusCodes.Bad_SubscriptionIdInvalid);
            } else {
                results[i] = subscription.acknowledge(sequenceNumber);
            }
        }

        acknowledgeResults.put(request.getRequestHeader().getRequestHandle(), results);

        publishQueue.addRequest(service);
    }

    public void republish(ServiceRequest<RepublishRequest, RepublishResponse> service) {
        RepublishRequest request = service.getRequest();

        if (subscriptions.isEmpty()) {
            service.setServiceFault(StatusCodes.Bad_SubscriptionIdInvalid);
            return;
        }

        UInteger subscriptionId = request.getSubscriptionId();
        Subscription subscription = subscriptions.get(subscriptionId);

        if (subscription == null) {
            service.setServiceFault(StatusCodes.Bad_SubscriptionIdInvalid);
            return;
        }

        UInteger sequenceNumber = request.getRetransmitSequenceNumber();
        NotificationMessage notificationMessage = subscription.republish(sequenceNumber);

        if (notificationMessage == null) {
            service.setServiceFault(StatusCodes.Bad_MessageNotAvailable);
            return;
        }

        ResponseHeader header = service.createResponseHeader();
        RepublishResponse response = new RepublishResponse(header, notificationMessage);

        service.setResponse(response);
    }

    public void setTriggering(ServiceRequest<SetTriggeringRequest, SetTriggeringResponse> service) {
        SetTriggeringRequest request = service.getRequest();

        UInteger subscriptionId = request.getSubscriptionId();
        Subscription subscription = subscriptions.get(subscriptionId);

        if (subscription == null) {
            service.setServiceFault(StatusCodes.Bad_SubscriptionIdInvalid);
            return;
        }

        if (request.getLinksToAdd().length == 0 && request.getLinksToRemove().length == 0) {
            service.setServiceFault(StatusCodes.Bad_NothingToDo);
            return;
        }

        UInteger triggerId = request.getTriggeringItemId();
        UInteger[] linksToAdd = request.getLinksToAdd();
        UInteger[] linksToRemove = request.getLinksToRemove();


        synchronized (subscription) {
            Map<UInteger, BaseMonitoredItem<?>> itemsById = subscription.getMonitoredItems();

            BaseMonitoredItem<?> triggerItem = itemsById.get(triggerId);
            if (triggerItem == null) {
                service.setServiceFault(StatusCodes.Bad_MonitoredItemIdInvalid);
                return;
            }

            List<StatusCode> removeResults = Arrays.stream(linksToRemove)
                    .map(linkedItemId -> {
                        BaseMonitoredItem<?> item = itemsById.get(linkedItemId);
                        if (item != null) {
                            if (triggerItem.getTriggeredItems().remove(linkedItemId) != null) {
                                return StatusCode.GOOD;
                            } else {
                                return new StatusCode(StatusCodes.Bad_MonitoredItemIdInvalid);
                            }
                        } else {
                            return new StatusCode(StatusCodes.Bad_MonitoredItemIdInvalid);
                        }
                    })
                    .collect(toList());

            List<StatusCode> addResults = Arrays.stream(linksToAdd)
                    .map(linkedItemId -> {
                        BaseMonitoredItem<?> linkedItem = itemsById.get(linkedItemId);
                        if (linkedItem != null) {
                            triggerItem.getTriggeredItems().put(linkedItemId, linkedItem);
                            return StatusCode.GOOD;
                        } else {
                            return new StatusCode(StatusCodes.Bad_MonitoredItemIdInvalid);
                        }
                    })
                    .collect(toList());

            SetTriggeringResponse response = new SetTriggeringResponse(
                    service.createResponseHeader(),
                    addResults.toArray(new StatusCode[addResults.size()]),
                    new DiagnosticInfo[0],
                    removeResults.toArray(new StatusCode[removeResults.size()]),
                    new DiagnosticInfo[0]
            );

            service.setResponse(response);
        }
    }

    public void sessionClosed(boolean deleteSubscriptions) {
        Iterator<Subscription> iterator = subscriptions.values().iterator();

        while (iterator.hasNext()) {
            Subscription s = iterator.next();
            s.setStateListener(null);

            if (deleteSubscriptions) {
                server.getSubscriptions().remove(s.getId());
            }

            iterator.remove();
        }
    }

    public Subscription removeSubscription(UInteger subscriptionId) {
        Subscription subscription = subscriptions.remove(subscriptionId);
        if (subscription != null) subscription.setStateListener(null);
        return subscription;
    }

    public void addSubscription(Subscription subscription) {
        subscriptions.put(subscription.getId(), subscription);

        subscription.setStateListener((s, ps, cs) -> {
            if (cs == State.Closed) {
                subscriptions.remove(s.getId());
                server.getSubscriptions().remove(s.getId());
            }
        });
    }

    StatusCode[] getAcknowledgeResults(UInteger requestHandle) {
        return acknowledgeResults.remove(requestHandle);
    }

    public void sendStatusChangeNotification(Subscription subscription) {
        ServiceRequest<PublishRequest, PublishResponse> service = publishQueue.poll();

        if (service != null) {
            subscription.returnStatusChangeNotification(service);
        } else {
            transferred.add(subscription);
        }
    }

}
