package com.example.massbreak

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*

class MassBreakPlugin : JavaPlugin(), Listener {

    companion object {
        private const val PERM_RELOAD = "massbreak.reload"
        private const val MAX_TREE = 1024
        private const val MAX_VEIN = 256
    }

    private lateinit var enabledKey: NamespacedKey
    private lateinit var autoKey: NamespacedKey
    private val whitelistMap: MutableMap<String, MutableSet<Material>> = mutableMapOf()

    /** 26 方向隣接オフセット */
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

    // ───────────────────────── onEnable
    override fun onEnable() {
        enabledKey = NamespacedKey(this, "mb_enabled")
        autoKey   = NamespacedKey(this, "mb_auto")

        server.pluginManager.registerEvents(this, this)

        getCommand("mbtoggle")?.setExecutor { s, _, _, _ ->
            (s as? org.bukkit.entity.Player)?.toggleFlag(enabledKey, "一括破壊"); true
        }
        getCommand("mbauto")?.setExecutor { s, _, _, _ ->
            (s as? org.bukkit.entity.Player)?.toggleFlag(autoKey, "自動回収"); true
        }
        getCommand("mbreload")?.setExecutor { sender, _, _, _ ->
            if (canReload(sender)) {
                reloadLists(); sender.sendMessage("§aMassBreak の設定をリロードしました")
            }
            true
        }

        ToolType.values().forEach { whitelistMap[it.name] = it.tag.values.toMutableSet() }
        reloadLists()
        logger.info("MassBreak v12 起動完了")
    }

    // ───────────────────────── BlockBreak
    @EventHandler(ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val p = e.player
        if (!p.flag(enabledKey) || !p.isSneaking) return

        val hand   = p.inventory.itemInMainHand
        val toolKey = hand.type.name.substringAfterLast("_")   // PICKAXE など
        val list   = whitelistMap[toolKey] ?: return
        val target = e.block.type
        if (target !in list) return

        e.isDropItems = false

        when (toolKey) {
            "AXE"     -> fellTree(e.block.location, p)
            "PICKAXE" -> if (target.name.endsWith("_ORE") || target == Material.ANCIENT_DEBRIS)
                veinMine(e.block.location, target, p)
            else cubeSame(e.block.location, p, target)
            else      -> cubeGeneric(e.block.location, p, list)
        }

        // 太字 ActionBar
        p.sendActionBar(
            Component.text("注意：一括破壊オン", NamedTextColor.RED, TextDecoration.BOLD)
        )
    }

    // スニーク開始時の警告
    @EventHandler
    fun onSneak(e: PlayerToggleSneakEvent) {
        if (e.isSneaking && e.player.flag(enabledKey)) {
            e.player.sendActionBar(
                Component.text("注意：一括破壊オン", NamedTextColor.RED, TextDecoration.BOLD)
            )
        }
    }

    // ───────────────────────── /mbreload 捕捉
    @EventHandler
    fun onCmd(e: PlayerCommandPreprocessEvent) {
        if (!e.message.lowercase().startsWith("/mbreload")) return
        if (canReload(e.player)) {
            reloadLists(); e.player.sendMessage("§aMassBreak の設定をリロードしました")
        }
        e.isCancelled = true
    }

    // ───────────────────────── 破壊ロジック
    private fun fellTree(start: Location, p: org.bukkit.entity.Player) =
        bfs(start, p, MAX_TREE) { it in whitelistMap["AXE"]!! }

    private fun cubeSame(o: Location, p: org.bukkit.entity.Player, mat: Material) {
        val hand = p.inventory.itemInMainHand
        for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
            val b = o.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
            if (b.type == mat) dropAndClear(b, hand, p)
        }
    }

    private fun cubeGeneric(o: Location, p: org.bukkit.entity.Player, list: Set<Material>) {
        val hand = p.inventory.itemInMainHand
        for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
            val b = o.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
            if (b.type in list) dropAndClear(b, hand, p)
        }
    }

    private fun veinMine(start: Location, ore: Material, p: org.bukkit.entity.Player) =
        bfs(start, p, MAX_VEIN) { it == ore }

    /** 汎用 BFS */
    private fun bfs(start: Location, p: org.bukkit.entity.Player, limit: Int, match: (Material) -> Boolean) {
        val hand = p.inventory.itemInMainHand
        val visited = HashSet<Location>()
        val queue: ArrayDeque<Location> = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && visited.size < limit) {
            val loc = queue.removeFirst()
            if (!visited.add(loc)) continue
            val b = loc.block
            if (!match(b.type)) continue
            dropAndClear(b, hand, p)
            neighbor26.forEach { off ->
                queue.add(loc.clone().add(off[0].toDouble(), off[1].toDouble(), off[2].toDouble()))
            }
        }
    }

    private fun dropAndClear(b: org.bukkit.block.Block, hand: ItemStack, p: org.bukkit.entity.Player) {
        val drops = b.getDrops(hand, p)
        b.type = Material.AIR
        if (p.flag(autoKey)) p.inventory.addItem(*drops.toTypedArray())
        else drops.forEach { b.world.dropItemNaturally(b.location, it) }
    }

    // ───────────────────────── リロード & 権限
    private fun canReload(sender: CommandSender): Boolean = when (sender) {
        is ConsoleCommandSender -> true
        is org.bukkit.entity.Player -> {
            if (sender.isOp || sender.hasPermission(PERM_RELOAD)) true
            else { sender.sendMessage("§cこのコマンドは OP のみ実行できます"); false }
        }
        else -> false
    }

    private fun reloadLists() {
        ToolType.values().forEach { whitelistMap[it.name] = it.tag.values.toMutableSet() }
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

    // ───────────────────────── 拡張
    private fun org.bukkit.entity.Player.toggleFlag(key: NamespacedKey, label: String) {
        val data = persistentDataContainer
        val on = !data.has(key, PersistentDataType.BYTE)
        if (on) data.set(key, PersistentDataType.BYTE, 1) else data.remove(key)
        sendMessage("$label: ${if (on) "§aON" else "§cOFF"}")
    }
    private fun org.bukkit.entity.Player.flag(key: NamespacedKey) =
        persistentDataContainer.has(key, PersistentDataType.BYTE)
}
