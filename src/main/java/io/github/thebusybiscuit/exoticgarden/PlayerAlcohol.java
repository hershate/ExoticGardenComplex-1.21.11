package io.github.thebusybiscuit.exoticgarden;

import java.util.UUID;

/**
 * 玩家醉酒状态。
 *
 * <p>以 Minecraft 正版验证 UUID（{@code player.getUniqueId()}，online 服务器下即 Mojang
 * 正版 UUID）作为玩家标识，避免原实现用玩家名导致“玩家改名即丢失/残留醉酒数据”的问题。
 * 数据在玩家退出时统一落盘（见 {@link ExoticGarden#saveDatas}），构造器不再即时写盘。</p>
 */
public class PlayerAlcohol {
    final UUID playerUuid;
    int alcohol;
    boolean isDrunk;

    public PlayerAlcohol(UUID playerUuid, int alcohol) {
        this.playerUuid = playerUuid;
        this.alcohol = alcohol;
        this.isDrunk = false;
    }

    public PlayerAlcohol(UUID playerUuid, int alcohol, boolean isDrunk) {
        this.playerUuid = playerUuid;
        this.alcohol = alcohol;
        this.isDrunk = isDrunk;
    }

    public int getAlcohol() {
        return this.alcohol;
    }

    public void setAlcohol(int alcohol) {
        if (alcohol < 0) {
            this.alcohol = 0;
            return;
        }
        this.alcohol = alcohol;
    }

    public void addAlcohol(int alcohol) {
        int result = this.alcohol + alcohol;
        if (result < 0) {
            this.alcohol = 0;
            return;
        }
        this.alcohol = result;
    }

    public boolean isDrunk() {
        return this.isDrunk;
    }

    public void setDrunk(boolean drunk) {
        this.isDrunk = drunk;
    }

    public UUID getPlayerUuid() {
        return this.playerUuid;
    }

    public boolean check() {
        if (this.alcohol >= 100) {
            if (!this.isDrunk) {
                this.isDrunk = true;
            }
            return true;
        }
        return false;
    }
}
