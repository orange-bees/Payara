package fish.payara.monitoring.store;

import static java.lang.Boolean.parseBoolean;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableCollection;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.glassfish.api.StartupRunLevel;
import org.glassfish.hk2.runlevel.RunLevel;
import org.jvnet.hk2.annotations.Service;

import fish.payara.monitoring.alert.Alert;
import fish.payara.monitoring.alert.Alert.Level;
import fish.payara.monitoring.collect.MonitoringData;
import fish.payara.monitoring.collect.MonitoringDataCollector;
import fish.payara.monitoring.collect.MonitoringDataSource;
import fish.payara.monitoring.collect.MonitoringWatchCollector;
import fish.payara.monitoring.collect.MonitoringWatchSource;
import fish.payara.monitoring.alert.AlertService;
import fish.payara.monitoring.alert.Watch;
import fish.payara.monitoring.model.Metric;
import fish.payara.monitoring.model.Series;
import fish.payara.monitoring.model.Unit;

@Service
@RunLevel(StartupRunLevel.VAL)
class InMemoryAlarmService extends AbstractMonitoringService implements AlertService, MonitoringDataSource {

    private static final int MAX_ALERTS_PER_SERIES = 10;

    @Inject
    private MonitoringDataRepository monitoringData;

    private boolean isDas;
    private final JobHandle checker = new JobHandle("watch checker");
    private final Map<String, Watch> watchesByName = new ConcurrentHashMap<>();
    private final Map<Series, Map<String, Watch>> simpleWatches = new ConcurrentHashMap<>();
    private final Map<Series, Map<String, Watch>> patternWatches = new ConcurrentHashMap<>();
    private final Map<Series, Deque<Alert>> alerts = new ConcurrentHashMap<>();
    private final AtomicReference<AlertStatistics> statistics = new AtomicReference<>(new AlertStatistics());
    private final AtomicLong evalLoopTime = new AtomicLong();

