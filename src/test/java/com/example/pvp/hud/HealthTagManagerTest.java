package com.example.pvp.hud;

import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthTagManagerTest {
    private static final int OWN = 11;
    private static final int OTHER = 22;

    @Test
    void filtersOwnMetadata() {
        assertNull(HealthTagManager.filterTagPacket(metadata(OWN), OWN));
    }

    @Test
    void keepsOtherMetadata() {
        var packet = metadata(OTHER);
        assertSame(packet, HealthTagManager.filterTagPacket(packet, OWN));
    }

    @Test
    void dropsBundleContainingOnlyOwnPackets() {
        var bundle = new BundleS2CPacket(List.of(metadata(OWN)));
        assertNull(HealthTagManager.filterTagPacket(bundle, OWN));
    }

    @Test
    void mixedBundleKeepsOtherPacketsAndDestructionInOrder() {
        var other = metadata(OTHER);
        var destroy = new EntitiesDestroyS2CPacket(OWN, OTHER);
        var bundle = new BundleS2CPacket(List.of(metadata(OWN), other, destroy));
        var result = assertInstanceOf(BundleS2CPacket.class, HealthTagManager.filterTagPacket(bundle, OWN));
        List<Packet<? super ClientPlayPacketListener>> kept = new ArrayList<>();
        result.getPackets().forEach(kept::add);
        assertEquals(List.of(other, destroy), kept);
    }

    @Test
    void keepsUnchangedBundle() {
        var bundle = new BundleS2CPacket(List.of(metadata(OTHER)));
        assertSame(bundle, HealthTagManager.filterTagPacket(bundle, OWN));
    }

    @Test
    void alwaysAllowsOwnAndMixedDestruction() {
        var own = new EntitiesDestroyS2CPacket(OWN);
        var mixed = new EntitiesDestroyS2CPacket(OWN, OTHER);
        assertSame(own, HealthTagManager.filterTagPacket(own, OWN));
        assertSame(mixed, HealthTagManager.filterTagPacket(mixed, OWN));
    }

    @Test
    void keepsPacketWithoutPlayer() {
        var packet = metadata(OTHER);
        assertSame(packet, HealthTagManager.filterOwnTagPacket(packet, null));
    }

    private static EntityTrackerUpdateS2CPacket metadata(int id) {
        return new EntityTrackerUpdateS2CPacket(id, List.of());
    }
}
