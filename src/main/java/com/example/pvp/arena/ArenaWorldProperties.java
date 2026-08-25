package com.example.pvp.arena;

import net.minecraft.world.GameRules;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.level.UnmodifiableLevelProperties;

/**
 * 竞技场世界的独立属性：拥有独立的 GameRules，固定正午、晴天，不受主世界影响。
 */
public class ArenaWorldProperties extends UnmodifiableLevelProperties {
    private final GameRules gameRules = new GameRules();

    public ArenaWorldProperties(SaveProperties saveProperties) {
        super(saveProperties, saveProperties.getMainWorldProperties());
    }

    @Override
    public GameRules getGameRules() {
        return this.gameRules;
    }

    @Override
    public long getTimeOfDay() {
        return 6000; // 固定正午
    }

    @Override
    public int getClearWeatherTime() {
        return 100000;
    }

    @Override
    public int getRainTime() {
        return 0;
    }

    @Override
    public boolean isRaining() {
        return false;
    }

    @Override
    public int getThunderTime() {
        return 0;
    }

    @Override
    public boolean isThundering() {
        return false;
    }
}
