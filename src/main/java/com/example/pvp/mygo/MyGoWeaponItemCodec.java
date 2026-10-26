package com.example.pvp.mygo;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import java.util.List;

/**
 * MyGO 枪械物品协议的唯一编解码入口。
 *
 * <p>修改原因：枪械空岛箱子中的枪械需要在后续运行时恢复枪械类型、弹匣和备用弹药，
 * 不能让各个玩法直接散落解析 CUSTOM_DATA。非法或过期标签统一返回空值，避免伪造物品
 * 把运行时状态机带入异常状态。</p>
 */
public final class MyGoWeaponItemCodec {
    public static final String GUN_TAG = "pvp.skywars_gun";
    public static final String AMMO_TAG = "pvp.skywars_ammo";
    public static final String GUN_AMMO_TAG = "pvp.gun_ammo";
    public static final String GUN_RESERVE_TAG = "pvp.gun_reserve";
    public static final String KNIFE_TAG = "pvp.skywars_knife";
    public static final int KNIFE_MODEL = 71006;

    private MyGoWeaponItemCodec() {
    }

    public record GunData(MyGoWeapon weapon, int ammo, int reserve) {
        public GunData {
            if (weapon == null) throw new IllegalArgumentException("weapon");
            ammo = Math.clamp(ammo, 0, weapon.magazine);
            reserve = Math.clamp(reserve, 0, weapon.reserve);
        }
    }

    public record AmmoData(String category, int count) {
        public AmmoData {
            if (category == null || category.isBlank()) throw new IllegalArgumentException("category");
            count = Math.max(0, count);
        }
    }

    /** 从物品标签恢复枪械状态；不是枪械时返回 {@code null}。 */
    public static GunData decodeGun(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.IRON_PICKAXE)) return null;
        NbtCompound data = customData(stack);
        if (data == null || !data.contains(GUN_TAG)) return null;
        MyGoWeapon weapon = MyGoWeapon.parse(data.getString(GUN_TAG));
        if (weapon == null) return null;
        CustomModelDataComponent model = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model == null || model.value() != weapon.model) return null;
        if (!data.contains(GUN_AMMO_TAG) || !data.contains(GUN_RESERVE_TAG)) return null;
        return new GunData(weapon, data.getInt(GUN_AMMO_TAG), data.getInt(GUN_RESERVE_TAG));
    }

    public static boolean isGun(ItemStack stack) {
        return decodeGun(stack) != null;
    }

    /** 编码为单件枪械物品，数量永远固定为 1，避免一堆物品共享弹药状态。 */
    public static ItemStack encodeGun(MyGoWeapon weapon, int ammo, int reserve) {
        GunData data = new GunData(weapon, ammo, reserve);
        ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(data.weapon().model));
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("§6" + data.weapon().label + " §7(枪械空岛)"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(GUN_TAG, data.weapon().name());
        nbt.putInt(GUN_AMMO_TAG, data.ammo());
        nbt.putInt(GUN_RESERVE_TAG, data.reserve());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        // Loose ammo is absorbed into a gun's reserve. Expose both counts so
        // inventory moves/pickups cannot look like the ammunition disappeared.
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7弹匣：§f" + data.ammo() + "/" + data.weapon().magazine),
                Text.literal("§7备弹：§f" + data.reserve() + "/" + data.weapon().reserve))));
        return stack;
    }

    /** 识别 MyGO 战术刀；兼容旧版只写模型、不写刀标签的物品。 */
    public static boolean isKnife(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.IRON_SWORD)) return false;
        NbtCompound data = customData(stack);
        if (data != null && data.contains(KNIFE_TAG) && "1".equals(data.getString(KNIFE_TAG))) return true;
        CustomModelDataComponent model = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        return model != null && model.value() == KNIFE_MODEL;
    }

    /** 从弹药物品读取分类和数量；数量来自物品堆叠数。 */
    public static AmmoData decodeAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.GUNPOWDER)) return null;
        NbtCompound data = customData(stack);
        if (data == null || !data.contains(AMMO_TAG)) return null;
        String category = data.getString(AMMO_TAG);
        return category.isBlank() ? null : new AmmoData(category, stack.getCount());
    }

    public static boolean isAmmo(ItemStack stack) {
        return decodeAmmo(stack) != null;
    }

    /** 编码带分类标签的弹药堆；堆叠数量限制在原版单格上限内。 */
    public static ItemStack encodeAmmo(String category, int count) {
        if (category == null || category.isBlank() || count <= 0) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(Items.GUNPOWDER, Math.min(64, count));
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e" + category + " 弹药"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(AMMO_TAG, category);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    private static NbtCompound customData(ItemStack stack) {
        NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
        return component == null ? null : component.copyNbt();
    }
}
