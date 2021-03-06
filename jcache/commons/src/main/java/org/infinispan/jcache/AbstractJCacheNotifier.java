package org.infinispan.jcache;

import java.io.Closeable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import javax.cache.Cache;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.Factory;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.EventType;

import org.infinispan.commons.logging.LogFactory;
import org.infinispan.jcache.logging.Log;

/**
 * JCache notifications dispatcher.
 *
 * TODO: Deal with asynchronous listeners...
 *
 * @author Galder Zamarreño
 * @since 5.3
 */
public abstract class AbstractJCacheNotifier<K, V> implements Closeable {

   private static final Log log =
         LogFactory.getLog(AbstractJCacheNotifier.class, Log.class);

   // Traversals are a not more common than mutations when it comes to
   // keeping track of registered listeners, so use copy-on-write lists.

   private final List<CacheEntryCreatedListener<K, V>> createdListeners =
         new CopyOnWriteArrayList<CacheEntryCreatedListener<K, V>>();

   private final List<CacheEntryUpdatedListener<K, V>> updatedListeners =
         new CopyOnWriteArrayList<CacheEntryUpdatedListener<K, V>>();

   private final List<CacheEntryRemovedListener<K, V>> removedListeners =
         new CopyOnWriteArrayList<CacheEntryRemovedListener<K, V>>();

   private final List<CacheEntryExpiredListener<K, V>> expiredListeners =
         new CopyOnWriteArrayList<CacheEntryExpiredListener<K, V>>();

   private final ConcurrentMap<CacheEntryListener<? super K, ? super V>, CacheEntryListenerConfiguration<K, V>> listenerCfgs =
         new ConcurrentHashMap<>();

   private AbstractJCacheListenerAdapter<K,V> listenerAdapter;

   private ConcurrentMap<EventSource<K, V>, Queue<CountDownLatch>> latchesByEventSource = new ConcurrentHashMap<>();

   public void addListener(CacheEntryListenerConfiguration<K, V> reg,
         AbstractJCache<K, V> jcache, AbstractJCacheNotifier<K, V> notifier) {
      boolean addListenerAdapter = listenerCfgs.isEmpty();
      addListener(reg, false);

      if (addListenerAdapter) {
         listenerAdapter = createListenerAdapter(jcache, notifier);
         jcache.addListener(listenerAdapter);
      }
   }

   public void removeListener(CacheEntryListenerConfiguration<K, V> reg,
         AbstractJCache<K, V> jcache) {
      removeListener(reg);

      if (listenerCfgs.isEmpty())
         jcache.removeListener(listenerAdapter);
   }

   public void addSyncNotificationLatch(Cache<K, V> cache, K key, V value, CountDownLatch latch) {
      EventSource<K, V> eventSourceKey = new EventSource<K, V>(cache, key, value);

      latchesByEventSource.computeIfAbsent(eventSourceKey, kvEventSource -> new ConcurrentLinkedQueue<>()).add(latch);
   }

   public void removeSyncNotificationLatch(Cache<K, V> cache, K key, V value, CountDownLatch latch) {
      EventSource<K, V> eventSourceKey = new EventSource<K, V>(cache, key, value);

      Queue<CountDownLatch> latches = latchesByEventSource.get(eventSourceKey);

      if (latches == null) {
         return;
      }

      latchesByEventSource.compute(eventSourceKey, (kvEventSource, countDownLatches) -> {
         countDownLatches.remove(latch);
         return countDownLatches.isEmpty() ? null : countDownLatches;
      });
   }

   private void notifySync(Cache<K, V> cache, K key, V value) {
      EventSource<K, V> eventSourceKey = new EventSource<K, V>(cache, key, value);

      notifySync(latchesByEventSource.get(eventSourceKey));
   }

   private void notifySync(Queue<CountDownLatch> latches) {
      if (latches == null) {
         return;
      }
      CountDownLatch latch = latches.poll();
      if (latch != null) {
         latch.countDown();
      }
   }

   public void notifyEntryCreated(Cache<K, V> cache, K key, V value) {
      try {
         if (!createdListeners.isEmpty()) {
            List<CacheEntryEvent<? extends K, ? extends V>> events =
               eventAsList(new RICacheEntryEvent<>(cache, key, value, EventType.CREATED));
            for (CacheEntryCreatedListener<K, V> listener : createdListeners)
               listener.onCreated(getEntryIterable(events, listenerCfgs.get(listener)));
         }
      } finally {
         notifySync(cache, key, value);
      }
   }

