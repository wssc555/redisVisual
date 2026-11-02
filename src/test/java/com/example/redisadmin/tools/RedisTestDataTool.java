package com.example.redisadmin.tools;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 开发自测工具（JUnit 版）：每个 @Test 方法都可在 IDE 中<b>单独运行</b>（点方法旁的绿色箭头）。
 *
 * <p>方法清单：</p>
 * <ol>
 *   <li>{@link #testConnection()} —— 测试连接是否正常（PING + dbsize）</li>
 *   <li>{@link #seedRandomData()} —— 随机生成 5 大类型各 {@link #COUNT_PER_TYPE} 个 key 并写入
 *       （String/List/Hash/Set/ZSet，统一前缀 {@code test:}）</li>
 *   <li>{@link #flushAll()} —— 清空 Redis <b>所有数据</b>（FLUSHALL，需先打开独立确认开关）</li>
 *   <li>{@link #clearTestKeys()} —— （附加）只清本工具生成的 {@code test:*} key（安全）</li>
 * </ol>
 *
 * <p>类名<b>故意不带 Test 后缀</b>：本类直连真实 Redis 且含 FLUSHALL，属于手动触发的开发沙盒，
 * 绝不能被 {@code mvn test} / CI 无人值守自动执行（surefire 默认只匹配 *Test / Test* 命名）。</p>
 *
 * <p>连接配置为类变量（见下方 HOST/PORT 等），也可用系统属性临时覆盖（不改代码）：
 * {@code -Dredis.host=x.x.x.x -Dredis.port=6379 -Dredis.username=xx -Dredis.password=xx -Dredis.database=0}</p>
 *
 * <p style="color:red">安全边界：本类是开发者的「直连 Redis 沙盒」，允许 FLUSHALL；
 * 该能力<b>严禁</b>搬入后端服务（见项目安全红线：服务端不提供 CONFIG SET / FLUSHDB / FLUSHALL / KEYS）。</p>
 */
class RedisTestDataTool {

    // Redis 连接配置（按环境修改这里）
    private static final String HOST = env("redis.host", "182.92.183.161");
    private static final int PORT = envInt("redis.port", 6379);
    /**
     * ACL 用户名，留空 = 使用 default 用户（无 ACL 场景无需填）
     */
    private static final String USERNAME = env("redis.username", "default");
    /**
     * 密码，留空 = 无密码
     */
    private static final String PASSWORD = env("redis.password", "redis_dcXGTQ");
    /**
     * 目标 db 编号
     */
    private static final int DATABASE = envInt("redis.database", 0);
    /**
     * 连接/命令超时
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /**
     * 每种类型生成的 key 数量
     */
    private static final int COUNT_PER_TYPE = 100;
    /**
     * 测试 key 统一前缀（便于识别与按前缀清理）
     */
    private static final String KEY_PREFIX = "test";

    /**
     * FLUSHALL 独立确认开关（防误跑）：默认 false → {@link #flushAll()} 会被跳过并提示。
     * 确实要清空时把这里改为 true（或运行时加 -Dredis.confirm.flushall=true）。
     */
    private static final boolean FLUSH_ALL_CONFIRMED = false;

    private static final String ALPHANUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * PING 探活；不通则测试失败（标红）
     */
    @Test
    void testConnection() {
        long start = System.currentTimeMillis();
        withCommands(cmds -> {
            String pong = cmds.ping();
            assertEquals("PONG", pong, "PING 未返回 PONG，连接异常");
            System.out.printf("[连接正常] PING -> PONG（%dms），dbsize = %d%n",
                    System.currentTimeMillis() - start, cmds.dbsize());
        });
    }

    /**
     * 5 大类型各生成 COUNT_PER_TYPE 个 key；完成后校验 dbsize
     */
    @Test
    void seedRandomData() {
        withCommands(cmds -> {
            long start = System.currentTimeMillis();
            seedStrings(cmds);
            seedLists(cmds);
            seedHashes(cmds);
            seedSets(cmds);
            seedZSets(cmds);
            long dbsize = cmds.dbsize();
            System.out.printf("[生成完成] 耗时 %dms，dbsize = %d（应 >= %d）%n",
                    System.currentTimeMillis() - start, dbsize, 5L * COUNT_PER_TYPE);
            assertTrue(dbsize >= 5L * COUNT_PER_TYPE,
                    "dbsize 应 >= " + (5L * COUNT_PER_TYPE) + "，实际 " + dbsize);
        });
    }

    /**
     * String：随机长度（10~200 字符）的随机串
     */
    private void seedStrings(RedisCommands<String, String> cmds) {
        for (int i = 1; i <= COUNT_PER_TYPE; i++) {
            cmds.set(key("string", i), randomString(10, 200));
        }
        System.out.printf("String  x%d 完成 -> %s:string:0001~%s%n", COUNT_PER_TYPE, KEY_PREFIX, pad(COUNT_PER_TYPE));
    }

    /**
     * List：RPUSH 5~20 个随机元素（模拟队列/消息流）
     */
    private void seedLists(RedisCommands<String, String> cmds) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 1; i <= COUNT_PER_TYPE; i++) {
            int size = rnd.nextInt(5, 21);
            String[] items = new String[size];
            for (int j = 0; j < size; j++) {
                items[j] = "item-" + randomString(6, 16);
            }
            cmds.rpush(key("list", i), items);
        }
        System.out.printf("List    x%d 完成 -> %s:list:*（每个 5~20 元素）%n", COUNT_PER_TYPE, KEY_PREFIX);
    }

    /**
     * Hash：HSET 3~12 个随机 field（模拟对象/配置）
     */
    private void seedHashes(RedisCommands<String, String> cmds) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 1; i <= COUNT_PER_TYPE; i++) {
            int fields = rnd.nextInt(3, 13);
            Map<String, String> map = new LinkedHashMap<>();
            for (int j = 0; j < fields; j++) {
                map.put("field-" + j, randomString(4, 40));
            }
            cmds.hset(key("hash", i), map);
        }
        System.out.printf("Hash    x%d 完成 -> %s:hash:*（每个 3~12 field）%n", COUNT_PER_TYPE, KEY_PREFIX);
    }

    /**
     * Set：SADD 5~20 个去重随机成员（模拟标签/集合）
     */
    private void seedSets(RedisCommands<String, String> cmds) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 1; i <= COUNT_PER_TYPE; i++) {
            int size = rnd.nextInt(5, 21);
            Set<String> members = new LinkedHashSet<>();
            while (members.size() < size) {
                members.add("member-" + randomString(6, 14));
            }
            cmds.sadd(key("set", i), members.toArray(new String[0]));
        }
        System.out.printf("Set     x%d 完成 -> %s:set:*（每个 5~20 成员）%n", COUNT_PER_TYPE, KEY_PREFIX);
    }

    /**
     * ZSet：ZADD 5~20 个成员，分数随机（模拟排行榜）。
     * ⚠️ lettuce 已移除 zadd(K, Map) 重载，必须走 ScoredValue 数组。
     */
    @SuppressWarnings("unchecked")
    private void seedZSets(RedisCommands<String, String> cmds) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 1; i <= COUNT_PER_TYPE; i++) {
            int size = rnd.nextInt(5, 21);
            ScoredValue<String>[] scoreMembers = new ScoredValue[size];
            for (int j = 0; j < size; j++) {
                scoreMembers[j] = ScoredValue.just(
                        Math.round(rnd.nextDouble(0, 10000)) / 100.0,
                        "player-" + randomString(4, 10));
            }
            cmds.zadd(key("zset", i), scoreMembers);
        }
        System.out.printf("ZSet    x%d 完成 -> %s:zset:*（每个 5~20 成员）%n", COUNT_PER_TYPE, KEY_PREFIX);
    }

    /**
     * FLUSHALL：清空目标 Redis 所有 db 的所有数据。危险操作，需确认开关。
     */
    @Test
    void flushAll() {
        assumeTrue(flushConfirmed(),
                "FLUSHALL 是危险操作：把类变量 FLUSH_ALL_CONFIRMED 改为 true"
                        + "（或加 -Dredis.confirm.flushall=true）后再单独运行本方法");
        withCommands(cmds -> {
            long before = cmds.dbsize();
            cmds.flushall();
            long after = cmds.dbsize();
            assertEquals(0L, after, "FLUSHALL 后 dbsize 应为 0");
            System.out.printf("[清空完成] FLUSHALL 前 dbsize = %d -> 后 dbsize = %d%n", before, after);
        });
    }

    /**
     * SCAN 匹配 {@code test:*} 后分批 DEL，不触碰其他数据
     */
    @Test
    void clearTestKeys() {
        withCommands(cmds -> {
            long deleted = deleteByPrefix(cmds, KEY_PREFIX + ":*");
            System.out.printf("[前缀清理完成] 删除 %d 个 %s:* key，当前 dbsize = %d%n",
                    deleted, KEY_PREFIX, cmds.dbsize());
        });
    }

    /**
     * 短建连接：建 client -> connect -> 执行 -> 关闭；异常向上抛（测试标红）
     */
    private void withCommands(java.util.function.Consumer<RedisCommands<String, String>> block) {
        RedisClient client = RedisClient.create(buildUri());
        client.setOptions(buildClientOptions());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            block.accept(conn.sync());
        } catch (Exception e) {
            throw new IllegalStateException("Redis 操作失败（检查 HOST/PORT/USERNAME/PASSWORD/DATABASE 配置）: "
                    + e.getMessage(), e);
        } finally {
            client.shutdown();
        }
    }

    /**
     * FLUSHALL 确认：类变量开关，或运行时系统属性 -Dredis.confirm.flushall=true
     */
    private static boolean flushConfirmed() {
        return FLUSH_ALL_CONFIRMED || Boolean.parseBoolean(System.getProperty("redis.confirm.flushall", "false"));
    }

    private static RedisURI buildUri() {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(HOST)
                .withPort(PORT)
                .withDatabase(DATABASE)
                .withTimeout(TIMEOUT);
        if (!PASSWORD.isBlank() && !USERNAME.isBlank()) {
            builder.withAuthentication(USERNAME, PASSWORD);
        } else if (!PASSWORD.isBlank()) {
            builder.withPassword(PASSWORD);
        }
        return builder.build();
    }

    /**
     * 超时三件套挂在 ClientOptions（与主工程约定一致）：
     * socket 连接超时 + 命令级 TimeoutOptions；ClientResources.Builder 不接受这些参数。
     */
    private static ClientOptions buildClientOptions() {
        return ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(TIMEOUT)
                        .keepAlive(true)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(TIMEOUT))
                .build();
    }

    private static long deleteByPrefix(RedisCommands<String, String> cmds, String pattern) {
        ScanArgs scanArgs = ScanArgs.Builder.limit(500).match(pattern);
        long deleted = 0;
        KeyScanCursor<String> cursor = cmds.scan(scanArgs);
        while (true) {
            List<String> keys = cursor.getKeys();
            if (!keys.isEmpty()) {
                deleted += cmds.del(keys.toArray(new String[0]));
            }
            if (cursor.isFinished()) {
                break;
            }
            cursor = cmds.scan(ScanCursor.of(cursor.getCursor()), scanArgs);
        }
        return deleted;
    }

    /**
     * 系统属性读取：redis.host / redis.username / redis.password
     */
    private static String env(String key, String def) {
        return System.getProperty(key, def);
    }

    /**
     * 系统属性读取：redis.port / redis.database
     */
    private static int envInt(String key, int def) {
        try {
            return Integer.parseInt(System.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String key(String type, int index) {
        return KEY_PREFIX + ":" + type + ":" + pad(index);
    }

    /**
     * 4 位序号：test:string:0001
     */
    private static String pad(int index) {
        return String.format("%04d", index);
    }

    /**
     * 随机长度 [minLen, maxLen] 的字母数字串
     */
    private static String randomString(int minLen, int maxLen) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int len = rnd.nextInt(minLen, maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUM.charAt(rnd.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }
}
