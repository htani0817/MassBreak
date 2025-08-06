package com.example.massbreak

import com.jeff_media.customblockdata.CustomBlockData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.block.data.Ageable
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import kotlin.random.Random

class MassBreakPlugin : JavaPlugin(), Listener {

    companion object {
        private const val PERM_RELOAD = "massbreak.reload"
        private const val MAX_TREE = 1024
        private const val MAX_VEIN = 256
        private const val SETUP_TITLE = "MassBreak Setup"
        private const val SLOT_ENABLED = 3
        private const val SLOT_AUTO = 5
    }

    private lateinit var enabledKey: NamespacedKey
    private lateinit var autoKey: NamespacedKey
    private lateinit var keyPlaced: NamespacedKey
    private lateinit var itemNaturalKey: NamespacedKey

    private val whitelistMap: MutableMap<String, MutableSet<Material>> = mutableMapOf()
    private val msgTasks = mutableMapOf<UUID, Int>()

    private val neighbor26 = buildList {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1)
            if (dx != 0 || dy != 0 || dz != 0) add(intArrayOf(dx, dy, dz))
    }

    private enum class ToolType(val tag: Tag<Material>) {
        PICKAXE(Tag.MINEABLE_PICKAXE),
        AXE(Tag.MINEABLE_AXE),
        SHOVEL(Tag.MINEABLE_SHOVEL),
        HOE(Tag.MINEABLE_HOE),
        SHEARS(Tag.WOOL)
    }

    override fun onEnable() {
        enabledKey     = NamespacedKey(this, "mb_enabled")
        autoKey        = NamespacedKey(this, "mb_auto")
        keyPlaced      = NamespacedKey(this, "placed_by_player")
        itemNaturalKey = NamespacedKey(this, "mb_natural_item")

        CustomBlockData.registerListener(this)
        server.pluginManager.registerEvents(this, this)

        getCommand("mbtoggle")?.setExecutor { s, _, _, _ ->
            (s as? Player)?.toggleFlag(enabledKey, "一括破壊"); true
        }
        getCommand("mbauto")?.setExecutor { s, _, _, _ ->
            (s as? Player)?.toggleFlag(autoKey, "自動回収"); true
        }
        getCommand("mbreload")?.setExecutor { sender, _, _, _ ->
            if (canReload(sender)) {
                reloadLists(); sender.sendMessage("§aMassBreak の設定をリロードしました")
            }; true
        }
        getCommand("mb")?.setExecutor { s, _: Command, _: String, args: Array<out String> ->
            if (s !is Player) return@setExecutor true
            if (args.isNotEmpty() && args[0].equals("setup", true)) openSetupMenu(s)
            else s.sendMessage("§e/mb setup §7… クリックGUIでON/OFFを切替")
            true
        }

        ToolType.values().forEach { whitelistMap[it.name] = it.tag.values.toMutableSet() }
        reloadLists()
        logger.info("MassBreak v15.1 起動完了（収納ブロック保護＆中身ドロップ対応）")
    }

    @EventHandler
    fun onPlace(e: BlockPlaceEvent) {
        val meta = e.itemInHand.itemMeta
        val isNaturalItem = meta != null &&
                meta.persistentDataContainer.has(itemNaturalKey, PersistentDataType.BYTE)

        if (!isNaturalItem) {
            val pdc = CustomBlockData(e.blockPlaced, this)
            pdc.set(keyPlaced, PersistentDataType.BYTE, 1)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val p = e.player
        if (!p.flag(enabledKey) || !p.isSneaking) return
        if (isPlayerPlaced(e.block)) return
        // ★ 起点が収納ブロックなら一括破壊を発動しない
        if (isStorageBlock(e.block)) return

        val hand    = p.inventory.itemInMainHand
        val toolKey = hand.type.name.substringAfterLast("_")
        val list    = whitelistMap[toolKey] ?: return
        val target  = e.block.type
        if (target !in list) return

        e.isDropItems = false

        when (toolKey) {
            "AXE"     -> fellTree(e.block.location, p)
            "PICKAXE" -> if (target.name.endsWith("_ORE") || target == Material.ANCIENT_DEBRIS)
                veinMine(e.block.location, target, p)
            else cubeSame(e.block.location, p, target)
            else      -> cubeGeneric(e.block.location, p, list)
        }
    }

    @EventHandler
    fun onSneak(e: PlayerToggleSneakEvent) {
        val p   = e.player
        val id  = p.uniqueId

        if (e.isSneaking && p.flag(enabledKey) && isHeldTool(p.inventory.itemInMainHand.type)) {
            if (msgTasks.containsKey(id)) return
            val taskId = server.scheduler.scheduleSyncRepeatingTask(this, {
                val holding = isHeldTool(p.inventory.itemInMainHand.type)
                if (!p.isSneaking || !p.flag(enabledKey) || !holding) {
                    p.sendActionBar(Component.empty())
                    server.scheduler.cancelTask(msgTasks.remove(id) ?: return@scheduleSyncRepeatingTask)
                    return@scheduleSyncRepeatingTask
                }
                p.sendActionBar(Component.text("注意：一括破壊オン", NamedTextColor.RED, TextDecoration.BOLD))
            }, 0L, 10L)
            msgTasks[id] = taskId
        } else {
            msgTasks.remove(id)?.let { server.scheduler.cancelTask(it) }
            p.sendActionBar(Component.empty())
        }
    }

    @EventHandler
    fun onCmd(e: PlayerCommandPreprocessEvent) {
        if (!e.message.lowercase().startsWith("/mbreload")) return
        if (canReload(e.player)) {
            reloadLists(); e.player.sendMessage("§aMassBreak の設定をリロードしました")
        }
        e.isCancelled = true
    }

    private fun fellTree(start: Location, p: Player) {
        val wood = start.block.type
        bfs(start, p, MAX_TREE) { it == wood }
    }

    /** 同種3×3×3（砂/砂利の欠け対策：スナップショット→破壊） */
    private fun cubeSame(o: Location, p: Player, mat: Material) {
        val hand = p.inventory.itemInMainHand
        val targets = buildList {
            for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
                val b = o.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
                // ★ 収納ブロックは除外
                if (b.type == mat && isBreakable(b) && !isPlayerPlaced(b) && !isStorageBlock(b)) add(b)
            }
        }
        targets.forEach { dropAndClear(it, hand, p) }
    }

    /** 3×3×3 or 完熟作物ベイン（砂/砂利の欠け対策あり） */
    private fun cubeGeneric(o: Location, p: Player, list: Set<Material>) {
        val hand   = p.inventory.itemInMainHand
        val center = o.block
        if (isPlayerPlaced(center)) return

        if (center.blockData is Ageable && isBreakable(center)) {
            harvestCropVein(o, center.type, p); return
        }
        val mat = center.type
        val targets = buildList {
            for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
                val b = o.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
                // ★ 収納ブロックは除外
                if (b.type == mat && isBreakable(b) && !isPlayerPlaced(b) && !isStorageBlock(b)) add(b)
            }
        }
        targets.forEach { dropAndClear(it, hand, p) }
    }

    private fun veinMine(start: Location, ore: Material, p: Player) =
        bfs(start, p, MAX_VEIN) { it == ore }

    private fun harvestCropVein(start: Location, crop: Material, p: Player) {
        val hand = p.inventory.itemInMainHand
        val vis = HashSet<Location>()
        val q: ArrayDeque<Location> = ArrayDeque(listOf(start))
        while (q.isNotEmpty()) {
            val loc = q.removeFirst()
            if (!vis.add(loc)) continue
            val b = loc.block
            if (b.type != crop || !isBreakable(b) || isPlayerPlaced(b)) continue
            dropAndClear(b, hand, p)
            neighbor26.forEach { off ->
                q.add(loc.clone().add(off[0].toDouble(), off[1].toDouble(), off[2].toDouble()))
            }
        }
    }

    /** 汎用 BFS（収納ブロックは除外） */
    private fun bfs(start: Location, p: Player, limit: Int, match: (Material) -> Boolean) {
        val hand = p.inventory.itemInMainHand
        val visited = HashSet<Location>()
        val queue: ArrayDeque<Location> = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && visited.size < limit) {
            val loc = queue.removeFirst()
            if (!visited.add(loc)) continue
            val b = loc.block
            // ★ 収納ブロック除外
            if (!match(b.type) || !isBreakable(b) || isPlayerPlaced(b) || isStorageBlock(b)) continue
            dropAndClear(b, hand, p)
            neighbor26.forEach { off ->
                queue.add(loc.clone().add(off[0].toDouble(), off[1].toDouble(), off[2].toDouble()))
            }
        }
    }

    /** ブロックを破壊してドロップ処理 & ツール耐久値を減少 */
    private fun dropAndClear(b: org.bukkit.block.Block, hand: ItemStack, p: Player) {
        val drops = b.getDrops(hand, p).toMutableList()
        val mat   = b.type
        val naturalBlock = !isPlayerPlaced(b)

        // ★ 収納ブロックなら先に「中身」を drops に合流（※自動回収にも乗る）
        val state = b.state
        if (state is InventoryHolder) {
            state.inventory.contents.filterNotNull()
                .filter { it.type != Material.AIR }
                .forEach { drops.add(it.clone()) }
            state.inventory.clear()
        }

        // 自然由来の鉱石ブロックに PDC を付与（再配置しても“自然扱い”）
        if (naturalBlock) {
            for (i in drops.indices) {
                val it = drops[i]
                if (isOreBlock(it.type)) {
                    val meta = it.itemMeta
                    meta.persistentDataContainer.set(itemNaturalKey, PersistentDataType.BYTE, 1)
                    it.itemMeta = meta
                    drops[i] = it
                }
            }
        }

        b.type = Material.AIR

        // 自動回収 or 現地ドロップ（収納ブロックの中身もここを通る）
        if (p.flag(autoKey)) {
            val leftover = p.inventory.addItem(*drops.toTypedArray())
            leftover.values.forEach { b.world.dropItemNaturally(b.location, it) }
        } else {
            drops.forEach { b.world.dropItemNaturally(b.location, it) }
        }

        // 経験値（容器ブロックは通常XP無し）
        val xp = when (mat) {
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE      -> 1
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE    -> (2..5).random()
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> (1..5).random()
            Material.NETHER_QUARTZ_ORE                           -> (2..5).random()
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> (3..7).random()
            else -> 0
        }
        if (xp > 0) {
            val orb = b.world.spawn(b.location, ExperienceOrb::class.java)
            orb.experience = xp
        }

        // ツール耐久（Unbreaking + “ほんの気持ち”軽減）
        damageTool(p, 1)
    }

    private fun damageTool(player: Player, amount: Int) {
        val item = player.inventory.itemInMainHand ?: return
        if (item.type.maxDurability <= 0) return

        val unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING)
        var actualDamage = 0
        repeat(amount) {
            if (unbreaking == 0 || Random.nextInt(unbreaking + 1) == 0) {
                if (Random.nextDouble() >= 0.20) actualDamage++
            }
        }
        if (actualDamage == 0) return

        val meta = item.itemMeta
        if (meta is Damageable) {
            val newDamage = meta.damage + actualDamage
            if (newDamage >= item.type.maxDurability) {
                player.inventory.setItemInMainHand(null)
                player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
            } else {
                meta.damage = newDamage
                item.itemMeta = meta
            }
        }
    }

    private fun canReload(sender: CommandSender): Boolean = when (sender) {
        is ConsoleCommandSender -> true
        is Player -> {
            if (sender.isOp || sender.hasPermission(PERM_RELOAD)) true
            else { sender.sendMessage("§cこのコマンドは OP のみ実行できます"); false }
        }
        else -> false
    }

    private fun reloadLists() {
        ToolType.values().forEach { whitelistMap[it.name] = it.tag.values.toMutableSet() }
        whitelistMap["SHEARS"]!!.apply {
            addAll(Tag.LEAVES.values)
            add(Material.AZALEA_LEAVES); add(Material.FLOWERING_AZALEA_LEAVES)
        }
        whitelistMap["HOE"]!!.addAll(Tag.LEAVES.values)

        dataFolder.mkdirs()
        ToolType.values().forEach { t ->
            val file = File(dataFolder, "${t.name.lowercase()}.yml")
            if (!file.exists()) file.writeText("add: []\nremove: []\n")
            val cfg  = YamlConfiguration.loadConfiguration(file)
            val list = whitelistMap[t.name]!!
            cfg.getStringList("add").mapNotNull { Material.matchMaterial(it.uppercase()) }.forEach { list.add(it) }
            cfg.getStringList("remove").mapNotNull { Material.matchMaterial(it.uppercase()) }.forEach { list.remove(it) }
        }
    }

    private class SetupMenuHolder : InventoryHolder {
        override fun getInventory(): Inventory =
            Bukkit.createInventory(this, 9, Component.text(SETUP_TITLE))
    }

    private fun openSetupMenu(p: Player) {
        val inv = Bukkit.createInventory(SetupMenuHolder(), 9, Component.text(SETUP_TITLE))
        renderSetupMenu(p, inv); p.openInventory(inv)
    }

    private fun renderSetupMenu(p: Player, inv: Inventory) {
        for (i in 0 until inv.size) inv.setItem(i, ItemStack(Material.GRAY_STAINED_GLASS_PANE).name(" "))
        inv.setItem(SLOT_ENABLED, toggleItem("一括破壊", p.flag(enabledKey)))
        inv.setItem(SLOT_AUTO,    toggleItem("自動回収", p.flag(autoKey)))
    }

    @EventHandler
    fun onSetupClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if (e.inventory.holder !is SetupMenuHolder) return
        e.isCancelled = true
        when (e.slot) {
            SLOT_ENABLED -> p.toggleFlag(enabledKey, "一括破壊")
            SLOT_AUTO    -> p.toggleFlag(autoKey, "自動回収")
            else -> return
        }
        renderSetupMenu(p, e.inventory)
    }

    private fun toggleItem(label: String, on: Boolean): ItemStack {
        val mat = if (on) Material.LIME_DYE else Material.GRAY_DYE
        return ItemStack(mat).name("$label: ${if (on) "§aON" else "§cOFF"}", glow = on)
    }

    private fun ItemStack.name(text: String, glow: Boolean = false): ItemStack {
        val m: ItemMeta = this.itemMeta
        m.displayName(Component.text(text))
        if (glow) {
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            this.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1)
        } else {
            this.enchantments.keys.forEach { this.removeEnchantment(it) }
            m.removeItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
        this.itemMeta = m
        return this
    }

    private fun Player.toggleFlag(key: NamespacedKey, label: String) {
        val data = persistentDataContainer
        val on = !data.has(key, PersistentDataType.BYTE)
        if (on) data.set(key, PersistentDataType.BYTE, 1) else data.remove(key)
        sendMessage("$label: ${if (on) "§aON" else "§cOFF"}")
    }
    private fun Player.flag(key: NamespacedKey) =
        persistentDataContainer.has(key, PersistentDataType.BYTE)

    private fun isBreakable(b: org.bukkit.block.Block): Boolean {
        val data = b.blockData
        return if (data is Ageable) data.age == data.maximumAge else true
    }

    private fun isPlayerPlaced(block: org.bukkit.block.Block): Boolean {
        val pdc = CustomBlockData(block, this)
        return pdc.has(keyPlaced, PersistentDataType.BYTE)
    }

    private fun isOreBlock(mat: Material): Boolean {
        return mat.name.endsWith("_ORE") || mat == Material.ANCIENT_DEBRIS
    }

    /** ★ 収納ブロック判定（InventoryHolderベース＋EnderChestを明示的に含む） */
    private fun isStorageBlock(b: org.bukkit.block.Block): Boolean {
        val state = b.state
        if (state is InventoryHolder) return true // Chest/Barrel/Shulker/…の大半をカバー
        if (b.type == Material.ENDER_CHEST) return true
        return false
    }

    private fun isHeldTool(mat: Material): Boolean {
        if (mat == Material.SHEARS) return true
        val n = mat.name
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
    }
}
