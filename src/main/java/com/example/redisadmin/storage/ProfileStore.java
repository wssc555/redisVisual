package com.example.redisadmin.storage;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.RedisProfile;
import com.example.redisadmin.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code redis_profile} 表的 JdbcTemplate CRUD。
 * <p>秘密三态（对齐 kafkaVisual5 唯一实现口径）：</p>
 * <ul>
 *   <li>{@code null} 或 {@code "******"} → 保持库中现值</li>
 *   <li>{@code ""} → 清空</li>
 *   <li>其他 → AES-GCM 加密后存</li>
 * </ul>
 * INSERT 路径同样走此逻辑（current=null），禁止 {@code Secrets.empty()} 式丢弃。
 */
@Repository
public class ProfileStore {

    private static final Logger log = LoggerFactory.getLogger(ProfileStore.class);

    /**
     * 脱敏占位符：前端回显该值表示「已配置密码，不回显明文」。
     */
    public static final String MASKED = "******";

    private static final String COLUMNS =
            "id, name, mode, host, port, nodes, sentinels, master_name, database, "
                    + "username, password, sentinel_password, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final CryptoService cryptoService;

    public ProfileStore(JdbcTemplate jdbcTemplate, CryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    public List<ProfileRow> findAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM redis_profile ORDER BY id", ROW_MAPPER);
    }