   public void notifyEntryUpdated(Cache<K, V> cache, K key, V value, V prev) {
      try {
         if (!updatedListeners.isEmpty()) {
            List<CacheEntryEvent<? extends K, ? extends V>> events =
               eventAsList(new RICacheEntryEvent<>(cache, key, value, prev, EventType.UPDATED));
            for (CacheEntryUpdatedListener<K, V> listener : updatedListeners)
               listener.onUpdated(getEntryIterable(events, listenerCfgs.get(listener)));
         }
      } finally {
         notifySync(cache, key, value);
      }
   }

   public void notifyEntryRemoved(Cache<K, V> cache, K key, V value, V prev) {
      try {
         if (prev == null) {
            // When we do a remove and the value is not present, we still need to set entry.setRemoved(true)
            // to make sure the remove occurs in persistence. However this fires a remove event, too.
            // JCache does not allow remove events with null previous value.
            return;
         }
         if (!removedListeners.isEmpty()) {
            List<CacheEntryEvent<? extends K, ? extends V>> events =
               eventAsList(new RICacheEntryEvent<>(cache, key, value, prev, EventType.REMOVED));
            for (CacheEntryRemovedListener<K, V> listener : removedListeners) {
               listener.onRemoved(getEntryIterable(events, listenerCfgs.get(listener)));
            }
         }
      } finally {
         notifySync(cache, key, null);
      }
   }

   public void notifyEntryExpired(Cache<K, V> cache, K key, V value) {
      if (!expiredListeners.isEmpty()) {
         List<CacheEntryEvent<? extends K, ? extends V>> events =
            eventAsList(new RICacheEntryEvent<>(cache, key, value, EventType.EXPIRED));
         for (CacheEntryExpiredListener<K, V> listener : expiredListeners) {
            listener.onExpired(getEntryIterable(events, listenerCfgs.get(listener)));
         }
      }
   }

   public boolean hasSyncCreatedListener() {
      return hasSyncListener(CacheEntryCreatedListener.class);
   }

   public boolean hasSyncRemovedListener() {
      return hasSyncListener(CacheEntryRemovedListener.class);
   }

   public boolean hasSyncUpdatedListener() {
      return hasSyncListener(CacheEntryUpdatedListener.class);
   }

   private boolean hasSyncListener(Class<?> listenerClass) {
      for (Map.Entry<CacheEntryListener<? super K, ? super V>, CacheEntryListenerConfiguration<K, V>> entry : listenerCfgs.entrySet()) {
         if (entry.getValue().isSynchronous() && listenerClass.isInstance(entry.getKey())) {
            return true;
         }
      }
      return false;
   }

   private Iterable<CacheEntryEvent<? extends K, ? extends V>> getEntryIterable(
         List<CacheEntryEvent<? extends K, ? extends V>> events,
         CacheEntryListenerConfiguration<K, V> listenerCfg) {
      CacheEntryEventFilter<? super K, ? super V> filter = createFilterIfNeeded(listenerCfg);
      return events.stream()
         .filter(e -> filter == null || filter.evaluate(e))
         .map(e -> launderEvent(e, listenerCfg))
         .collect(Collectors.toList());
   }

   private CacheEntryEvent<? extends K, ? extends V> launderEvent(
      CacheEntryEvent<? extends K, ? extends V> e, CacheEntryListenerConfiguration<K, V> cfg) {
      switch (e.getEventType()) {
         case UPDATED:
         case REMOVED:
         case EXPIRED:
            if (cfg.isOldValueRequired())
               return e;

            // Since JCache 1.1, removed & expired events have to return oldValue or null when oldValueRequired == false
            // RI chooses to return null as old value and oldValueAvailable is false in this case
            if (e.getEventType() == EventType.REMOVED || e.getEventType() == EventType.EXPIRED)
               return new RICacheEntryEvent<>(e.getSource(), e.getKey(), null, null,
                  e.getEventType(), false);

            return new RICacheEntryEvent(e.getSource(), e.getKey(), e.getValue(), e.getEventType());

         default:
            return e;
      }
   }

   protected CacheEntryEventFilter<? super K,? super V> createFilterIfNeeded(CacheEntryListenerConfiguration<K, V> listenerCfg) {
      Factory<CacheEntryEventFilter<? super K,? super V>> factory = listenerCfg.getCacheEntryEventFilterFactory();
      return factory != null ? factory.create() : null;
   }

