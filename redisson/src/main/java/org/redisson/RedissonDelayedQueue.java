/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RDelayedQueue;
import org.redisson.api.RFuture;
import org.redisson.api.RTopic;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.RedissonPromise;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ThreadLocalRandom;

/**
 * 
 * @author Nikita Koksharov
 *
 * @param <V> value type
 */
public class RedissonDelayedQueue<V> extends RedissonExpirable implements RDelayedQueue<V> {

    private final QueueTransferService queueTransferService;
    
    protected RedissonDelayedQueue(QueueTransferService queueTransferService, Codec codec, final CommandAsyncExecutor commandExecutor, String name) {
        super(codec, commandExecutor, name);
        
        QueueTransferTask task = new QueueTransferTask(commandExecutor.getConnectionManager()) {
            
            @Override
            protected RFuture<Long> pushTaskAsync() {
                return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_LONG,
                        "local expiredValues = redis.call('zrangebyscore', KEYS[2], 0, ARGV[1], 'limit', 0, ARGV[2]); "
                      + "if #expiredValues > 0 then "
                          + "for i, v in ipairs(expiredValues) do "
                              + "local randomId, value = struct.unpack('dLc0', v);"
                              + "redis.call('rpush', KEYS[1], value);"
                              + "redis.call('lrem', KEYS[3], 1, v);"
                          + "end; "
                          + "redis.call('zrem', KEYS[2], unpack(expiredValues));"
                      + "end; "
                        // get startTime from scheduler queue head task
                      + "local v = redis.call('zrange', KEYS[2], 0, 0, 'WITHSCORES'); "
                      + "if v[1] ~= nil then "
                         + "return v[2]; "
                      + "end "
                      + "return nil;",
                      Arrays.<Object>asList(getName(), getTimeoutSetName(), getQueueName()), 
                      System.currentTimeMillis(), 100);
            }
            
            @Override
            protected RTopic<Long> getTopic() {
                return new RedissonTopic<Long>(LongCodec.INSTANCE, commandExecutor, getChannelName());
            }
        };
        
        queueTransferService.schedule(getQueueName(), task);
        
