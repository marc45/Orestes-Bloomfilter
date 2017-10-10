package orestes.bloomfilter.test.cachesketch;

import com.google.common.collect.Lists;
import orestes.bloomfilter.FilterBuilder;
import orestes.bloomfilter.cachesketch.ExpirationQueue;
import orestes.bloomfilter.cachesketch.ExpirationQueue.ExpiringItem;
import orestes.bloomfilter.cachesketch.ExpirationQueueMemory;
import orestes.bloomfilter.cachesketch.ExpirationQueueRedis;
import orestes.bloomfilter.memory.BloomFilterMemory;
import orestes.bloomfilter.redis.helper.RedisPool;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.Transaction;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.*;

/**
 * Created on 04.10.17.
 *
 * @author Konstantin Simon Maria Möllers
 */
@RunWith(Parameterized.class)
public class ExpirationQueueTest {

    private final boolean inMemory;
    private ExpirationQueue<String> queue;
    private int handlerCallsCount;
    private RedisPool pool;

    @Parameterized.Parameters(name = "Expiration Queue test {0}")
    public static Collection<Object[]> data() throws Exception {
        Object[][] data = {{"in-memory", true}, {"with redis", false}};

        return Arrays.asList(data);
    }

    public ExpirationQueueTest(String name, boolean inMemory) {
        this.inMemory = inMemory;
    }

    @Before
    public void setUp() throws Exception {
        handlerCallsCount = 0;
        final Consumer<ExpiringItem<String>> handler = it -> handlerCallsCount++;
        if (inMemory) {
            queue = new ExpirationQueueMemory<>(handler);
        } else {
            pool = RedisPool.builder().host("localhost").port(6379).redisConnections(10).database(Protocol.DEFAULT_DATABASE).build();
            final String s = pool.safelyReturn(p -> p.set("erich", "ksm"));
            queue = new ExpirationQueueRedis(pool, "queue", this::expirationHandler);
        }
    }

    @Test
    public void testAddElements() throws Exception {
        assertEquals(0, queue.size());

        assertFalse(queue.contains("demo"));
        assertFalse(queue.contains("foo"));
        assertFalse(queue.contains("bar"));

        assertTrue(queue.addTTL("demo", 10, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("foo", 10, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("bar", 10, TimeUnit.SECONDS));

        assertEquals(3, queue.size());

        assertTrue(queue.contains("demo"));
        assertTrue(queue.contains("foo"));
        assertTrue(queue.contains("bar"));
    }

    @Test
    public void testRemoveElements() throws Exception {
        if (!inMemory)
            return;
        assertTrue(queue.addTTL("demo", 10, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("foo", 10, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("bar", 10, TimeUnit.SECONDS));

        assertEquals(3, queue.size());

        assertTrue(queue.remove("demo"));
        assertTrue(queue.remove("foo"));
        assertTrue(queue.remove("bar"));

        assertEquals(0, queue.size());

        assertFalse(queue.contains("demo"));
        assertFalse(queue.contains("foo"));
        assertFalse(queue.contains("bar"));
    }

    @Test
    public void testElementsAreRemoved() throws Exception {
        assertTrue(queue.addTTL("demo", 1, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("foo", 1, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("bar", 1, TimeUnit.SECONDS));
        assertEquals(3, queue.size());
        assertTrue(queue.contains("demo"));
        assertTrue(queue.contains("foo"));
        assertTrue(queue.contains("bar"));

        if (queue instanceof ExpirationQueueRedis) {
            ((ExpirationQueueRedis) queue).triggerExpirationHandling(1, TimeUnit.SECONDS);
        }

        Thread.sleep(1500);
        assertEquals(0, queue.size());
        assertEquals(3, handlerCallsCount);
        assertFalse(queue.contains("demo"));
        assertFalse(queue.contains("foo"));
        assertFalse(queue.contains("bar"));
    }

    @Test
    public void testIterable() throws Exception {
        assertTrue(queue.addTTL("demo", 12, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("foo", 11, TimeUnit.SECONDS));
        assertTrue(queue.addTTL("bar", 10, TimeUnit.SECONDS));

        int calls = 0;
        for (String item : queue) {
            calls++;
        }
        assertEquals(3, calls);

        final Set<String> elements = new HashSet<>(Lists.newArrayList(queue));
        assertEquals(new HashSet<>(Arrays.asList("bar", "demo", "foo")), elements);
    }

    @Test
    public void testAddDuplicateElements() throws Exception {
        assertEquals(0, queue.size());

        assertFalse(queue.contains("demo"));

        assertTrue(queue.addTTL("demo", 1, TimeUnit.MILLISECONDS));
        assertTrue(queue.addTTL("demo", 2, TimeUnit.SECONDS));


        assertEquals(2, queue.size());
        assertTrue(queue.contains("demo"));

        if (queue instanceof ExpirationQueueRedis) {
            ((ExpirationQueueRedis) queue).triggerExpirationHandling(1, TimeUnit.SECONDS);
        }

        Thread.sleep(1500);
        assertEquals(1, queue.size());
        assertTrue(queue.contains("demo"));

        Thread.sleep(1500);
        assertEquals(0, queue.size());
        assertFalse(queue.contains("demo"));
    }

    /**
     * Handles expiring items from the expiration queue.
     *
     * @param queue The queue that may have expired items.
     *
     * @return true if successful, false otherwise.
     */
    private boolean expirationHandler(ExpirationQueueRedis queue) {
        return pool.safelyReturn(p -> {
            final List<String> uniqueQueueKeys = queue.getExpiredItems(p);
            final List<String> expiredElems = new ArrayList<>(uniqueQueueKeys);
            handlerCallsCount += uniqueQueueKeys.size();

            // If no element is expired, we have nothing to do
            if (expiredElems.isEmpty()) {
                return true;
            }

            // Remove expired elements from queue
            Transaction t = p.multi();
            queue.removeElements(expiredElems, t);

            boolean success = t.exec() != null;

            return success;
        });
    }
}