    /**
     * Watches that are added during collection
     */
    private final Map<String, Watch> collectedWatches = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        isDas = serverEnv.isDas();
        changedConfig(parseBoolean(serverConfig.getMonitoringService().getMonitoringEnabled()));
    }

    @Override
    void changedConfig(boolean enabled) {
        if (!isDas) {
            return; // only check alerts on DAS
        }
        if (!enabled) {
            checker.stop();
        } else {
            checker.start(executor, 2, SECONDS, this::checkWatches);
        }
    }

    @Override
    @MonitoringData(ns = "monitoring", intervalSeconds = 2)
    public void collect(MonitoringDataCollector collector) {
        if (isDas) {
            collector.collect("WatchLoopDuration", evalLoopTime.get());
        }
    }

    @Override
    public AlertStatistics getAlertStatistics() {
        return statistics.get();
    }

    @Override
    public Collection<Alert> alertsMatching(Predicate<Alert> filter) {
        List<Alert> matches = new ArrayList<>();
        for (Queue<Alert> queue : alerts.values()) {
            for (Alert a : queue) {
                if (filter.test(a)) {
                    matches.add(a);
                }
            }
        }
        return matches;
    }

    @Override
    public Collection<Alert> alerts() {
        List<Alert> all = new ArrayList<>();
        for (Queue<Alert> queue : alerts.values()) {
            all.addAll(queue);
        }
        return all;
    }

    @Override
    public Collection<Alert> alertsFor(Series series) {
        if (!series.isPattern()) {
            // this is the usual path called while polling for data so this should not be too expensive
            Deque<Alert> alertsForSeries = alerts.get(series);
            return alertsForSeries == null ? emptyList() : unmodifiableCollection(alertsForSeries);
        }
        if (series.equalTo(Series.ANY)) {
            return alerts();
        }
        Collection<Alert> matches = null;
        for (Entry<Series, Deque<Alert>> e : alerts.entrySet()) {
            if (series.matches(e.getKey())) {
                if (matches == null) {
                    matches = e.getValue();
                } else {
                    if (!(matches instanceof ArrayList)) {
                        matches = new ArrayList<>(matches);
                    }
                    matches.addAll(e.getValue());
                }
            }
        }
        return matches == null ? emptyList() : unmodifiableCollection(matches);
    }

    @Override
    public void addWatch(Watch watch) {
        Watch existing = watchesByName.put(watch.name, watch);
        if (existing != null) {
            removeWatch(existing);
        }
        Series series = watch.watched.series;
        Map<Series, Map<String, Watch>> target = series.isPattern() ? patternWatches : simpleWatches;
        target.computeIfAbsent(series, key -> new ConcurrentHashMap<>()).put(watch.name, watch);
    }

    private void removeWatch(Watch watch) {
        watch.stop();
        if (watchesByName.get(watch.name) == watch) {
            watchesByName.remove(watch.name);
        }
        removeWatch(watch, simpleWatches);
        removeWatch(watch, patternWatches);
    }

    private static void removeWatch(Watch watch, Map<Series, Map<String, Watch>> map) {
        String name = watch.name;
        Iterator<Entry<Series, Map<String, Watch>>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<Series, Map<String, Watch>> entry = iter.next();
            Map<String, Watch> watches = entry.getValue();
            if (watches.get(name) == watch) {
                watches.remove(name);
                if (watches.isEmpty()) {
                    iter.remove();
                }
                return;
            }
        }
    }

    @Override
    public Collection<Watch> watches() {
        List<Watch> all = new ArrayList<>();
        for (Map<?, Watch> watches : simpleWatches.values()) {
            all.addAll(watches.values());
        }
        for (Map<?, Watch> watches : patternWatches.values()) {
            all.addAll(watches.values());
        }
        return all;
    }

    @Override
    public Collection<Watch> wachtesFor(Series series) {
        if (!series.isPattern()) {
            // this is the usual path called while polling for data so this should not be too expensive
            Map<?, Watch> watches = simpleWatches.get(series);
            return watches == null ? emptyList() : unmodifiableCollection(watches.values());
        }
        if (series.equalTo(Series.ANY)) {
            return watches();
        }
        Map<?, Watch> seriesWatches = patternWatches.get(series);
        Collection<Watch> watches = seriesWatches == null ? emptyList() : seriesWatches.values();
        for (Map<?, Watch> simple : simpleWatches.values()) {
            for (Watch w : simple.values()) {
                if (series.matches(w.watched.series)) {
                    if (!(watches instanceof ArrayList)) {
                        watches = new ArrayList<>(watches);
                    }
                    watches.add(w);
                }
            }
        }
        return unmodifiableCollection(watches);
    }

    private void checkWatches() {
        long start = System.currentTimeMillis();
        collectWatches();
        try {
            checkWatches(simpleWatches.values());
            checkWatches(patternWatches.values());
            statistics.set(computeStatistics());
        } catch (Exception ex) {
            LOGGER.log(java.util.logging.Level.FINE, "Failed to check watches", ex);
        }
        evalLoopTime.set(System.currentTimeMillis() - start);
    }

    private void collectWatches() {
        List<MonitoringWatchSource> sources = serviceLocator.getAllServices(MonitoringWatchSource.class);
        Map<String, Watch> collectedBefore = collectedWatches;
        if (sources.isEmpty() && collectedBefore.isEmpty()) {
            return; // nothing to do
        }
        Set<String> notYetCollectedWatches = new HashSet<>(collectedBefore.keySet());
        MonitoringWatchCollector collector = new MonitoringWatchCollector() {
            @Override
            public WatchBuilder watch(CharSequence series, String name, String unit) {
                return new WatchBuilder() {
                    @Override
                    public WatchBuilder with(String level, long startThreshold, Number startForLast,
                            boolean startOnAverage, Long stopTheshold, Number stopForLast, boolean stopOnAverage) {
                        notYetCollectedWatches.remove(name);
                        Watch watch = collectedBefore.get(name);
                        if (watch == null) {
                            watch = new Watch(name, new Metric(new Series(series.toString()), Unit.fromShortName(unit)));
                        }
                        Watch updated = watch.with(level, startThreshold, startForLast, startOnAverage, stopTheshold,
                                stopForLast, stopOnAverage);
                        if (updated != watch) {
                            addWatch(updated); // this stops and removes existing watch for that name
                            collectedBefore.put(name, updated);
                        }
                        return this;
                    }
                };
            }
        };
        for (MonitoringWatchSource source : sources) {
            try {
                source.collect(collector);
            } catch (Exception ex) {
                LOGGER.log(java.util.logging.Level.FINE,
                        "Failed to collect watch source " + source.getClass().getSimpleName(), ex);
            }
        }
        if (!notYetCollectedWatches.isEmpty()) {
            for (String name : notYetCollectedWatches) {
                removeWatch(collectedBefore.get(name));
                collectedBefore.remove(name);
            }
        }
    }

    private AlertStatistics computeStatistics() {
        AlertStatistics stats = new AlertStatistics();
        stats.changeCount = Alert.getChangeCount();
        stats.watches = watchesByName.size();
        if (alerts.isEmpty()) {
            return stats;
        }
        for (Deque<Alert> seriesAlerts : alerts.values()) {
            for (Alert a : seriesAlerts) {
                if (a.getLevel() == Level.RED) {
                    if (a.isAcknowledged()) {
                        stats.acknowledgedRedAlerts++;
                    } else {
                        stats.unacknowledgedRedAlerts++;
                    }
                } else if (a.getLevel() == Level.AMBER) {
                    if (a.isAcknowledged()) {
                        stats.acknowledgedAmberAlerts++;
                    } else {
                        stats.unacknowledgedAmberAlerts++;
                    }
                }
            }
        }
        return stats;
    }

    private void checkWatches(Collection<? extends Map<?, Watch>> watches) {
        for (Map<?, Watch> group : watches) {
            for (Watch watch : group.values()) {
                if (watch.isStopped()) {
                    removeWatch(watch);
                } else {
                    try {
                        checkWatch(watch);
                    } catch (Exception ex) {
                        LOGGER.log(java.util.logging.Level.FINE, "Failed to check watch : " + watch, ex);
                    }
                }
            }
        }
    }

    private void checkWatch(Watch watch) {
        for (Alert newlyRaised : watch.check(monitoringData)) {
            Deque<Alert> seriesAlerts = alerts.computeIfAbsent(newlyRaised.getSeries(),
                    key -> new ConcurrentLinkedDeque<>());
            seriesAlerts.add(newlyRaised);
            if (seriesAlerts.size() > MAX_ALERTS_PER_SERIES) {
                if (!removeFirst(seriesAlerts, Alert::isAcknowledged)) {
                    if (!removeFirst(seriesAlerts, alert -> alert.getLevel() == Level.AMBER)) {
                        seriesAlerts.removeFirst();
                    }
                }
            }
        }
    }

    private static boolean removeFirst(Deque<Alert> alerts, Predicate<Alert> test) {
        Iterator<Alert> iter = alerts.iterator();
        while (iter.hasNext()) {
            Alert a = iter.next();
            if (test.test(a)) {
                iter.remove();
                return true;
            }
        }
        return false;
    }

}