        this.queueTransferService = queueTransferService;
    }

    private String getChannelName() {
        return prefixName("redisson_delay_queue_channel", getName());
    }
    
    private String getQueueName() {
        return prefixName("redisson_delay_queue", getName());
    }
    
    private String getTimeoutSetName() {
        return prefixName("redisson_delay_queue_timeout", getName());
    }

    public void offer(V e, long delay, TimeUnit timeUnit) {
        get(offerAsync(e, delay, timeUnit));
    }
    
    public RFuture<Void> offerAsync(V e, long delay, TimeUnit timeUnit) {
        long delayInMs = timeUnit.toMillis(delay);
        long timeout = System.currentTimeMillis() + delayInMs;
     
        long randomId = PlatformDependent.threadLocalRandom().nextLong();
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_VOID,
                "local value = struct.pack('dLc0', tonumber(ARGV[2]), string.len(ARGV[3]), ARGV[3]);" 
              + "redis.call('zadd', KEYS[2], ARGV[1], value);"
              + "redis.call('rpush', KEYS[3], value);"
              // if new object added to queue head when publish its startTime 
              // to all scheduler workers 
              + "local v = redis.call('zrange', KEYS[2], 0, 0); "
              + "if v[1] == value then "
                 + "redis.call('publish', KEYS[4], ARGV[1]); "
              + "end;"
                 ,
              Arrays.<Object>asList(getName(), getTimeoutSetName(), getQueueName(), getChannelName()), 
              timeout, randomId, encode(e));
    }

    @Override
    public boolean add(V e) {
        throw new UnsupportedOperationException("Use 'offer' method with timeout param");
    }

    @Override
    public boolean offer(V e) {
        throw new UnsupportedOperationException("Use 'offer' method with timeout param");
    }

    @Override
    public V remove() {
        V value = poll();
        if (value == null) {
            throw new NoSuchElementException();
        }
        return value;
    }

    @Override
    public V poll() {
        return get(pollAsync());
    }

    @Override
    public V element() {
        V value = peek();
        if (value == null) {
            throw new NoSuchElementException();
        }
        return value;
    }

    @Override
    public V peek() {
        return get(peekAsync());
    }

    @Override
    public int size() {
        return get(sizeAsync());
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        return get(containsAsync(o));
    }

    V getValue(int index) {
        return (V)get(commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_OBJECT,
                "local v = redis.call('lindex', KEYS[1], ARGV[1]); "
              + "if v ~= false then "
                  + "local randomId, value = struct.unpack('dLc0', v);"
                  + "return value; "
              + "end "
              + "return nil;",
              Arrays.<Object>asList(getQueueName()), index));
    }
    
    void remove(int index) {
        get(commandExecutor.evalWriteAsync(getName(), null, RedisCommands.EVAL_VOID,
                "local v = redis.call('lindex', KEYS[1], ARGV[1]);" + 
                "if v ~= false then " + 
                   "local randomId, value = struct.unpack('dLc0', v);" + 
                   "redis.call('lrem', KEYS[1], 1, v);" + 
                   "redis.call('zrem', KEYS[2], v);" +
                "end; ",
                Arrays.<Object>asList(getQueueName(), getTimeoutSetName()), index));
    }
    
    @Override
    public Iterator<V> iterator() {
        return new Iterator<V>() {

            private V nextCurrentValue;
            private V currentValueHasRead;
            private int currentIndex = -1;
            private boolean hasBeenModified = true;

            @Override
            public boolean hasNext() {
                V val = RedissonDelayedQueue.this.getValue(currentIndex+1);
                if (val != null) {
                    nextCurrentValue = val;
                }
                return val != null;
            }

            @Override
            public V next() {
                if (nextCurrentValue == null && !hasNext()) {
                    throw new NoSuchElementException("No such element at index " + currentIndex);
                }
                currentIndex++;
                currentValueHasRead = nextCurrentValue;
                nextCurrentValue = null;
                hasBeenModified = false;
                return currentValueHasRead;
            }

            @Override
            public void remove() {
                if (currentValueHasRead == null) {
                    throw new IllegalStateException("Neither next nor previous have been called");
                }
                if (hasBeenModified) {
                    throw new IllegalStateException("Element been already deleted");
                }
                RedissonDelayedQueue.this.remove(currentIndex);
                currentIndex--;
                hasBeenModified = true;
                currentValueHasRead = null;
            }

        };
    }

    @Override
    public Object[] toArray() {
        List<V> list = readAll();
        return list.toArray();
    }
    
    @Override
    public <T> T[] toArray(T[] a) {
        List<V> list = readAll();
        return list.toArray(a);
    }

    @Override
    public List<V> readAll() {
        return get(readAllAsync());
    }

    @Override
    public RFuture<List<V>> readAllAsync() {
        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_LIST,
                "local result = {}; " +
                "local items = redis.call('lrange', KEYS[1], 0, -1); "
              + "for i, v in ipairs(items) do "
                   + "local randomId, value = struct.unpack('dLc0', v); "
                   + "table.insert(result, value);"
              + "end; "
              + "return result; ",
           Collections.<Object>singletonList(getQueueName()));
    }

    @Override
    public boolean remove(Object o) {
        return get(removeAsync(o));
    }

    @Override
    public RFuture<Boolean> removeAsync(Object o) {
        return removeAsync(o, 1);
    }

    protected RFuture<Boolean> removeAsync(Object o, int count) {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local s = redis.call('llen', KEYS[1]);" +
                "for i = 0, s-1, 1 do "
                    + "local v = redis.call('lindex', KEYS[1], i);"
                    + "local randomId, value = struct.unpack('dLc0', v);"
                    + "if ARGV[1] == value then "
                        + "redis.call('zrem', KEYS[2], v);"
                        + "redis.call('lrem', KEYS[1], 1, v);"
                        + "return 1;"
                    + "end; "
               + "end;" +
               "return 0;",
        Arrays.<Object>asList(getQueueName(), getTimeoutSetName()), encode(o));
    }

    @Override
    public RFuture<Boolean> containsAllAsync(Collection<?> c) {
        if (c.isEmpty()) {
            return RedissonPromise.newSucceededFuture(true);
        }

        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local s = redis.call('llen', KEYS[1]);" +
                "for i = 0, s-1, 1 do "
                    + "local v = redis.call('lindex', KEYS[1], i);"
                    + "local randomId, value = struct.unpack('dLc0', v);"
                    
                    + "for j = 1, #ARGV, 1 do "
                        + "if value == ARGV[j] then "
                          + "table.remove(ARGV, j) "
                        + "end; "
                    + "end; "
               + "end;" +
               "return #ARGV == 0 and 1 or 0;",
                Collections.<Object>singletonList(getQueueName()), encode(c).toArray());
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return get(containsAllAsync(c));
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        throw new UnsupportedOperationException("Use 'offer' method with timeout param");
    }

    @Override
    public RFuture<Boolean> removeAllAsync(Collection<?> c) {
        if (c.isEmpty()) {
            return RedissonPromise.newSucceededFuture(false);
        }

        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local result = 0;" + 
                "local s = redis.call('llen', KEYS[1]);" + 
                "local i = 0;" +
                "while i < s do "
                    + "local v = redis.call('lindex', KEYS[1], i);"
                    + "local randomId, value = struct.unpack('dLc0', v);"
                    
                    + "for j = 1, #ARGV, 1 do "
                        + "if value == ARGV[j] then "
                            + "result = 1; "
                            + "i = i - 1; "
                            + "s = s - 1; "
                            + "redis.call('zrem', KEYS[2], v);"
                            + "redis.call('lrem', KEYS[1], 0, v); "
                            + "break; "
                        + "end; "
                    + "end; "
                    + "i = i + 1;"
               + "end; " 
               + "return result;",
               Arrays.<Object>asList(getQueueName(), getTimeoutSetName()), encode(c).toArray());
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return get(removeAllAsync(c));
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return get(retainAllAsync(c));
    }

    @Override
    public RFuture<Boolean> retainAllAsync(Collection<?> c) {
        if (c.isEmpty()) {
            return deleteAsync();
        }

        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                     "local changed = 0; " +
                     "local items = redis.call('lrange', KEYS[1], 0, -1); "
                   + "local i = 1; "
                   + "while i <= #items do "
                        + "local randomId, element = struct.unpack('dLc0', items[i]); "
                        + "local isInAgrs = false; "
                        + "for j = 1, #ARGV, 1 do "
                            + "if ARGV[j] == element then "
                                + "isInAgrs = true; "
                                + "break; "
                            + "end; "
                        + "end; "
                        + "if isInAgrs == false then "
                            + "redis.call('LREM', KEYS[1], 0, items[i]) "
                            + "changed = 1; "
                        + "end; "
                        + "i = i + 1; "
                   + "end; "
                   + "return changed; ",
                Collections.<Object>singletonList(getQueueName()), encode(c).toArray());
    }  

    @Override
    public void clear() {
        delete();
    }
    
    @Override
    public RFuture<Boolean> deleteAsync() {
        return commandExecutor.writeAsync(getName(), RedisCommands.DEL_OBJECTS, getQueueName(), getTimeoutSetName());
    }

    @Override
    public RFuture<V> peekAsync() {
        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_OBJECT,
                "local v = redis.call('lindex', KEYS[1], 0); "
              + "if v ~= false then "
                  + "local randomId, value = struct.unpack('dLc0', v);"
                  + "return value; "
              + "end "
              + "return nil;",
              Arrays.<Object>asList(getQueueName()));
    }

    @Override
    public RFuture<V> pollAsync() {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_OBJECT,
                  "local v = redis.call('lpop', KEYS[1]); "
                + "if v ~= false then "
                    + "redis.call('zrem', KEYS[2], v); "
                    + "local randomId, value = struct.unpack('dLc0', v);"
                    + "return value; "
                + "end "
                + "return nil;",
                Arrays.<Object>asList(getQueueName(), getTimeoutSetName()));
    }

    @Override
    public RFuture<Boolean> offerAsync(V e) {
        throw new UnsupportedOperationException("Use 'offer' method with timeout param");
    }

    @Override
    public RFuture<V> pollLastAndOfferFirstToAsync(String queueName) {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_OBJECT,
                "local v = redis.call('rpop', KEYS[1]); "
              + "if v ~= false then "
                  + "redis.call('zrem', KEYS[2], v); "
                  + "local randomId, value = struct.unpack('dLc0', v);"
                  + "redis.call('lpush', KEYS[3], value); "
                  + "return value; "
              + "end "
              + "return nil;",
              Arrays.<Object>asList(getQueueName(), getTimeoutSetName(), queueName));
    }

    @Override
    public RFuture<Boolean> containsAsync(Object o) {
        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                        "local s = redis.call('llen', KEYS[1]);" +
                        "for i = 0, s-1, 1 do "
                            + "local v = redis.call('lindex', KEYS[1], i);"
                            + "local randomId, value = struct.unpack('dLc0', v);"
                            + "if ARGV[1] == value then "
                                + "return 1;"
                            + "end; "
                       + "end;" +
                       "return 0;",
                Collections.<Object>singletonList(getQueueName()), encode(o));
    }

    @Override
    public RFuture<Integer> sizeAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.LLEN_INT, getQueueName());
    }

    @Override
    public RFuture<Boolean> addAsync(V e) {
        throw new UnsupportedOperationException("Use 'offer' method with timeout param");
    }

    @Override
    public RFuture<Boolean> addAllAsync(Collection<? extends V> c) {
        throw new UnsupportedOperationException("Use 'offer' method with timeout param");
    }

    @Override
    public V pollLastAndOfferFirstTo(String dequeName) {
        return get(pollLastAndOfferFirstToAsync(dequeName));
    }

    @Override
    public void destroy() {
        queueTransferService.remove(getQueueName());
    }
    
}
