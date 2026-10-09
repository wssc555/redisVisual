package com.example.redisadmin.service;

import io.lettuce.core.ScanArgs;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;

/**
 * 在已构建的 {@link ScanArgs}（COUNT/MATCH）之上追加 {@code TYPE <type>} 选项
 * （Redis 6.0+ 的 SCAN 语法）。
 * <p><b>为什么继承包装而不是直接传参</b>：lettuce 的 {@code ScanArgs}
 * 只有 match/limit 两个构建方法，未提供 type 支持。
 * {@code ScanArgs} 非 final 且 {@code build(CommandArgs)} 为 public，
 * 覆写后先委托 base 写出 COUNT/MATCH，再追加 TYPE —— Redis 要求 TYPE
 * 必须是 SCAN 的最后一个选项，追加位置恰好满足语法。</p>
 * <p>⚠️ 必须传入已含 limit/match 的 base 实例：本类自身不设 limit/match，
 * 若误用空实例会导致 COUNT/MATCH 整体丢失。</p>
 * <p>仅由 {@link KeyService#scanKeys} 在用户显式选择类型筛选时使用。</p>
 */
final class TypeFilterScanArgs extends ScanArgs {

    private final ScanArgs base;
    private final String type;

    TypeFilterScanArgs(ScanArgs base, String type) {
        this.base = base;
        this.type = type;
    }

    @Override
    public <K, V> void build(CommandArgs<K, V> args) {
        base.build(args);
        args.add(CommandKeyword.TYPE);
        args.add(type);
    }
}
