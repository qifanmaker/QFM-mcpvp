package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/**
 * 练习房的固定十个席位可在人机与真人之间替换，不能依赖只包含真人的 Match 在线名单。
 * 通过最小宿主接口复用正式 MyGO 的枪械、经济和爆破规则，不创建正式比赛或写入战绩。
 */
public interface MyGoPracticeRuntime {
    ArenaWorld world();
    List<ServerPlayerEntity> participants();
    ServerPlayerEntity player(UUID id);
    boolean contains(UUID id);
    void broadcast(Text message);
    void finish(String reason);
}
