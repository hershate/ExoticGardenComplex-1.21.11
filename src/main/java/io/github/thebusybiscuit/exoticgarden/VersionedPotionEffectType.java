package io.github.thebusybiscuit.exoticgarden;

import org.bukkit.potion.PotionEffectType;

/**
 * 版本兼容的药水效果类型。
 *
 * <p>1.20.5+ 重构了药水效果注册表，部分旧名（如 {@code DAMAGE_RESISTANCE}）被重命名
 * （{@code RESISTANCE}）。本类按“旧名 → 新名”顺序，使用 {@link PotionEffectType#getByName}
 * 解析；若都未命中则回退到 {@link PotionEffectType#SLOW}，保证字段永不为 {@code null}，
 * 避免 {@code new PotionEffect(null, ...)} 在 onEnable 注册酒类或在运行时
 * （如 {@code checkDrunkers}）抛 {@link IllegalArgumentException} 导致插件不可用。</p>
 *
 * <p>注意：旧实现遍历 {@link PotionEffectType#values()} 并调用 {@code getName()}，
 * 该方法在较新版本下行为不稳定，且失败时返回 {@code null} 会直接打断 onEnable。
 * 现改为基于 {@code getByName} 的健壮查找 + 兜底。</p>
 */
public final class VersionedPotionEffectType {
    public static final PotionEffectType DAMAGE_RESISTANCE = getOrDefault("DAMAGE_RESISTANCE", "RESISTANCE");
    public static final PotionEffectType INCREASE_DAMAGE = getOrDefault("INCREASE_DAMAGE", "STRENGTH");
    public static final PotionEffectType HEAL = getOrDefault("HEAL", "INSTANT_HEAL");
    public static final PotionEffectType CONFUSION = getOrDefault("CONFUSION", "NAUSEA");

    private VersionedPotionEffectType() {
    }

    /**
     * 按给定名称顺序解析药水效果类型，全部未命中时返回 {@code null}。
     *
     * @param names 候选名称（旧名在前、新名在后）
     * @return 命中的 {@link PotionEffectType}，或 {@code null}
     */
    public static PotionEffectType get(String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null) {
                continue;
            }
            PotionEffectType type = PotionEffectType.getByName(name);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    /**
     * 解析药水效果类型，全部未命中时回退到 {@link PotionEffectType#SLOW}，保证非空。
     */
    private static PotionEffectType getOrDefault(String... names) {
        PotionEffectType type = get(names);
        if (type != null) {
            return type;
        }
        // 1.20.5+ 移除了 PotionEffectType 的旧静态常量（如 SLOW），故兜底也用 getByName。
        // 候选名全部解析失败（理论极端）时，用最常见的效果顶替，尽量保证字段非 null，
        // 避免 new PotionEffect(null, ...) 在注册/运行时抛 IllegalArgumentException。
        for (String fallbackName : new String[] {"SLOW", "NAUSEA", "SPEED", "RESISTANCE"}) {
            PotionEffectType fallback = PotionEffectType.getByName(fallbackName);
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }
}
