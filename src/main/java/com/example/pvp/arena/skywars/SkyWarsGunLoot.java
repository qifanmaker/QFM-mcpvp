package com.example.pvp.arena.skywars;

import com.example.pvp.mygo.MyGoWeapon;
import com.example.pvp.mygo.MyGoWeaponItemCodec;
import com.example.pvp.mygo.MyGoUtilities;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * 枪械空岛的独立箱子物资表。
 *
 * <p>修改原因：普通空岛的剑、弓和斧不能直接替换成 MyGO 枪械；枪械物品需要
 * 保留模型、枪械类型和初始弹药，供后续枪械运行时接管。这里独立维护战利品表，
 * 避免改变普通 SKYWARS 的已有掉落。</p>
 */
public final class SkyWarsGunLoot {
    public static final String GUN_TAG = MyGoWeaponItemCodec.GUN_TAG;
    public static final String AMMO_TAG = MyGoWeaponItemCodec.AMMO_TAG;
    public static final String GUN_AMMO_TAG = MyGoWeaponItemCodec.GUN_AMMO_TAG;
    public static final String GUN_RESERVE_TAG = MyGoWeaponItemCodec.GUN_RESERVE_TAG;

    private SkyWarsGunLoot() {
    }

    /** 每箱独立抽取；初次生成与补货使用同一张表。 */
    public static void populateIsland(List<ChestBlockEntity> chests, SkyWarsLootPolicy.Tier tier,
                                      boolean refill, SkyWarsLootPolicy policy) {
        List<ChestBlockEntity> available = new ArrayList<>(new LinkedHashSet<>(chests));
        available.removeIf(chest -> chest == null);
        Random random = policy.random();
        for (ChestBlockEntity chest : available) {
            chest.clear();
            List<ItemStack> drops = drops(random, tier);
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < chest.size(); i++) slots.add(i);
            Collections.shuffle(slots, random);
            for (int i = 0; i < drops.size() && i < slots.size(); i++) {
                chest.setStack(slots.get(i), drops.get(i));
            }
            chest.markDirty();
        }
    }

    private static List<ItemStack> drops(Random random, SkyWarsLootPolicy.Tier tier) {
        List<ItemStack> drops = new ArrayList<>();
        // 每个箱子至少有一件枪械或弹药，避免首个出生岛箱子完全无法进入枪械玩法。
        MyGoWeapon first = rollWeapon(random, tier);
        drops.add(gun(first, random));
        drops.add(ammo(ammoClass(first), ammoCount(random, tier)));

        int count = tier == SkyWarsLootPolicy.Tier.CENTER ? 5 + random.nextInt(3) : 4 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            drops.add(rollSupport(random, tier));
        }
        return drops;
    }

    private static MyGoWeapon rollWeapon(Random random, SkyWarsLootPolicy.Tier tier) {
        MyGoWeapon[] pool;
        if (tier == SkyWarsLootPolicy.Tier.SPAWN) {
            pool = new MyGoWeapon[]{MyGoWeapon.GLOCK, MyGoWeapon.USP_S, MyGoWeapon.P2000,
                    MyGoWeapon.P250, MyGoWeapon.DUALS, MyGoWeapon.TEC9, MyGoWeapon.MP9,
                    MyGoWeapon.MAC10, MyGoWeapon.MP7};
        } else if (tier == SkyWarsLootPolicy.Tier.TRANSIT) {
            pool = new MyGoWeapon[]{MyGoWeapon.P250, MyGoWeapon.FIVESEVEN, MyGoWeapon.TEC9,
                    MyGoWeapon.MP9, MyGoWeapon.MP7, MyGoWeapon.UMP45, MyGoWeapon.P90,
                    MyGoWeapon.NOVA, MyGoWeapon.XM1014, MyGoWeapon.MAG7, MyGoWeapon.SAWEDOFF};
        } else {
            pool = new MyGoWeapon[]{MyGoWeapon.AK47, MyGoWeapon.M4A4, MyGoWeapon.M4A1S,
                    MyGoWeapon.GALIL, MyGoWeapon.FAMAS, MyGoWeapon.AUG, MyGoWeapon.SG553,
                    MyGoWeapon.SSG08, MyGoWeapon.AWP, MyGoWeapon.G3SG1, MyGoWeapon.SCAR20,
                    MyGoWeapon.M249, MyGoWeapon.NEGEV, MyGoWeapon.XM1014};
        }
        return pool[random.nextInt(pool.length)];
    }

    private static ItemStack rollSupport(Random random, SkyWarsLootPolicy.Tier tier) {
        return switch (random.nextInt(14)) {
            case 0 -> stack(Items.IRON_CHESTPLATE, 1);
            case 1 -> stack(tier == SkyWarsLootPolicy.Tier.CENTER ? Items.DIAMOND_HELMET : Items.IRON_HELMET, 1);
            case 2 -> stack(Items.COOKED_BEEF, 4 + random.nextInt(5));
            case 3 -> stack(Items.OAK_PLANKS, 16 + random.nextInt(17));
            case 4 -> stack(Items.COBWEB, 1 + random.nextInt(3));
            case 5 -> stack(Items.ENDER_PEARL, tier == SkyWarsLootPolicy.Tier.CENTER ? 2 : 1);
            case 6 -> stack(Items.GOLDEN_APPLE, tier == SkyWarsLootPolicy.Tier.CENTER ? 2 : 1);
            case 7 -> stack(Items.EXPERIENCE_BOTTLE, 4 + random.nextInt(8));
            case 8 -> ammo(ammoClass(rollWeapon(random, tier)), ammoCount(random, tier));
            case 9 -> MyGoUtilities.Type.HE.stack(1);
            case 10 -> MyGoUtilities.Type.FLASH.stack(1);
            case 11 -> MyGoUtilities.Type.SMOKE.stack(1);
            case 12 -> MyGoUtilities.Type.MOLOTOV.stack(1);
            case 13 -> MyGoUtilities.Type.DECOY.stack(1);
            default -> stack(Items.WATER_BUCKET, 1);
        };
    }

    private static ItemStack gun(MyGoWeapon weapon, Random random) {
        int reserve = Math.max(weapon.magazine, weapon.reserve / 4)
                + random.nextInt(Math.max(1, weapon.magazine / 2 + 1));
        return createGunItem(weapon, reserve);
    }

    /** 创建单件枪械箱子物品；后续运行时可直接用这些标签恢复弹药状态。 */
    public static ItemStack createGunItem(MyGoWeapon weapon, int reserve) {
        return MyGoWeaponItemCodec.encodeGun(weapon, weapon == null ? 0 : weapon.magazine, reserve);
    }

    private static ItemStack ammo(String category, int count) {
        return createAmmoItem(category, count);
    }

    /** 创建带分类标签的弹药补给物品。 */
    public static ItemStack createAmmoItem(String category, int count) {
        return MyGoWeaponItemCodec.encodeAmmo(category, count);
    }

    private static int ammoCount(Random random, SkyWarsLootPolicy.Tier tier) {
        int base = tier == SkyWarsLootPolicy.Tier.CENTER ? 24 : tier == SkyWarsLootPolicy.Tier.TRANSIT ? 16 : 12;
        return base + random.nextInt(base / 2 + 1);
    }

    private static String ammoClass(MyGoWeapon weapon) {
        if (weapon.shotgun()) return "霰弹枪";
        if (weapon.sniper()) return "狙击枪";
        if (weapon.smg()) return "冲锋枪";
        if (weapon.pistol()) return "手枪";
        if (weapon.category().equals("机枪")) return "机枪";
        return "步枪";
    }

    private static ItemStack stack(net.minecraft.item.Item item, int count) {
        return new ItemStack(item, Math.max(1, count));
    }
}
