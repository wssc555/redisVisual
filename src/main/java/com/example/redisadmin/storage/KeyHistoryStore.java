package com.example.redisadmin.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code key_history} 表的 JdbcTemplate CRUD：事件追加 + 组合条件分页查询 + 过期清理。
 * <p>查询侧口径：</p>
 * <ul>
 *   <li>key / value 均为模糊匹配（{@code LIKE %kw%}，统一 LOWER 实现三方言一致的
 *       大小写不敏感），通配符由调用方以 <b>{@code !}</b> 转义
 *       （{@code ESCAPE '!'}——不能用反斜杠：MySQL 把 {@code '\'} 解析为字面量转义，
 *       {@code ESCAPE '\'} 直接 1064 语法错误；{@code '!'} 三方言均无歧义）</li>
 *   <li>{@code LIKE '%..%'} 无法走索引 → 全表扫描，属已知代价（见评估文档）</li>
 *   <li>LIMIT/OFFSET 分页，三方言通用</li>
 * </ul>
 */
@Repository
public class KeyHistoryStore {

    private final JdbcTemplate jdbcTemplate;

    public KeyHistoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS =
            "id, profile_id, db, key_name, key_type, operation, value_preview, value_bytes, operator, event_time";

    private static final String TABLE = "key_history";

    public void append(KeyHistoryRow row) {
        jdbcTemplate.update("INSERT INTO key_history (profile_id, db, key_name, key_type, operation, "
                        + "value_preview, value_bytes, operator, event_time) VALUES (?,?,?,?,?,?,?,?,?)",
                row.getProfileId(), row.getDb(), row.getKeyName(), row.getKeyType(), row.getOperation(),
                row.getValuePreview(), row.getValueBytes(), row.getOperator(), row.getEventTime());
    }

    /**
     * 组合条件分页查询。全部条件可选，动态拼接 AND。
     *
     * @param f        过滤条件（keyPattern/valuePattern 已含 % 通配且已转义）
     * @param offset   偏移量（已按 page 换算）
     * @param pageSize 页大小（已钳制）
     */
    public List<KeyHistoryRow> query(HistoryFilter f, long offset, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ").append(TABLE);
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, f);
        sql.append(" ORDER BY event_time DESC, id DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    /**
     * 与 {@link #query} 同口径的计数。
     */
    public long count(HistoryFilter f) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM ").append(TABLE);
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, f);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0L : count;
    }

    /**
     * 删除 event_time 早于截止点的记录，返回删除行数。
     */
    public int purgeBefore(LocalDateTime cutoff) {
        return jdbcTemplate.update("DELETE FROM key_history WHERE event_time < ?", cutoff);
    }

    private void appendWhere(StringBuilder sql, List<Object> params, HistoryFilter f) {
        List<String> conditions = new ArrayList<>();
        if (f.profileId() != null) {
            conditions.add("profile_id = ?");
            params.add(f.profileId());
        }
        if (f.db() != null) {
            conditions.add("db = ?");
            params.add(f.db());
        }
        if (f.keyPattern() != null) {
            conditions.add("LOWER(key_name) LIKE LOWER(?) ESCAPE '!'");
            params.add(f.keyPattern());
        }
        if (f.valuePattern() != null) {
            conditions.add("LOWER(value_preview) LIKE LOWER(?) ESCAPE '!'");
            params.add(f.valuePattern());
        }
        if (f.operation() != null) {
            conditions.add("operation = ?");
            params.add(f.operation());
        }
        if (f.startTime() != null) {
            conditions.add("event_time >= ?");
            params.add(f.startTime());
        }
        if (f.endTime() != null) {
            conditions.add("event_time <= ?");
            params.add(f.endTime());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    /**
     * 过滤条件（不可变载体；keyPattern/valuePattern 为 {@code %kw%} 形态且已按 {@code !} 转义，见 {@link #appendWhere}）。
     */
    public record HistoryFilter(Long profileId, Integer db, String keyPattern, String valuePattern,
                                String operation, LocalDateTime startTime, LocalDateTime endTime) {
    }

    private static final RowMapper<KeyHistoryRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        KeyHistoryRow row = new KeyHistoryRow();
        row.setId(rs.getLong("id"));
        long profileId = rs.getLong("profile_id");
        row.setProfileId(rs.wasNull() ? null : profileId);
        row.setDb(rs.getInt("db"));
        row.setKeyName(rs.getString("key_name"));
        row.setKeyType(rs.getString("key_type"));
        row.setOperation(rs.getString("operation"));
        row.setValuePreview(rs.getString("value_preview"));
        long valueBytes = rs.getLong("value_bytes");
        row.setValueBytes(rs.wasNull() ? null : valueBytes);
        row.setOperator(rs.getString("operator"));
        row.setEventTime(rs.getObject("event_time", LocalDateTime.class));
        return row;
    };
}
