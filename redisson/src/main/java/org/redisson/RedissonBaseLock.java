package org.redisson;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.convertor.IntegerReplayConvertor;
import org.redisson.client.protocol.decoder.MapValueDecoder;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandBatchService;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.misc.CompletableFutureWrapper;
import org.redisson.misc.WrappedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;

/**
 * 分布式锁基类
 *
 * @author Danila Varatyntsev
 * @author Nikita Koksharov
 */
public abstract class RedissonBaseLock extends RedissonExpirable implements RLock {
    private static final Logger log = LoggerFactory.getLogger(RedissonBaseLock.class);

    /** UUID、UUID + 业务锁标识、内置默认过期时间（30s）、过期项Map（key为UUID + 业务锁标识，value为过期项，用于管理过期时间自动续期） */
    final String id;
    final String entryName;
    protected long internalLockLeaseTime;
    private static final ConcurrentMap<String, ExpirationEntry> EXPIRATION_RENEWAL_MAP = new ConcurrentHashMap<>();

    /** 构造方法 */
    public RedissonBaseLock(CommandAsyncExecutor commandExecutor, String name) {
        super(commandExecutor, name);
        this.id = getServiceManager().getId();
        this.internalLockLeaseTime = getServiceManager().getCfg().getLockWatchdogTimeout();
        this.entryName = id + ":" + name;
    }

