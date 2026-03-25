package moe.nea.firmament.features.inventory

import org.lwjgl.glfw.GLFW
import net.minecraft.network.chat.Component
import net.minecraft.util.StringRepresentable
import moe.nea.firmament.annotations.Subscribe
import moe.nea.firmament.events.ItemTooltipEvent
import moe.nea.firmament.repo.HypixelStaticData
import moe.nea.firmament.util.FirmFormatters.formatCommas
import moe.nea.firmament.util.asBazaarStock
import moe.nea.firmament.util.bold
import moe.nea.firmament.util.darkGrey
import moe.nea.firmament.util.data.Config
import moe.nea.firmament.util.data.ManagedConfig
import moe.nea.firmament.util.getLogicalStackSize
import moe.nea.firmament.util.gold
import moe.nea.firmament.util.skyBlockId
import moe.nea.firmament.util.tr
import moe.nea.firmament.util.yellow
import io.github.moulberry.repo.data.NEUCraftingRecipe
import io.github.moulberry.repo.data.NEUIngredient
import moe.nea.firmament.repo.RepoManager
import moe.nea.firmament.util.SkyblockId
import kotlin.math.min

object PriceData {
	val identifier: String
		get() = "price-data"

	@Config
	object TConfig : ManagedConfig(identifier, Category.INVENTORY) {
		val tooltipEnabled by toggle("enable-always") { true }
		val enableKeybinding by keyBindingWithDefaultUnbound("enable-keybind")
		val stackSizeKey by keyBinding("stack-size-keybind") { GLFW.GLFW_KEY_LEFT_SHIFT }

		val bzPriceType by choice("bz-price-type") { BazaarPriceType.ORDERPRICES }
	}

	enum class BazaarPriceType : StringRepresentable {
		ORDERPRICES,
		INSTANTPRICES;

		override fun getSerializedName(): String = name
	}

	fun formatPrice(label: Component, price: Double): Component {
		return Component.literal("")
			.yellow()
			.bold()
			.append(label)
			.append(": ")
			.append(
				Component.literal(formatCommas(price, fractionalDigits = 1))
					.append(if (price != 1.0) " coins" else " coin")
					.gold()
					.bold()
			)
	}

	// -----------------------------
	// Pricing helpers
	// -----------------------------

	/** Market price for "BUY_ORDER" acquisition for crafting: Bazaar sellPrice (highest buy order = what you receive when instant-selling), else LBIN. */
	private fun marketUnitPriceBuyOrder(id: SkyblockId): Double? {
		HypixelStaticData.bazaarData[id.asBazaarStock]?.let { bz ->
			// sellPrice = highest outstanding buy order = what you receive when instant-selling
			return bz.quickStatus.sellPrice
		}
		return HypixelStaticData.lowestBin[id]
	}


	private fun computeCraftingCostDirectBuyOrder(sbId: SkyblockId): Double? {
		val recipes = RepoManager.getRecipesFor(sbId)
		val craftRecipes = recipes.filterIsInstance<NEUCraftingRecipe>()
		if (craftRecipes.isEmpty()) return null

		var best: Double? = null
		for (recipe in craftRecipes) {
			var total = 0.0
			var missing = false

			for (ing in recipe.inputs) {
				if (ing.itemId == NEUIngredient.NEU_SENTINEL_EMPTY) continue

				if (ing.itemId == NEUIngredient.NEU_SENTINEL_COINS) {
					total += ing.amount
					continue
				}

				val ingId = SkyblockId(ing.itemId)
				val unit = marketUnitPriceBuyOrder(ingId)
				if (unit == null) {
					missing = true
					break
				}
				total += unit * ing.amount
			}

			if (missing) continue

			val outCount = recipe.output.amount.takeIf { it > 0 } ?: 1.0
			val perItem = total / outCount
			if (best == null || perItem < best) best = perItem
		}

		return best
	}

	private const val MAX_CRAFT_DEPTH = 6

	private fun bestUnitPriceBuyOrderRecursive(
		id: SkyblockId,
		depth: Int,
		visiting: MutableSet<SkyblockId>,
	): Double? {
		val market = marketUnitPriceBuyOrder(id)

		if (depth >= MAX_CRAFT_DEPTH) return market
		if (!visiting.add(id)) return market // cycle => fall back to market

		val craft = computeCraftingCostRecursiveBuyOrder(id, depth + 1, visiting)

		visiting.remove(id)

		return when {
			market == null -> craft
			craft == null -> market
			else -> min(market, craft)
		}
	}