    public Optional<ProfileRow> findRowById(Long id) {
        List<ProfileRow> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM redis_profile WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean existsByName(String name, Long excludeId) {
        Integer count = excludeId == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(1) FROM redis_profile WHERE name = ?", Integer.class, name)
                : jdbcTemplate.queryForObject("SELECT COUNT(1) FROM redis_profile WHERE name = ? AND id <> ?",
                Integer.class, name, excludeId);
        return count != null && count > 0;
    }

    /**
     * 解出连接域用的 {@link RedisProfile}（凭据在此解密为明文）。
     * profile 不存在时抛 40401 —— service 层无需重复判空。
     */
    public RedisProfile resolveProfile(Long id) {
        ProfileRow row = findRowById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.PROFILE_NOT_FOUND, String.valueOf(id)));
        return toDomain(row);
    }

    public ProfileRow insert(ProfileRow row) {
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setPassword(resolveSecretForWrite(row.getPassword(), null));
        row.setSentinelPassword(resolveSecretForWrite(row.getSentinelPassword(), null));

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO redis_profile (name, mode, host, port, nodes, sentinels, master_name, "
                            + "database, username, password, sentinel_password, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            bindInsert(ps, row);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            row.setId(key.longValue());
        }
        log.info("已创建连接配置 id={} name={} mode={}", row.getId(), row.getName(), row.getMode());
        return row;
    }

    public ProfileRow update(Long id, ProfileRow row) {
        ProfileRow current = findRowById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.PROFILE_NOT_FOUND, String.valueOf(id)));
        row.setId(id);
        row.setCreatedAt(current.getCreatedAt());
        row.setUpdatedAt(LocalDateTime.now());
        row.setPassword(resolveSecretForWrite(row.getPassword(), current.getPassword()));
        row.setSentinelPassword(resolveSecretForWrite(row.getSentinelPassword(), current.getSentinelPassword()));

        jdbcTemplate.update("UPDATE redis_profile SET name=?, mode=?, host=?, port=?, nodes=?, sentinels=?, "
                        + "master_name=?, database=?, username=?, password=?, sentinel_password=?, updated_at=? "
                        + "WHERE id=?",
                row.getName(), row.getMode().name(), row.getHost(), row.getPort(), row.getNodes(), row.getSentinels(),
                row.getMasterName(), row.getDatabase(), row.getUsername(), row.getPassword(),
                row.getSentinelPassword(), row.getUpdatedAt(), id);
        log.info("已更新连接配置 id={} name={} mode={}", id, row.getName(), row.getMode());
        return row;
    }

    public boolean delete(Long id) {
        int affected = jdbcTemplate.update("DELETE FROM redis_profile WHERE id = ?", id);
        if (affected > 0) {
            log.info("已删除连接配置 id={}", id);
        }
        return affected > 0;
    }

    /**
     * 写入侧三态解析。{@code current} 为库中现值（INSERT 传 null）。
     *
     * @return 落库密文，或 null 表示「无密码」（保持 / 清空后为 null）
     */
    private String resolveSecretForWrite(String input, String current) {
        if (input == null || MASKED.equals(input)) {
            return current;
        }
        if (input.isEmpty()) {
            return null;
        }
        return cryptoService.encrypt(input);
    }

    /**
     * 对外只暴露「有没有配密码」，永不回明文或可逆向值。
     */
    public static boolean hasCredential(String cipherOrNull) {
        return cipherOrNull != null && !cipherOrNull.isEmpty();
    }

    private RedisProfile toDomain(ProfileRow row) {
        return RedisProfile.builder()
                .id(row.getId())
                .name(row.getName())
                .mode(row.getMode())
                .host(row.getHost())
                .port(row.getPort())
                .nodes(row.nodeList().stream()
                        .map(n -> RedisProfile.Node.builder().host(n.getHost()).port(
                                n.getPort() == null ? 6379 : n.getPort()).build())
                        .toList())
                .sentinels(row.sentinelList().stream()
                        .map(n -> RedisProfile.Node.builder().host(n.getHost()).port(
                                n.getPort() == null ? 26379 : n.getPort()).build())
                        .toList())
                .masterName(row.getMasterName())
                .database(row.getDatabase() == null ? 0 : row.getDatabase())
                .username(row.getUsername())
                .password(decryptQuietly(row.getPassword(), row.getId(), "password"))
                .sentinelPassword(decryptQuietly(row.getSentinelPassword(), row.getId(), "sentinelPassword"))
                .build();
    }

    private String decryptQuietly(String cipher, Long id, String field) {
        if (cipher == null || cipher.isEmpty()) {
            return null;
        }
        try {
            return cryptoService.decrypt(cipher);
        } catch (RuntimeException e) {
            // 凭据不可解不应拖垮整个列表：记录并按「无凭据」处理，建连时自然失败
            log.error("连接配置 id={} 的 {} 解密失败（密钥是否与数据库匹配？）", id, field);
            return null;
        }
    }

    private static void bindInsert(PreparedStatement ps, ProfileRow row) throws SQLException {
        ps.setString(1, row.getName());
        ps.setString(2, row.getMode() == null ? DeployMode.STANDALONE.name() : row.getMode().name());
        ps.setString(3, row.getHost());
        if (row.getPort() == null) {
            ps.setNull(4, java.sql.Types.INTEGER);
        } else {
            ps.setInt(4, row.getPort());
        }
        ps.setString(5, row.getNodes());
        ps.setString(6, row.getSentinels());
        ps.setString(7, row.getMasterName());
        ps.setInt(8, row.getDatabase() == null ? 0 : row.getDatabase());
        ps.setString(9, row.getUsername());
        ps.setString(10, row.getPassword());
        ps.setString(11, row.getSentinelPassword());
        ps.setObject(12, row.getCreatedAt());
        ps.setObject(13, row.getUpdatedAt());
    }

    private static final RowMapper<ProfileRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        ProfileRow row = new ProfileRow();
        row.setId(rs.getLong("id"));
        row.setName(rs.getString("name"));
        row.setMode(DeployMode.valueOf(rs.getString("mode")));
        row.setHost(rs.getString("host"));
        int port = rs.getInt("port");
        row.setPort(rs.wasNull() ? null : port);
        row.setNodes(rs.getString("nodes"));
        row.setSentinels(rs.getString("sentinels"));
        row.setMasterName(rs.getString("master_name"));
        int database = rs.getInt("database");
        row.setDatabase(rs.wasNull() ? 0 : database);
        row.setUsername(rs.getString("username"));
        row.setPassword(rs.getString("password"));
        row.setSentinelPassword(rs.getString("sentinel_password"));
        row.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        row.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return row;
    };
}
