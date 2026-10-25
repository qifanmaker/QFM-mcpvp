package com.example.pvp.mygo;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力包中的二级倍率必须真实进入 1.21.1 客户端 FOV；只检查镜位整数会漏掉浅倍率下限。
 * 从真实客户端 JAR 读取 getFovMultiplier 字节码，仅用轻量输入代替实体基类及属性注册表，
 * 保留原始计算指令，并实际调用 GameRenderer.getFov。普通 JUnit 没有 Fabric 的注册表
 * access widener，无法完整引导实体层；本测试不构造窗口，不声称为客户端启动或画面验收。
 */
final class MyGoScopeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
    }

    @Test
    void actualVanillaClientAndRendererReachBothSniperLevelsWithoutUsingAnItem() throws Exception {
        try (ClientFixture client = new ClientFixture(1.0)) {
            for (double movement : new double[]{.065, .075, .09, .117}) {
                client.player.movementSpeed = movement;
                for (int fov : new int[]{45, 40, 15, 10}) {
                    client.player.abilities.setWalkSpeed(MyGoScope.clientWalkSpeed(movement, fov));
                    assertEquals(fov / 70.0, client.multiplier(), 1e-6,
                            "Actual vanilla FOV calculation for " + fov + " degrees");
                    assertEquals(fov, client.renderedFov(), .002,
                            "Actual renderer must preserve deep zoom above its 0.1 multiplier floor");
                    assertFalse(client.player.isUsingItem(), "Left click remains available outside item-use mode");
                }
            }
        }
    }

    @Test
    void vanillaFovEffectsSettingControlsTheAchievableZoom() throws Exception {
        try (ClientFixture client = new ClientFixture(0.0)) {
            client.player.movementSpeed = .065;
            client.player.abilities.setWalkSpeed(MyGoScope.clientWalkSpeed(.065, 10));
            assertEquals(1, client.multiplier(), 1e-6,
                    "A server-only mod cannot override the client's disabled FOV effects setting");
            assertEquals(70, client.renderedFov(), .002);
        }
    }

    @Test
    void signedScopeBaselineSurvivesVanillaPacketCodecWithoutChangingServerAbilities() {
        PlayerAbilities server = new PlayerAbilities();
        server.invulnerable = true;
        server.allowFlying = true;
        server.creativeMode = true;
        server.setFlySpeed(.037F);
        float baseline = MyGoScope.clientWalkSpeed(.065, 10);
        assertTrue(baseline < 0, "Deep zoom uses a client-only signed FOV baseline");
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            PlayerAbilitiesS2CPacket.CODEC.encode(buffer, MyGoScope.packet(server, baseline));
            PlayerAbilitiesS2CPacket received = PlayerAbilitiesS2CPacket.CODEC.decode(buffer);
            assertEquals(baseline, received.getWalkSpeed());
            assertEquals(.037F, received.getFlySpeed());
            assertTrue(received.isInvulnerable());
            assertTrue(received.allowFlying());
            assertTrue(received.isCreativeMode());
            assertFalse(received.isFlying());
            assertEquals(.1F, server.getWalkSpeed(), "Server-side abilities were never modified");
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidOrSingularInputsCannotSendNonFiniteAbilityValues() {
        for (double speed : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> MyGoScope.clientWalkSpeed(speed, 10));
        for (int fov : new int[]{0, 6, 35, 71})
            assertThrows(IllegalArgumentException.class, () -> MyGoScope.clientWalkSpeed(.065, fov));
    }

    @Test
    void scopeSensitivityFallsWithTheConfiguredZoomFov() {
        assertEquals(.5F, MyGoScope.sensitivityFactor(45), 1e-6F);
        assertEquals(10F / 90F, MyGoScope.sensitivityFactor(10), 1e-6F);
        assertThrows(IllegalArgumentException.class, () -> MyGoScope.sensitivityFactor(6));
        assertThrows(IllegalArgumentException.class, () -> MyGoScope.sensitivityFactor(91));
    }

    private static final class ClientFixture implements AutoCloseable {
        private final MinecraftClient previous;
        private final HeadlessPlayer player;
        private final GameRenderer renderer;
        private final Camera camera = new Camera();

        private ClientFixture(double fovEffects) throws Exception {
            this.previous = MinecraftClient.getInstance();
            MinecraftClient client = allocate(MinecraftClient.class);
            GameOptions options = allocate(GameOptions.class);
            set(options, "fovEffectScale", new SimpleOption<>("options.fovEffectScale", SimpleOption.emptyTooltip(),
                    (text, value) -> text, SimpleOption.DoubleSliderCallbacks.INSTANCE, fovEffects, value -> {}));
            set(options, "fov", new SimpleOption<>("options.fov", SimpleOption.emptyTooltip(),
                    (text, value) -> text, new SimpleOption.ValidatingIntSliderCallbacks(30, 110), 70, value -> {}));
            set(client, "options", options);
            this.player = createPlayerProbe();
            this.player.abilities = new PlayerAbilities();
            this.renderer = allocate(GameRenderer.class);
            set(this.renderer, "client", client);
            set(this.renderer, "fovMultiplier", 1.0F);
            set(this.renderer, "lastFovMultiplier", 1.0F);
            setStatic(MinecraftClient.class, "instance", client);
        }

        private float multiplier() throws Exception {
            return (float) this.player.getClass().getMethod("getFovMultiplier").invoke(this.player);
        }

        private double renderedFov() throws Exception {
            float value = multiplier();
            assertTrue(value >= .1F, "The deepest scope stays above the real renderer's FOV floor");
            set(this.renderer, "fovMultiplier", value);
            set(this.renderer, "lastFovMultiplier", value);
            Method fov = GameRenderer.class.getDeclaredMethod("getFov", Camera.class, float.class, boolean.class);
            fov.setAccessible(true);
            return (double) fov.invoke(this.renderer, this.camera, 1.0F, true);
        }

        @Override
        public void close() throws Exception {
            setStatic(MinecraftClient.class, "instance", this.previous);
        }
    }

    /** Only these inputs replace the full entity/world; all FOV instructions come from Minecraft. */
    public static class HeadlessPlayer {
        private PlayerAbilities abilities;
        private double movementSpeed;

        public PlayerAbilities getAbilities() { return this.abilities; }
        public double getAttributeValue(RegistryEntry<EntityAttribute> attribute) { return this.movementSpeed; }
        public ItemStack getActiveItem() { return null; }
        public boolean isUsingItem() { return false; }
        public int getItemUseTime() { return 0; }
        public boolean isUsingSpyglass() { return false; }
    }

    private static HeadlessPlayer createPlayerProbe() throws Exception {
        String original = "net/minecraft/client/network/AbstractClientPlayerEntity";
        String parent = HeadlessPlayer.class.getName().replace('.', '/');
        String name = "com/example/pvp/mygo/VanillaFovProbe";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, parent, null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        int[] copied = {0};
        try (InputStream input = MyGoScopeTest.class.getClassLoader().getResourceAsStream(original + ".class")) {
            assertNotNull(input, "Read the actual Minecraft 1.21.1 class from the test classpath");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    if (!method.equals("getFovMultiplier") || !descriptor.equals("()F")) return null;
                    copied[0]++;
                    return new MethodVisitor(Opcodes.ASM9, writer.visitMethod(access, method, descriptor, signature, exceptions)) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            super.visitMethodInsn(opcode, owner.equals(original) ? parent : owner, method, descriptor, isInterface);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            if (opcode == Opcodes.GETSTATIC && owner.equals("net/minecraft/entity/attribute/EntityAttributes")
                                    && field.equals("GENERIC_MOVEMENT_SPEED")) {
                                // The lightweight player supplies the same positive attribute value without registry bootstrap.
                                super.visitInsn(Opcodes.ACONST_NULL);
                            } else super.visitFieldInsn(opcode, owner, field, descriptor);
                        }

                        @Override
                        public void visitFrame(int type, int localCount, Object[] locals, int stackCount, Object[] stack) {
                            Object[] mapped = locals == null ? null : locals.clone();
                            if (mapped != null) for (int i = 0; i < mapped.length; i++)
                                if (original.equals(mapped[i])) mapped[i] = name;
                            super.visitFrame(type, localCount, mapped, stackCount, stack);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        assertEquals(1, copied[0], "The test executes an extracted vanilla method, never a rewritten FOV formula");
        writer.visitEnd();
        class ProbeLoader extends ClassLoader {
            ProbeLoader() { super(MyGoScopeTest.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return (HeadlessPlayer) new ProbeLoader().define(writer.toByteArray()).getDeclaredConstructor().newInstance();
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        // Reflection avoids linking internal JDK APIs or compiling proprietary-API warnings.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
