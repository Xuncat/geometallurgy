package com.geometallurgy.block;

import java.util.Locale;
import java.util.Map;

import com.geometallurgy.blockentity.BarrelBlockEntity;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.item.BucketMaterial;
import com.geometallurgy.item.MetalBucket;
import com.geometallurgy.item.MolCarrier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 储罐：容量 60 mol（= 4 桶）的立式容器，四种材质共用这一个类。
 *
 * <p>材质决定罐子耐不耐蚀（见 {@link BucketMaterial}），也决定贴图与方块音效。
 * 与 Immersive Engineering 的金属桶一样，玩家拿金属桶右键就能灌进/抽出，
 * 机器也能按面通过 {@link com.geometallurgy.chemical.MolTank} 直接过户 mol。
 *
 * <h2>右键规矩</h2>
 * <ul>
 *   <li><b>手里是装了料的金属桶</b>：先判定目标罐满没满、罐材质装不装得住，再动账；
 *       倒完把少掉刚倒进去那部分的桶写回同一只手；</li>
 *   <li><b>手里是空金属桶</b>：先从罐里预演最多「这只载具自己的容量」（铁桶 = 15 mol），桶材质装不住就不抽；
 *       抽完把装了料的桶写回同一只手；</li>
 *   <li><b>空手</b>：在动作栏报一句当前内容，如「铁 盐酸 12.0 / 60.0 mol」。</li>
 * </ul>
 *
 * <p>所有账目变动都只在服务端做，客户端只负责摆一下手臂 —— 两边不会各说一套。
 * 每次改完手牌都要 {@code getInventory().setChanged()} + {@code containerMenu.broadcastChanges()}，
 * 否则客户端手里那只桶的名字/悬浮文本要等下一次背包同步才刷新。
 */
public class BarrelBlock extends BaseEntityBlock {

	/** 认为「已经满了」的余量（mol）。 */
	private static final double MIN_MOL = 1.0e-6;

	/** 本罐的材质（耐蚀判定）。 */
	private final BucketMaterial material;

	public BarrelBlock(BucketMaterial material, Properties properties) {
		super(properties);
		this.material = material;
	}

	/** 本罐的材质。 */
	public BucketMaterial material() {
		return this.material;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new BarrelBlockEntity(pos, state);
	}

// ------------------------------------------------------------------ 右键交互

	/** 空手右键：只报内容，不改账。 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hit) {
		if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) {
			player.sendOverlayMessage(readout(barrel));
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * 一次只处理「一只」桶。
	 *
	 * <p>一摞桶（最多 16）共享同一个内容组件（`bucket_stock`），直接改整摞等于一次
	 * 倒 / 灌 16 只的量 —— 一边凭空多料，一边凭空丢料。这是"取出不消耗"的真凶：
	 * 拿一摞空桶去抽，一次就把整摞标成满桶，而罐子只少了一桶的量。
	 *
	 * <p>所以先要求手里只有一只；把"从摞里拆一只、改完再塞回背包"那套香草水桶行为留作后续。
	 */
	private static boolean oneAtATime(Player player, ItemStack stack) {
		if (stack.getCount() <= 1) {
			return true;
		}
		player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_one_at_a_time"));
		return false;
	}

