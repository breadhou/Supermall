package com.mall.infra.redis;

/**
 * Redis keys used by the seckill flow.
 *
 * <p>All keys belonging to one item use the same Redis Cluster hash tag:
 * {@code mall:seckill:{itemId}:...}.  A reserve/rollback Lua script can
 * therefore touch the path, result, limit, stock and pending keys atomically
 * both on standalone Redis and on a Redis Cluster.</p>
 */
public final class SeckillKey extends BasePrefix {

    private static final String ROOT = "mall:seckill:";

    /**
     * Legacy prefixes are retained for source compatibility with callers
     * outside the seckill module.  New code must use the complete-key helpers
     * below so that the hash tag is preserved.
     */
    @Deprecated
    public static final SeckillKey stock = new SeckillKey(0, "mall:seckill:stock:");
    @Deprecated
    public static final SeckillKey result = new SeckillKey(3600, "mall:seckill:result:");
    @Deprecated
    public static final SeckillKey userLimit = new SeckillKey(0, "mall:seckill:limit:");
    @Deprecated
    public static final SeckillKey userLock = new SeckillKey(10, "mall:seckill:lock:");
    @Deprecated
    public static final SeckillKey path = new SeckillKey(60, "mall:seckill:path:");
    @Deprecated
    public static final SeckillKey verifyCode = new SeckillKey(300, "mall:seckill:verify:");

    private SeckillKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

    public static String stockKey(Long itemId) {
        return itemPrefix(itemId) + "stock";
    }

    public static String pathKey(Long itemId, Long userId) {
        return itemPrefix(itemId) + "path:" + userId;
    }

    public static String resultKey(Long itemId, Long userId) {
        return itemPrefix(itemId) + "result:" + userId;
    }

    public static String userLimitKey(Long itemId, Long userId) {
        return itemPrefix(itemId) + "limit:" + userId;
    }

    /** Snapshot loaded during preheat and copied into a user's request snapshot. */
    public static String itemSnapshotKey(Long itemId) {
        return itemPrefix(itemId) + "snapshot";
    }

    /** Snapshot containing the path and the user's address selected by getPath. */
    public static String requestSnapshotKey(Long itemId, Long userId) {
        return itemPrefix(itemId) + "request:" + userId;
    }

    /** Pending message state; the item id remains the hash tag. */
    public static String pendingKey(Long itemId, String messageId) {
        return itemPrefix(itemId) + "pending:" + messageId;
    }

    /** Sorted-set index for pending messages of one item. */
    public static String pendingIndexKey(Long itemId) {
        return itemPrefix(itemId) + "pending:index";
    }

    /** Global item registry used by the compensation scanner. */
    public static String itemIndexKey() {
        return ROOT + "items";
    }

    private static String itemPrefix(Long itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId must not be null");
        }
        return ROOT + "{" + itemId + "}:";
    }
}