   @SuppressWarnings("unchecked")
   private boolean addListener(CacheEntryListenerConfiguration<K, V> listenerCfg, boolean addIfAbsent) {
      boolean added = false;
      CacheEntryListener<? super K, ? super V> listener =
            listenerCfg.getCacheEntryListenerFactory().create();
      if (listener instanceof CacheEntryCreatedListener)
         added = !containsListener(addIfAbsent, listener, createdListeners)
               && createdListeners.add((CacheEntryCreatedListener<K, V>) listener);

      if (listener instanceof CacheEntryUpdatedListener)
         added = !containsListener(addIfAbsent, listener, updatedListeners)
               && updatedListeners.add((CacheEntryUpdatedListener<K, V>) listener);

      if (listener instanceof CacheEntryRemovedListener)
         added = !containsListener(addIfAbsent, listener, removedListeners)
               && removedListeners.add((CacheEntryRemovedListener<K, V>) listener);

      if (listener instanceof CacheEntryExpiredListener)
         added = !containsListener(addIfAbsent, listener, expiredListeners)
               && expiredListeners.add((CacheEntryExpiredListener<K, V>) listener);

      if (added)
         listenerCfgs.put(listener, listenerCfg);

      return added;
   }

   private boolean containsListener(boolean addIfAbsent,
         CacheEntryListener<? super K, ? super V> listenerToAdd,
         List<? extends CacheEntryListener<? super K, ? super V>> listeners) {
      // If add only if no listener present, check the listeners collection
      if (addIfAbsent) {
         for (CacheEntryListener<? super K, ? super V> listener : listeners) {
            if (listener.equals(listenerToAdd))
               return true;
         }
      }

      return false;
   }

   private void removeListener(CacheEntryListenerConfiguration<K, V> listenerCfg) {
      Iterator<Map.Entry<CacheEntryListener<? super K, ? super V>, CacheEntryListenerConfiguration<K, V>>> it;
      for (it = listenerCfgs.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<CacheEntryListener<? super K, ? super V>, CacheEntryListenerConfiguration<K, V>> entry = it.next();
         CacheEntryListenerConfiguration<K, V> cfg = entry.getValue();
         if (cfg.equals(listenerCfg)) {
            CacheEntryListener<? super K, ? super V> listener = entry.getKey();
            if (listener instanceof CacheEntryCreatedListener)
               createdListeners.remove(listener);

            if (listener instanceof CacheEntryUpdatedListener)
               updatedListeners.remove(listener);

            if (listener instanceof CacheEntryRemovedListener)
               removedListeners.remove(listener);

            if (listener instanceof CacheEntryExpiredListener)
               expiredListeners.remove(listener);

            it.remove();
         }
      }
   }

   private List<CacheEntryEvent<? extends K, ? extends V>> eventAsList(
         CacheEntryEvent<? extends K, ? extends V> event) {
      List<CacheEntryEvent<? extends K, ? extends V>> events =
            Collections.<CacheEntryEvent<? extends K, ? extends V>>singletonList(event);
      if (log.isTraceEnabled()) log.tracef("Received event: %s", events);
      return events;
   }

   protected abstract AbstractJCacheListenerAdapter<K, V> createListenerAdapter(AbstractJCache<K, V> jcache, AbstractJCacheNotifier<K, V> notifier);

   @Override
   public void close() {
      Closeables.close(listenerCfgs.keySet());
   }

   private static class EventSource<K, V> {
      private final Cache<K, V> cache;
      private final K key;
      private final V value;

      public EventSource(final Cache<K, V> cache, final K key, final V value) {
         this.cache = cache;
         this.key = key;
         this.value = value;
      }

      @Override
      public boolean equals(Object obj) {
         if (!(obj instanceof EventSource)) {
            return false;
         }
         EventSource<?, ?> otherEventSource = (EventSource<?, ?>) obj;
         return equalOrNull(cache, otherEventSource.cache)
               && equalOrNull(key, otherEventSource.key)
               && equalOrNull(value, otherEventSource.value);
      }

      private static boolean equalOrNull(Object obj1, Object obj2) {
         return ((obj1 == null) && (obj2 == null)) || ((obj1 != null) && obj1.equals(obj2));
      }

      @Override
      public int hashCode() {
         int result = 1;
         result = 31 * result + (cache == null ? 0 : cache.hashCode());
         result = 31 * result + (key == null ? 0 : key.hashCode());
         result = 31 * result + (value == null ? 0 : value.hashCode());
         return result;
      }
   }
}