	/** 拿金属桶右键：有料就倒进去，空桶就从罐里抽一桶。 */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		// 认接口，不硬编码认桶：金属粉也走这条路（见 {@link MolCarrier}）。
		if (!(stack.getItem() instanceof MolCarrier carrier)) {
			return InteractionResult.PASS; // 不是 mol 载具：让给别人
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS; // 客户端只摆手臂，账在服务端动
		}
		if (!(level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel)) {
			return InteractionResult.PASS;
		}
		// 一摞桶共享一个内容组件，一次只能处理一只（bug#12）；粉的账按件现推，
		// 消耗一件是安全递减，不必挡整摞。
		if (!carrier.consumedOnEmpty() && !oneAtATime(player, stack)) {
			return InteractionResult.SUCCESS;
		}
		MolStock content = carrier.stock(stack);
		if (content.isEmpty()) {
			// 只有桶（可回灌的载具）能从罐里抽料；粉的 room 恒 0，抽不了。
			if (carrier instanceof MetalBucket bucket) {
				drainInto(barrel, bucket, stack, player, hand);
			}
		} else {
			pourInto(barrel, carrier, stack, player, hand, content);
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * 把桶里的账倒进罐里。
	 *
	 * <p><b>先判定、后动手</b>：罐满了、罐材质装不住，都只提示、一点不动物品；
	 * 只有真的倒进去之后，才按「实际倒进去的量」把桶里的账扣掉并把桶写回手里。
	 */
	private void pourInto(BarrelBlockEntity barrel, MolCarrier carrier, ItemStack stack, Player player,
			InteractionHand hand, MolStock content) {
		if (barrel.room() <= MIN_MOL) {
			player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_full"));
			return;
		}
		if (!barrel.holds(content)) {
			player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_corrosion"));
			return;
		}
		if (carrier.consumedOnEmpty()) {
			// 消耗品不能像桶那样「倒一半、剩一半」：它在账上只有一个整件单位，
			// 罐装不下整份就不动，免得倒掉的那部分 mol 凭空消失。
			if (content.totalMol() > barrel.room() + MIN_MOL) {
				player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_full"));
				return;
			}
			MolStock moved = barrel.pour(content.copy(), barrel.room());
			if (moved.isEmpty()) {
				player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_full"));
				return;
			}
			// 一件粉被吃掉，手里那一格计数减一；整摞也能安全递减（账不共享）。
			ItemStack reduced = stack.copy();
			reduced.shrink(1);
			handOffOne(player, hand, stack, reduced);
			player.sendOverlayMessage(readout(barrel));
			return;
		}
		// 趁倒之前先留一份底账：MolTank.pour 会就地从 content 扣料，
		// 若事后再拿 content 算「桶里还剩多少」，同一份料会被扣两遍（部分倾倒时桶里剩得比实际少）。
		MolStock before = content.copy();
		MolStock moved = barrel.pour(content, barrel.room());
		if (moved.isEmpty()) {
			player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_full"));
			return;
		}
		// 按实际倒进去的量算出「桶里剩下什么」，直接写回去。
		// 不用 take() 逐个扣：那条路要依赖 take 的返回值语义，出过一次"灌注了但桶不变"的问题。
		MolStock remaining = MolStock.empty();
		for (Map.Entry<String, Double> e : before.view().entrySet()) {
			double kept = e.getValue() - moved.of(e.getKey());
			if (kept > MIN_MOL) {
				remaining.add(e.getKey(), kept);
			}
		}
		ItemStack emptied = stack.copyWithCount(1);
		MetalBucket.setStock(emptied, remaining);
		handOffOne(player, hand, stack, emptied);
		player.sendOverlayMessage(readout(barrel));
	}

	/** 从罐里抽最多「这只桶自己的容量」灌进空桶；桶材质装不住抽出来的料就一点不抽。 */
	private void drainInto(BarrelBlockEntity barrel, MetalBucket bucket, ItemStack stack, Player player,
			InteractionHand hand) {
		// 抽多少由载具自己说了算，不写死 15：桶换个容量，这里跟着换，不会抽超量。
		double capacity = bucket.capacityMol();
		MolStock load = preview(barrel.tank(), capacity);
		if (load.isEmpty()) {
			player.sendOverlayMessage(readout(barrel)); // 罐子本来就是空的
			return;
		}
		if (!bucket.holds(load)) {
			player.sendOverlayMessage(Component.translatable("gui.geometallurgy.barrel_corrosion"));
			return;
		}
		MolStock moved = barrel.drain(capacity, null);
		if (moved.isEmpty()) {
			return;
		}
		ItemStack filled = stack.copyWithCount(1);
		MetalBucket.setStock(filled, moved);
		handOffOne(player, hand, stack, filled);
		player.sendOverlayMessage(readout(barrel));
	}

	/**
	 * 把改写后的桶放回手上，并立刻让客户端看见。
	 *
	 * <p>{@code setItemInHand} 走的是 {@code LivingEntity.setItemSlot} →
	 * {@code PlayerEquipment.set(MAINHAND)} → {@code Inventory.setSelectedItem}（已反汇编核对），
	 * 改的确实是玩家真正持有的那一格，不是一份副本。但服务端格子变了不等于客户端知道：
	 * 不补下面的广播，客户端手里那只桶的名字与悬浮文本会停在旧内容上。
	 *
	 * <p><b>消耗品</b>（金属粉）也走这一套：调用方先把计数减一，再把手里的那一格换掉 ——
	 * 所以这里的 held 未必只有一只，但 changed 一定是「扣掉一件之后」的同一格，
	 * 不会把整摞一次消耗掉。
	 *
	 * <p>广播用 {@link Player#inventoryMenu} 而不是 {@link Player#containerMenu}：
	 * 玩家开着箱子时 {@code containerMenu} 是箱子菜单，物品栏菜单才是「手里这格」最直接的
	 * 同步通道（{@code broadcastChanges} 逐槽对比 {@code remoteSlots} 并发
	 * {@code ClientboundContainerSetSlotPacket}）。箱子菜单此时与物品栏菜单不同，再补一次，
	 * 覆盖箱子里那组物品栏槽位，两边都不落空。
	 *
	 * <p><b>创造模式</b>：这条路径不按游戏模式分叉。原版 26.x 的
	 * {@code ServerPlayerGameMode.useItemOn} 只在「方块没消费、落到物品自己的
	 * {@code ItemStack.useOn}」那条路上才看 {@code hasInfiniteMaterials()}（那段会把数量恢复）；
	 * 我们的 {@code Block.useItemOn} 返回的是消费型成功，根本走不到那里，所以倒桶 / 抽桶
	 * 在创造与生存下都改写手里这只桶，行为一致。
	 */
	private static void handOffOne(Player player, InteractionHand hand, ItemStack held, ItemStack changed) {
		// 桶必定只有一只（调用方已用 oneAtATime 挡掉整摞）；消耗品可以是整摞，
		// 但 changed 已是「扣掉一件」的同一格，原地换掉即可。
		player.setItemInHand(hand, changed);
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastChanges();
		}
	}

// ------------------------------------------------------------------ 显示

