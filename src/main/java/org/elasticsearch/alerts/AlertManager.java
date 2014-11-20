/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts;


import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.alerts.actions.AlertActionEntry;
import org.elasticsearch.alerts.actions.AlertActionManager;
import org.elasticsearch.alerts.actions.AlertActionRegistry;
import org.elasticsearch.alerts.actions.AlertActionState;
import org.elasticsearch.alerts.scheduler.AlertScheduler;
import org.elasticsearch.alerts.triggers.TriggerManager;
import org.elasticsearch.alerts.triggers.TriggerResult;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.component.LifecycleListener;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.KeyedLock;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public class AlertManager extends AbstractComponent {

    private final AlertScheduler scheduler;
    private final AlertsStore alertsStore;
    private final TriggerManager triggerManager;
    private final AlertActionManager actionManager;
    private final AlertActionRegistry actionRegistry;
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final KeyedLock<String> alertLock = new KeyedLock<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    @Inject
    public AlertManager(Settings settings, ClusterService clusterService, AlertScheduler scheduler, AlertsStore alertsStore,
                        IndicesService indicesService, TriggerManager triggerManager, AlertActionManager actionManager,
                        AlertActionRegistry actionRegistry, ThreadPool threadPool) {
        super(settings);
        this.scheduler = scheduler;
        this.threadPool = threadPool;
        this.scheduler.setAlertManager(this);
        this.alertsStore = alertsStore;
        this.triggerManager = triggerManager;
        this.actionManager = actionManager;
        this.actionManager.setAlertManager(this);
        this.actionRegistry = actionRegistry;
        this.clusterService = clusterService;
        clusterService.add(new AlertsClusterStateListener());
        // Close if the indices service is being stopped, so we don't run into search failures (locally) that will
        // happen because we're shutting down and an alert is scheduled.
        indicesService.addLifecycleListener(new LifecycleListener() {
            @Override
            public void beforeStop() {
                stop();
            }
        });

    }

    public DeleteResponse deleteAlert(String name) throws InterruptedException, ExecutionException {
        ensureStarted();
        alertLock.acquire(name);
        try {
            DeleteResponse deleteResponse = alertsStore.deleteAlert(name);
            if (deleteResponse.isFound()) {
                scheduler.remove(name);
            }
            return deleteResponse;
        } finally {
            alertLock.release(name);
        }
    }

    public IndexResponse addAlert(String alertName, BytesReference alertSource) {
        ensureStarted();
        alertLock.acquire(alertName);
        try {
            Tuple<Alert, IndexResponse> result = alertsStore.addAlert(alertName, alertSource);
            scheduler.add(alertName, result.v1());
            return result.v2();
        } finally {
            alertLock.release(alertName);
        }
    }

    public boolean isStarted() {
        return state.get() == State.STARTED;
    }

    public void scheduleAlert(String alertName, DateTime scheduledFireTime, DateTime fireTime){
        ensureStarted();
        alertLock.acquire(alertName);
        try {
            Alert alert = alertsStore.getAlert(alertName);
            if (alert == null) {
                logger.warn("Unable to find [{}] in the alert store, perhaps it has been deleted", alertName);
                return;
            }
            if (!alert.enabled()) {
                logger.debug("Alert [{}] is not enabled", alert.alertName());
                return;
            }

            try {
                actionManager.addAlertAction(alert, scheduledFireTime, fireTime);
            } catch (IOException ioe) {
                logger.error("Failed to add alert action for [{}]", ioe, alert);
            }
        } finally {
            alertLock.release(alertName);
        }
    }

    public TriggerResult executeAlert(AlertActionEntry entry) throws IOException {
        ensureStarted();
        alertLock.acquire(entry.getAlertName());
        try {
            Alert alert = alertsStore.getAlert(entry.getAlertName());
            if (alert == null) {
                throw new ElasticsearchException("Alert is not available");
            }
            TriggerResult triggerResult = triggerManager.isTriggered(alert, entry.getScheduledTime(), entry.getFireTime());
            entry.setSearchResponse(triggerResult.getResponse());

            if (triggerResult.isTriggered()) {
                entry.setTriggered(true);
                if (!isActionThrottled(alert)) {
                    actionRegistry.doAction(alert, triggerResult);
                    alert.setTimeLastActionExecuted(entry.getFireTime());
                    if (alert.getAckState() == AlertAckState.NOT_TRIGGERED) {
                        alert.setAckState(AlertAckState.NEEDS_ACK);
                    }
                } else {
                    entry.setEntryState(AlertActionState.THROTTLED);
                }
                alert.lastActionFire(entry.getFireTime());
                alertsStore.updateAlert(alert);
            } else if (alert.getAckState() == AlertAckState.ACKED) {
                alert.setAckState(AlertAckState.NOT_TRIGGERED);
                alertsStore.updateAlert(alert);
            }
            return triggerResult;
        } finally {
            alertLock.release(entry.getAlertName());
        }
    }

    private boolean isActionThrottled(Alert alert) {
        if (alert.getThrottlePeriod() != null && alert.getTimeLastActionExecuted() != null) {
            TimeValue timeSinceLastExeuted = new TimeValue((new DateTime()).getMillis() - alert.getTimeLastActionExecuted().getMillis());
            if (timeSinceLastExeuted.getMillis() <= alert.getThrottlePeriod().getMillis()) {
                return true;
            }
        }
        if (alert.getAckState() == AlertAckState.ACKED) {
            return true;
        }
        return false;
    }


    // This is synchronized, because this may first be called from the cluster changed event and then from before close
    // when a node closes. The stop also stops the scheduler which has several background threads. If this method is
    // invoked in that order that node closes and the test framework complains then about the fact that there are still
    // threads alive.
    public synchronized void stop() {
        if (state.compareAndSet(State.LOADING, State.STOPPED) || state.compareAndSet(State.STARTED, State.STOPPED)) {
            logger.info("Stopping alert manager...");
            actionManager.stop();
            scheduler.stop();
            alertsStore.stop();
            logger.info("Alert manager has stopped");
        }
    }

    /**
     * For testing only to clear the alerts between tests.
     */
    public void clear() {
        scheduler.clearAlerts();
        alertsStore.clear();
    }

    private void ensureStarted() {
        if (state.get() != State.STARTED) {
            throw new ElasticsearchIllegalStateException("not started");
        }
    }

    public long getNumberOfAlerts() {
        return alertsStore.getAlerts().size();
    }

    /**
     * Acks the alert if needed
     * @param alertName
     * @return
     */
    public AlertAckState ackAlert(String alertName) {
        ensureStarted();
        alertLock.acquire(alertName);
        try {
            Alert alert = alertsStore.getAlert(alertName);
            if (alert == null) {
                throw new ElasticsearchException("Alert is does not exist [" + alertName + "]");
            }
            if (alert.getAckState() == AlertAckState.NEEDS_ACK) {
                alert.setAckState(AlertAckState.ACKED);
                try {
                    alertsStore.updateAlert(alert);
                } catch (IOException ioe) {
                    throw new ElasticsearchException("Failed to update the alert", ioe);
                }
                return AlertAckState.ACKED;
            } else {
                return alert.getAckState();
            }
        } finally {
            alertLock.release(alertName);
        }
    }

    private final class AlertsClusterStateListener implements ClusterStateListener {

        @Override
        public void clusterChanged(ClusterChangedEvent event) {
            if (!event.localNodeMaster()) {
                // We're no longer the master so we need to stop alerting.
                // Stopping alerting may take a while since it will wait on the scheduler to complete shutdown,
                // so we fork here so that we don't wait too long. Other events may need to be processed and
                // other cluster state listeners may need to be executed as well for this event.
                threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                    @Override
                    public void run() {
                        stop();
                    }
                });
            } else {
                if (event.state().blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
                    // wait until the gateway has recovered from disk, otherwise we think may not have .alerts and
                    // a .alertshistory index, but they may not have been restored from the cluster state on disk
                    return;
                }
                if (state.compareAndSet(State.STOPPED, State.LOADING)) {
                    initialize(event.state());
                }
            }
        }

        private void initialize(final ClusterState state) {
            threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                @Override
                public void run() {
                    if (alertsStore.start(state)) {
                        startIfReady();
                    } else {
                        retry();
                    }
                }
            });
            threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                @Override
                public void run() {
                    if (actionManager.start(state)) {
                        startIfReady();
                    } else {
                        retry();
                    }
                }
            });
        }

        private void startIfReady() {
            if (alertsStore.started() && actionManager.started()) {
                if (state.compareAndSet(State.LOADING, State.STARTED)) {
                    scheduler.start(alertsStore.getAlerts());
                } else {
                    logger.info("Didn't start alert manager, because it state was [{}] while [{}] was expected", state.get(), State.LOADING);
                }
            }
        }

        private void retry() {
            // Only retry if our state is loading
            if (state.get() == State.LOADING) {
                final ClusterState newState = clusterService.state();
                threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                    @Override
                    public void run() {
                        // Retry with the same event:
                        initialize(newState);
                    }
                });
            } else {
                logger.info("Didn't retry to initialize the alert manager, because it state was [{}] while [{}] was expected", state.get(), State.LOADING);
            }
        }

    }

    private enum State {

        STOPPED,
        LOADING,
        STARTED

    }

}