    /** 看门狗自动续期 */
    protected void scheduleExpirationRenewal(long threadId) {
        ExpirationEntry entry = new ExpirationEntry();
        ExpirationEntry oldEntry = EXPIRATION_RENEWAL_MAP.putIfAbsent(getEntryName(), entry);
        if (oldEntry != null) {
            oldEntry.addThreadId(threadId);
        } else {
            entry.addThreadId(threadId);
            try {
                // 自动续期
                renewExpiration();
            } finally {
                if (Thread.currentThread().isInterrupted()) {
                    cancelExpirationRenewal(threadId, null);
                }
            }
        }
    }
    private void renewExpiration() {
        ExpirationEntry ee = EXPIRATION_RENEWAL_MAP.get(getEntryName());
        if (ee == null) {
            return;
        }
        // 基于Netty HashedWheelTimer时间轮定时器，执行自动续期任务，调度周期为internalLockLeaseTime / 3 = 30s / 3 = 10s
        Timeout task = getServiceManager().newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                ExpirationEntry ent = EXPIRATION_RENEWAL_MAP.get(getEntryName());
                if (ent == null) {
                    return;
                }
                Long threadId = ent.getFirstThreadId();
                if (threadId == null) {
                    return;
                }
                // LUA脚本自动续期
                CompletionStage<Boolean> future = renewExpirationAsync(threadId);
                future.whenComplete((res, e) -> {
                    if (e != null) {
                        log.error("Can't update lock {} expiration", getRawName(), e);
                        EXPIRATION_RENEWAL_MAP.remove(getEntryName());
                        return;
                    }
                    if (res) {
                        // 若续期成功，则提交下一次自动续期任务
                        renewExpiration();
                    } else {
                        // 若续期失败，则说明锁已经释放，则取消自动续期
                        cancelExpirationRenewal(null, null);
                    }
                });
            }
        }, internalLockLeaseTime / 3, TimeUnit.MILLISECONDS);
        ee.setTimeout(task);
    }
    /** LUA脚本：锁续期 */
    protected CompletionStage<Boolean> renewExpirationAsync(long threadId) {
        // KEYS[1]为key业务锁标识，ARGV[1]为锁过期时间，ARGV[2]为field锁名称（UUID + 业务线程ID）
        return evalWriteSyncedAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                        "return 1; " +
                        "end; " +
                        "return 0;",
                Collections.singletonList(getRawName()),
                internalLockLeaseTime, getLockName(threadId));
    }
    protected String getLockName(long threadId) {
        return id + ":" + threadId;
    }

    /** 取消看门狗自动续期：移除过期项 */
    protected void cancelExpirationRenewal(Long threadId, Boolean unlockResult) {
        ExpirationEntry task = EXPIRATION_RENEWAL_MAP.get(getEntryName());
        if (task == null) {
            return;
        }
        if (threadId != null) {
            task.removeThreadId(threadId);
        }
        if (threadId == null || task.hasNoThreads()) {
            Timeout timeout = task.getTimeout();
            if (timeout != null) {
                timeout.cancel();
            }
            EXPIRATION_RENEWAL_MAP.remove(getEntryName());
        }
    }

    /** 释放锁 */
    @Override
    public void unlock() {
        try {
            get(unlockAsync(Thread.currentThread().getId()));
        } catch (RedisException e) {
            if (e.getCause() instanceof IllegalMonitorStateException) {
                throw (IllegalMonitorStateException) e.getCause();
            } else {
                throw e;
            }
        }
    }
    @Override
    public boolean forceUnlock() {
        return get(forceUnlockAsync());
    }
    @Override
    public RFuture<Void> unlockAsync(long threadId) {
        return getServiceManager().execute(() -> unlockAsync0(threadId));
    }
    private RFuture<Void> unlockAsync0(long threadId) {
        CompletionStage<Boolean> future = unlockInnerAsync(threadId);
        CompletionStage<Void> f = future.handle((res, e) -> {
            cancelExpirationRenewal(threadId, res);
            if (e != null) {
                if (e instanceof CompletionException) {
                    throw (CompletionException) e;
                }
                throw new CompletionException(e);
            }
            if (res == null) {
                IllegalMonitorStateException cause = new IllegalMonitorStateException("attempt to unlock lock, not locked by current thread by node id: "
                        + id + " thread-id: " + threadId);
                throw new CompletionException(cause);
            }

            return null;
        });
        return new CompletableFutureWrapper<>(f);
    }
    protected final RFuture<Boolean> unlockInnerAsync(long threadId) {
        String id = getServiceManager().generateId();
        MasterSlaveServersConfig config = getServiceManager().getConfig();
        int timeout = (config.getTimeout() + config.getRetryInterval()) * config.getRetryAttempts();
        timeout = Math.max(timeout, 1);
        // LUA脚本：释放锁
        RFuture<Boolean> r = unlockInnerAsync(threadId, id, timeout);
        CompletionStage<Boolean> ff = r.thenApply(v -> {
            CommandAsyncExecutor ce = commandExecutor;
            if (ce instanceof CommandBatchService) {
                ce = new CommandBatchService(commandExecutor);
            }
            ce.writeAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.DEL, getUnlockLatchName(id));
            if (ce instanceof CommandBatchService) {
                ((CommandBatchService) ce).executeAsync();
            }
            return v;
        });
        return new CompletableFutureWrapper<>(ff);
    }
    protected abstract RFuture<Boolean> unlockInnerAsync(long threadId, String requestId, int timeout);


    protected final <T> RFuture<T> evalWriteSyncedAsync(String key, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        return commandExecutor.syncedEval(key, codec, evalCommandType, script, keys, params);
    }

    protected void acquireFailed(long waitTime, TimeUnit unit, long threadId) {
        commandExecutor.get(acquireFailedAsync(waitTime, unit, threadId));
    }

    protected void trySuccessFalse(long currentThreadId, CompletableFuture<Boolean> result) {
        acquireFailedAsync(-1, null, currentThreadId).whenComplete((res, e) -> {
            if (e == null) {
                result.complete(false);
            } else {
                result.completeExceptionally(e);
            }
        });
    }

    protected CompletableFuture<Void> acquireFailedAsync(long waitTime, TimeUnit unit, long threadId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Condition newCondition() {
        // TODO implement
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLocked() {
        return isExists();
    }
    
    @Override
    public RFuture<Boolean> isLockedAsync() {
        return isExistsAsync();
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return isHeldByThread(Thread.currentThread().getId());
    }

    @Override
    public boolean isHeldByThread(long threadId) {
        return get(isHeldByThreadAsync(threadId));
    }

    @Override
    public RFuture<Boolean> isHeldByThreadAsync(long threadId) {
        return commandExecutor.writeAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.HEXISTS, getRawName(), getLockName(threadId));
    }

    private static final RedisCommand<Integer> HGET = new RedisCommand<Integer>("HGET", new MapValueDecoder(), new IntegerReplayConvertor(0));
    
    public RFuture<Integer> getHoldCountAsync() {
        return commandExecutor.writeAsync(getRawName(), LongCodec.INSTANCE, HGET, getRawName(), getLockName(Thread.currentThread().getId()));
    }
    
    @Override
    public int getHoldCount() {
        return get(getHoldCountAsync());
    }

    @Override
    public RFuture<Boolean> deleteAsync() {
        return forceUnlockAsync();
    }

    @Override
    public RFuture<Void> unlockAsync() {
        long threadId = Thread.currentThread().getId();
        return unlockAsync(threadId);
    }

    String getUnlockLatchName(String requestId) {
        return prefixName("redisson_unlock_latch", getRawName()) + ":" + requestId;
    }

    @Override
    public RFuture<Void> lockAsync() {
        return lockAsync(-1, null);
    }

    @Override
    public RFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
        long currentThreadId = Thread.currentThread().getId();
        return lockAsync(leaseTime, unit, currentThreadId);
    }

    @Override
    public RFuture<Void> lockAsync(long currentThreadId) {
        return lockAsync(-1, null, currentThreadId);
    }

    @Override
    public RFuture<Boolean> tryLockAsync() {
        return tryLockAsync(Thread.currentThread().getId());
    }

    @Override
    public RFuture<Boolean> tryLockAsync(long waitTime, TimeUnit unit) {
        return tryLockAsync(waitTime, -1, unit);
    }

    @Override
    public RFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
        long currentThreadId = Thread.currentThread().getId();
        return tryLockAsync(waitTime, leaseTime, unit, currentThreadId);
    }

    protected final <T> CompletionStage<T> handleNoSync(long threadId, CompletionStage<T> ttlRemainingFuture) {
        return commandExecutor.handleNoSync(ttlRemainingFuture, () -> unlockInnerAsync(threadId));
    }

    /** 过期项：用于辅助管理过期时间自动续期 */
    public static class ExpirationEntry {
        private final Map<Long, Integer> threadIds = new LinkedHashMap<>();
        private volatile Timeout timeout;
        private final WrappedLock lock = new WrappedLock();
        public ExpirationEntry() {
            super();
        }
        public void addThreadId(long threadId) {
            lock.execute(() -> {
                threadIds.compute(threadId, (t, counter) -> {
                    counter = Optional.ofNullable(counter).orElse(0);
                    counter++;
                    return counter;
                });
            });
        }
        public boolean hasNoThreads() {
            return lock.execute(() -> threadIds.isEmpty());
        }
        public Long getFirstThreadId() {
            return lock.execute(() -> {
                if (threadIds.isEmpty()) {
                    return null;
                }
                return threadIds.keySet().iterator().next();
            });
        }
        public void removeThreadId(long threadId) {
            lock.execute(() -> {
                threadIds.compute(threadId, (t, counter) -> {
                    if (counter == null) {
                        return null;
                    }
                    counter--;
                    if (counter == 0) {
                        return null;
                    }
                    return counter;
                });
            });
        }
        public void setTimeout(Timeout timeout) {
            this.timeout = timeout;
        }
        public Timeout getTimeout() {
            return timeout;
        }
    }

    protected String getEntryName() {
        return entryName;
    }

}