	/** 罐子内容的一句话读数：材质 + 主要成分 + mol 数 / 容量。 */
	private Component readout(BarrelBlockEntity barrel) {
		MolStock stock = barrel.tank();
		Component content = stock.isEmpty()
			? Component.translatable("item.geometallurgy.bucket.empty_content")
			: Component.translatable("chemical.geometallurgy." + dominant(stock));
		return Component.translatable("gui.geometallurgy.barrel_readout",
			Component.translatable(this.material.nameKey()),
			content,
			String.format(Locale.ROOT, "%.1f", stock.totalMol()),
			String.format(Locale.ROOT, "%.1f", BarrelBlockEntity.CAPACITY));
	}

	/** mol 最多的那种物质 id；空账返回 null。 */
	private static String dominant(MolStock stock) {
		String best = null;
		double bestMol = 0.0;
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			if (e.getValue() > bestMol) {
				bestMol = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/** 按 {@link BarrelBlockEntity#drain} 的抽取顺序，先算出「最多能抽多少 mol」。 */
	private static MolStock preview(MolStock source, double maxMol) {
		MolStock out = MolStock.empty();
		double left = maxMol;
		for (Map.Entry<String, Double> e : source.view().entrySet()) {
			if (left <= MIN_MOL) {
				break;
			}
			double take = Math.min(e.getValue(), left);
			if (take <= 0.0) {
				continue;
			}
			out.add(e.getKey(), take);
			left -= take;
		}
		return out;
	}
}
