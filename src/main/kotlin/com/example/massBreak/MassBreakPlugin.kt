package com.example.massbreak

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.block.data.Ageable
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
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.meta.Damageable
import org.bukkit.entity.Player
import org.bukkit.Sound
import kotlin.random.Random
import org.bukkit.entity.ExperienceOrb

class MassBreakPlugin : JavaPlugin(), Listener {

    companion object {
        private const val PERM_RELOAD = "massbreak.reload"
        private const val MAX_TREE = 1024
        private const val MAX_VEIN = 256
    }

    private lateinit var enabledKey: NamespacedKey
    private lateinit var autoKey: NamespacedKey
    private val whitelistMap: MutableMap<String, MutableSet<Material>> = mutableMapOf()

    /** Sneak 中の ActionBar 送信タスク */
    private val msgTasks = mutableMapOf<UUID, Int>()

    /** 26 方向隣接 */
    private val neighbor26 = buildList {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1)
            if (dx != 0 || dy != 0 || dz != 0) add(intArrayOf(dx, dy, dz))
    }

    private enum class ToolType(val tag: Tag<Material>) {
        PICKAXE(Tag.MINEABLE_PICKAXE),
        AXE(Tag.MINEABLE_AXE),
        SHOVEL(Tag.MINEABLE_SHOVEL),
        HOE(Tag.MINEABLE_HOE),
        SHEARS(Tag.WOOL)               // Paper 1.21 には MINEABLE_SHEARS が無い
    }

    // ───────────────────────── onEnable
    override fun onEnable() {
        enabledKey = NamespacedKey(this, "mb_enabled")
        autoKey    = NamespacedKey(this, "mb_auto")

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
            }; true
        }

        ToolType.values().forEach { whitelistMap[it.name] = it.tag.values.toMutableSet() }
        reloadLists()
        logger.info("MassBreak v13 起動完了")
    }

    // ───────────────────────── BlockBreak
    @EventHandler(ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val p = e.player
        if (!p.flag(enabledKey) || !p.isSneaking) return

        val hand    = p.inventory.itemInMainHand
        val toolKey = hand.type.name.substringAfterLast("_")      // PICKAXE など
        val list    = whitelistMap[toolKey] ?: return
        val target  = e.block.type
        if (target !in list) return

        e.isDropItems = false

        when (toolKey) {
            "AXE" -> fellTree(e.block.location, p)

            "PICKAXE" -> if (target.name.endsWith("_ORE") || target == Material.ANCIENT_DEBRIS)
                veinMine(e.block.location, target, p)
            else cubeSame(e.block.location, p, target)

            else -> cubeGeneric(e.block.location, p, list)  // HOE, SHOVEL, SHEARS
        }
    }

    // ───────────────────────── Sneak ActionBar (常時表示＆即消去)
    @EventHandler
    fun onSneak(e: PlayerToggleSneakEvent) {
        val p   = e.player
        val id  = p.uniqueId

        if (e.isSneaking && p.flag(enabledKey)) {
            // すでにループ中なら何もしない
            if (msgTasks.containsKey(id)) return

            // 0.5 秒ごとに ActionBar を再送
            val taskId = server.scheduler.scheduleSyncRepeatingTask(this, {
                if (!p.isSneaking || !p.flag(enabledKey)) {
                    // Sneak 解除 → タスク停止 & ActionBar 空送信で即消去
                    p.sendActionBar(Component.empty())
                    server.scheduler.cancelTask(msgTasks.remove(id) ?: return@scheduleSyncRepeatingTask)
                    return@scheduleSyncRepeatingTask
                }
                p.sendActionBar(
                    Component.text("注意：一括破壊オン", NamedTextColor.RED, TextDecoration.BOLD)
                )
            }, 0L, 10L)  // 10tick = 0.5s

            msgTasks[id] = taskId
        } else {
            // Sneak OFF：タスクがあれば停止し、ActionBar を即クリア
            msgTasks.remove(id)?.let { server.scheduler.cancelTask(it) }
            p.sendActionBar(Component.empty())
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
    private fun fellTree(start: Location, p: Player) {
        val wood = start.block.type                    // 壊し始めた原木
        bfs(start, p, MAX_TREE) { it == wood }         // 同じ木だけ破壊
    }

    private fun cubeSame(o: Location, p: org.bukkit.entity.Player, mat: Material) {
        val hand = p.inventory.itemInMainHand
        for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
            val b = o.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
            if (b.type == mat) dropAndClear(b, hand, p)
        }
    }

    /** 3×3×3 破壊 or 完熟作物ベイン */
    private fun cubeGeneric(o: Location, p: Player, list: Set<Material>) {
        val hand   = p.inventory.itemInMainHand
        val center = o.block
        // 完熟作物ならベイン収穫
        if (center.blockData is Ageable && isBreakable(center)) {
            harvestCropVein(o, center.type, p); return
        }
        // 同種ブロックのみ 3×3×3
        for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
            val b = o.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
            if (b.type == center.type && isBreakable(b)) dropAndClear(b, hand, p)
        }
    }

    private fun veinMine(start: Location, ore: Material, p: org.bukkit.entity.Player) =
        bfs(start, p, MAX_VEIN) { it == ore }

    /** 完熟作物の同種ベイン収穫 (26 方向) */
    private fun harvestCropVein(start: Location, crop: Material, p: org.bukkit.entity.Player) {
        val hand = p.inventory.itemInMainHand
        val vis = HashSet<Location>()
        val q: ArrayDeque<Location> = ArrayDeque(listOf(start))
        while (q.isNotEmpty()) {
            val loc = q.removeFirst()
            if (!vis.add(loc)) continue
            val b = loc.block
            if (b.type != crop || !isBreakable(b)) continue
            dropAndClear(b, hand, p)
            neighbor26.forEach { off ->
                q.add(loc.clone().add(off[0].toDouble(), off[1].toDouble(), off[2].toDouble()))
            }
        }
    }

    /** 汎用 BFS */
    private fun bfs(start: Location, p: org.bukkit.entity.Player, limit: Int, match: (Material) -> Boolean) {
        val hand = p.inventory.itemInMainHand
        val visited = HashSet<Location>()
        val queue: ArrayDeque<Location> = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && visited.size < limit) {
            val loc = queue.removeFirst()
            if (!visited.add(loc)) continue
            val b = loc.block
            if (!match(b.type) || !isBreakable(b)) continue
            dropAndClear(b, hand, p)
            neighbor26.forEach { off ->
                queue.add(loc.clone().add(off[0].toDouble(), off[1].toDouble(), off[2].toDouble()))
            }
        }
    }

    /** ブロックを破壊してドロップ処理 & ツール耐久値を減少 */
    private fun dropAndClear(b: org.bukkit.block.Block, hand: ItemStack, p: Player) {
        val drops = b.getDrops(hand, p)
        val mat   = b.type
        b.type = Material.AIR

        // ─── 自動回収 ─────────────────────────
        if (p.flag(autoKey)) {
            val leftover = p.inventory.addItem(*drops.toTypedArray())
            leftover.values.forEach { b.world.dropItemNaturally(b.location, it) }
        } else {
            drops.forEach { b.world.dropItemNaturally(b.location, it) }
        }

        // ─── 経験値オーブ ─────────────────────
        val xp = when (mat) {
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE      -> 1
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE    -> (2..5).random()
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> (1..5).random()
            Material.NETHER_QUARTZ_ORE                           -> (2..5).random()
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> (3..7).random()
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE,   Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE,   Material.DEEPSLATE_GOLD_ORE     -> 0  // 精錬系は XP 無し
            else -> 0
        }
        if (xp > 0) {
            val orb = b.world.spawn(b.location, ExperienceOrb::class.java)
            orb.experience = xp
        }

        // ─── ツール耐久 (Unbreaking 対応) ────────
        damageTool(p, 1)
    }

    /** Unbreaking を考慮してツール耐久を減少させる */
    private fun damageTool(player: Player, amount: Int) {
        val item = player.inventory.itemInMainHand ?: return
        if (item.type.maxDurability <= 0) return               // 耐久値が無いアイテム

        val unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING)
        var actualDamage = 0
        repeat(amount) {
            // Unbreaking: 1/(level+1) の確率で耐久を消費
            if (unbreaking == 0 || Random.nextInt(unbreaking + 1) == 0) actualDamage++
        }
        if (actualDamage == 0) return                          // 全部無効化された

        val meta = item.itemMeta
        if (meta is Damageable) {
            val newDamage = meta.damage + actualDamage
            if (newDamage >= item.type.maxDurability) {
                // ツールが壊れる
                player.inventory.setItemInMainHand(null)
                player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
            } else {
                meta.damage = newDamage
                item.itemMeta = meta
            }
        }
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
        // 追加―葉とツツジ葉を shears, hoe に加える
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

    // ───────────────────────── 拡張
    private fun org.bukkit.entity.Player.toggleFlag(key: NamespacedKey, label: String) {
        val data = persistentDataContainer
        val on = !data.has(key, PersistentDataType.BYTE)
        if (on) data.set(key, PersistentDataType.BYTE, 1) else data.remove(key)
        sendMessage("$label: ${if (on) "§aON" else "§cOFF"}")
    }
    private fun org.bukkit.entity.Player.flag(key: NamespacedKey) =
        persistentDataContainer.has(key, PersistentDataType.BYTE)

    /** 完熟判定 */
    private fun isBreakable(b: org.bukkit.block.Block): Boolean {
        val data = b.blockData
        return if (data is Ageable) data.age == data.maximumAge else true
    }
}