	private fun computeCraftingCostRecursiveBuyOrder(
		sbId: SkyblockId,
		depth: Int,
		visiting: MutableSet<SkyblockId>,
	): Double? {
		val recipes = RepoManager.getRecipesFor(sbId)
		val craftRecipes = recipes.filterIsInstance<NEUCraftingRecipe>()
		if (craftRecipes.isEmpty()) return null

		var best: Double? = null
		for (recipe in craftRecipes) {
			var total = 0.0
			var missing = false

			for (ing in recipe.inputs) {
				if (ing.itemId == NEUIngredient.NEU_SENTINEL_EMPTY) continue

				if (ing.itemId == NEUIngredient.NEU_SENTINEL_COINS) {
					total += ing.amount
					continue
				}

				val ingId = SkyblockId(ing.itemId)
				val unit = bestUnitPriceBuyOrderRecursive(ingId, depth, visiting)
				if (unit == null) {
					missing = true
					break
				}
				total += unit * ing.amount
			}

			if (missing) continue

			val outCount = recipe.output.amount.takeIf { it > 0 } ?: 1.0
			val perItem = total / outCount
			if (best == null || perItem < best) best = perItem
		}

		return best
	}

	private fun computeCraftingCostRecursiveBuyOrder(sbId: SkyblockId): Double? {
		return computeCraftingCostRecursiveBuyOrder(sbId, depth = 0, visiting = mutableSetOf())
	}

	// -----------------------------
	// Tooltip
	// -----------------------------
	@Subscribe
	fun onItemTooltip(it: ItemTooltipEvent) {
		if (!TConfig.tooltipEnabled) return
		if (TConfig.enableKeybinding.isBound && !TConfig.enableKeybinding.isPressed()) return

		val sbId = it.stack.skyBlockId
		val stackSize = it.stack.getLogicalStackSize()
		val isShowingStack = TConfig.stackSizeKey.isPressed()
		val multiplier = if (isShowingStack) stackSize else 1

		val multiplierText =
			if (isShowingStack)
				tr("firmament.tooltip.multiply", "Showing prices for x${stackSize}").darkGrey()
			else
				tr(
					"firmament.tooltip.multiply.hint",
					"[${TConfig.stackSizeKey.format()}] to show x${stackSize}"
				).darkGrey()

		val bazaarData = HypixelStaticData.bazaarData[sbId?.asBazaarStock]

		val craftDirect = sbId?.let { computeCraftingCostDirectBuyOrder(it) }
		val craftRecursive = sbId?.let { computeCraftingCostRecursiveBuyOrder(it) }

		if (bazaarData != null || craftDirect != null || craftRecursive != null) {
			it.lines.add(Component.literal(""))
			it.lines.add(multiplierText)

			if (bazaarData != null) {
				when (TConfig.bzPriceType) {
					BazaarPriceType.ORDERPRICES -> {
						// sellPrice = highest buy order (what you receive when instant-selling)
						// buyPrice  = lowest sell offer (what you pay when instant-buying)
						it.lines.add(
							formatPrice(
								Component.literal("Bazaar Buy Order"),
								bazaarData.quickStatus.sellPrice * multiplier
							)
						)
						it.lines.add(
							formatPrice(
								Component.literal("Bazaar Sell Offer"),
								bazaarData.quickStatus.buyPrice * multiplier
							)
						)
					}

					BazaarPriceType.INSTANTPRICES -> {
						// buyPrice  = lowest sell offer = what you pay to instant-buy
						// sellPrice = highest buy order = what you receive when instant-selling
						it.lines.add(
							formatPrice(
								Component.literal("Bazaar Instant Buy"),
								bazaarData.quickStatus.buyPrice * multiplier
							)
						)
						it.lines.add(
							formatPrice(
								Component.literal("Bazaar Instant Sell"),
								bazaarData.quickStatus.sellPrice * multiplier
							)
						)
					}
				}
			}

			if (craftDirect != null) {
				it.lines.add(formatPrice(Component.literal("Crafting Price (Direct, Buy Order)"), craftDirect * multiplier))
			}
			if (craftRecursive != null) {
				it.lines.add(formatPrice(Component.literal("Crafting Price (Recursive, Buy Order)"), craftRecursive * multiplier))
			}
		}
	}
}