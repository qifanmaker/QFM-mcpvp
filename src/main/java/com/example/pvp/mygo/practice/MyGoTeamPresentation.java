package com.example.pvp.mygo.practice;

import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 练习没有正式 Match 创建的原版队伍，因此人机原先会暴露头顶姓名且列表名字全部灰色。
 * 每个房间单独投影当前 T/CT 阵营，保留原队伍，替补、换边和退出不污染其他房间或大厅。
 */
final class MyGoTeamPresentation implements AutoCloseable {
    private record Membership(ServerPlayerEntity actor, Team original) { }

    private final Scoreboard scoreboard;
    private final Team attackers, defenders;
    private final Map<UUID, Membership> memberships = new HashMap<>();
    private boolean closed;

    MyGoTeamPresentation(MinecraftServer server, int region) {
        this.scoreboard = server.getScoreboard();
        this.attackers = createTeam("pvp_mgp_" + region + "_t", "MyGO 练习 · T", Formatting.RED);
        this.defenders = createTeam("pvp_mgp_" + region + "_ct", "MyGO 练习 · CT", Formatting.BLUE);
    }

    private Team createTeam(String id, String label, Formatting color) {
        // 正常关服会删除这些队伍；异常退出留下的同房间队伍也不能带入新的十席名单。
        Team stale = this.scoreboard.getTeam(id);
        if (stale != null) this.scoreboard.removeTeam(stale);
        Team team = this.scoreboard.addTeam(id);
        team.setDisplayName(Text.literal(label));
        team.setColor(color);
        team.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
        team.setCollisionRule(AbstractTeam.CollisionRule.ALWAYS);
        // MyGO 虚拟伤害由会话按正式规则结算，不用原版 team 过滤改变伤害行为。
        team.setFriendlyFireAllowed(true);
        return team;
    }

    void assign(ServerPlayerEntity actor, boolean attacking) {
        if (this.closed) return;
        String name = actor.getGameProfile().getName();
        Team target = attacking ? this.attackers : this.defenders;
        boolean joining = !this.memberships.containsKey(actor.getUuid());
        this.memberships.computeIfAbsent(actor.getUuid(), ignored ->
                new Membership(actor, this.scoreboard.getScoreHolderTeam(name)));
        if (target.getNameTagVisibilityRule() != AbstractTeam.VisibilityRule.NEVER)
            target.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
        boolean changed = this.scoreboard.getScoreHolderTeam(name) != target;
        if (!joining && !changed) return;
        if (changed) this.scoreboard.addScoreHolderToTeam(name, target);
        if (actor instanceof MyGoBotPlayer bot) {
            for (Membership membership : this.memberships.values()) {
                ServerPlayerEntity viewer = membership.actor();
                if (viewer instanceof MyGoBotPlayer || viewer.networkHandler == null) continue;
                if (joining) bot.sendListedProfile(viewer);
                // 半场只更新本房已知的人机，不能给大厅/他房未知 profile 发更新或列出人机。
                else viewer.networkHandler.sendPacket(new PlayerListS2CPacket(
                        EnumSet.of(PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME), List.of(actor)));
            }
        } else if (joining) {
            // 接管时 runtime 名单尚未替换，用本 helper 的成员表获得完整且当前有效的人机名单。
            for (Membership membership : this.memberships.values())
                if (membership.actor() instanceof MyGoBotPlayer bot) bot.sendListedProfile(actor);
        }
    }

    void release(ServerPlayerEntity actor) {
        Membership membership = this.memberships.remove(actor.getUuid());
        if (membership == null) return;
        // 列表清理独立于 scoreboard 当前归属；管理员或其他系统改队也不能留下人机 Tab 条目。
        if (actor instanceof MyGoBotPlayer) {
            for (Membership remaining : this.memberships.values()) {
                ServerPlayerEntity viewer = remaining.actor();
                if (!(viewer instanceof MyGoBotPlayer) && viewer.networkHandler != null)
                    viewer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(actor.getUuid())));
            }
        } else if (actor.networkHandler != null) {
            List<UUID> bots = this.memberships.values().stream().map(Membership::actor)
                    .filter(MyGoBotPlayer.class::isInstance).map(ServerPlayerEntity::getUuid).toList();
            if (!bots.isEmpty()) actor.networkHandler.sendPacket(new PlayerRemoveS2CPacket(bots));
        }
        String name = actor.getGameProfile().getName();
        Team current = this.scoreboard.getScoreHolderTeam(name);
        if (current != this.attackers && current != this.defenders) return;
        this.scoreboard.clearTeam(name);
        Team original = membership.original();
        if (original != null && this.scoreboard.getTeam(original.getName()) == original)
            this.scoreboard.addScoreHolderToTeam(name, original);
    }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        for (Membership membership : List.copyOf(this.memberships.values())) release(membership.actor());
        if (this.scoreboard.getTeam(this.attackers.getName()) == this.attackers) this.scoreboard.removeTeam(this.attackers);
        if (this.scoreboard.getTeam(this.defenders.getName()) == this.defenders) this.scoreboard.removeTeam(this.defenders);
    }
}
