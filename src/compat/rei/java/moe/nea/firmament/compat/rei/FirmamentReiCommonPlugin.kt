package moe.nea.firmament.compat.rei

import me.shedaniel.rei.api.common.entry.type.EntryTypeRegistry
import me.shedaniel.rei.api.common.plugins.REICommonPlugin

class FirmamentReiCommonPlugin : REICommonPlugin {
	override fun registerEntryTypes(registry: EntryTypeRegistry) {
		// Always register the entry type so old SBItemStack bookmarks can still be resolved
		// after restart, even when the REI integration is disabled via config.
		registry.register(FirmamentReiPlugin.SKYBLOCK_ITEM_TYPE_ID, SBItemEntryDefinition)
	}
}
