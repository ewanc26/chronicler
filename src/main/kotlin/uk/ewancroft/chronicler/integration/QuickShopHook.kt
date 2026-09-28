package uk.ewancroft.chronicler.integration

import com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import uk.ewancroft.chronicler.news.EventType

/** Only loaded when QuickShop-Hikari is enabled. Each completed trade feeds the Market Report. */
class QuickShopHook(private val record: (EventType, String, String, String, Map<String, String>) -> Unit) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPurchase(event: ShopSuccessPurchaseEvent) {
        val shop = event.shop
        val buyer = event.purchaser
        record(EventType.SHOP_SALE, buyer.username ?: buyer.display, buyer.uniqueIdOptional.map { it.toString() }.orElse(""), "world", mapOf(
            "item" to shop.item.type.name.lowercase(),
            "amount" to event.amount.toString(),
            "total" to "%.2f".format(java.util.Locale.ROOT, event.balance),
            "owner" to (shop.owner.username ?: shop.owner.display),
            "direction" to if (shop.isSelling) "sold" else "bought",
        ))
    }
}
