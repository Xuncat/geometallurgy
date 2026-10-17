package com.geometallurgy.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * 工程配置器手上记着的那一份配置：<b>哪台机器的</b> + <b>六个面分别是什么</b>。
 *
 * <p>这两件事必须一起记、一起清。少记类型，粘贴时就没法判断「这台机器收不收这份配置」，
 * 而两套面角色枚举里同一个序号含义不同（见 {@link com.geometallurgy.blockentity.FaceConfig}），
 * 贴错不会报错、只会得到一台六个面全错的机器。
 *
 * <p>读写都收在 {@link #of} / {@link #write} / {@link #clear} 三个方法里，
 * 就是为了不给「半套组件」留出口。
 */
public record FaceCopy(Identifier kind, int faces) {

	/**
	 * 从物品上读回当前的记录。
	 *
	 * @return 没记录时返回 {@code null} —— 也就是「空手」状态，这时再右键机器就是<b>复制</b>。
	 */
	public static FaceCopy of(ItemStack stack) {
		String kind = stack.get(ModComponents.FACE_COPY_KIND);
		Integer faces = stack.get(ModComponents.FACE_COPY_FACES);
		Identifier id = kind == null ? null : Identifier.tryParse(kind);
		return id == null || faces == null ? null : new FaceCopy(id, faces);
	}

	/** 记下一份配置。 */
	public static void write(ItemStack stack, Identifier kind, int faces) {
		stack.set(ModComponents.FACE_COPY_KIND, kind.toString());
		stack.set(ModComponents.FACE_COPY_FACES, faces);
	}

	/** 清空 —— 就是配置器上的「初始化」。两个组件一起摘掉，不留半套。 */
	public static void clear(ItemStack stack) {
		stack.remove(ModComponents.FACE_COPY_KIND);
		stack.remove(ModComponents.FACE_COPY_FACES);
	}

	/** 手上这份配置是不是来自某一型机器。 */
	public boolean sameKindAs(Identifier other) {
		return this.kind.equals(other);
	}

	/**
	 * 来源机器的显示名 —— 那个方块已经不在注册表里（模组卸了）就退回注册名本身。
	 *
	 * <p>判存在性用 {@code containsKey} 而<b>不是</b> {@code getOptional}：方块注册表是
	 * {@code DefaultedRegistry}，查不到的 id 会返回默认的「空气」而不是 null，
	 * 于是 {@code getOptional} 永远非空、永远显示成「空气」。这种「看起来有值」的默认值
	 * 是查注册表时最容易踩的一次。
	 */
	public Component displayKind() {
		return BuiltInRegistries.BLOCK.containsKey(this.kind)
				? BuiltInRegistries.BLOCK.getValue(this.kind).getName()
				: Component.literal(this.kind.toString());
	}
}
