package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-game proof that spoken requests really happen, run on the GameTest server against a real MCA
 * villager and a mock player (only the endpoint is faked, with fixed replies). Not shipped: the jar
 * task excludes this class and its template.
 *
 * <p>Each test speaks one unique line; the fake endpoint answers by that line, and records every
 * request so a test can check that a dishonest line was sent back to be rewritten.
 */
@GameTestHolder(McaConversations.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AiGameTests {

    private static final String TEMPLATE = "ai_field";
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    private AiGameTests() {
    }

    /** A reply as the model would write it. */
    private static String reply(String message, String impact, String effects) {
        return "{\"message\": \"" + message + "\", \"assessment\": {\"impact\": \"" + impact + "\", \"confidence\": 0.9}, "
                + "\"emotion\": \"happy\", \"memory\": null, \"effects\": [" + effects + "]}";
    }

    /** The fake endpoint: answers by the unique player line in the request; rewrites are answered truthfully. */
    private static final AiTransport FAKE = (endpoint, token, body, timeout) -> {
        REQUESTS.add(body);
        String content;
        if (body.contains("You were about to say")) {
            content = "{\"message\": \"I would love to, but I have no axe. Will you lend me yours?\"}";
        } else if (body.contains("gt-work-fallback")) {
            content = reply("Of course! Right away.", "positive", "");
        } else if (body.contains("gt-work-noaxe")) {
            content = reply("Sure, I'll go chop now!", "positive",
                    "{\"type\": \"action\", \"do\": \"work\", \"task\": \"chop\", \"amount\": 3}");
        } else if (body.contains("gt-fake-thanks")) {
            content = reply("Thanks for the axe! I'll get chopping.", "positive", "");
        } else if (body.contains("gt-give-haul")) {
            content = reply("Here you go!", "positive", "");
        } else if (body.contains("gt-refuse")) {
            content = reply("No way, I won't chop for you.", "negative",
                    "{\"type\": \"action\", \"do\": \"work\", \"task\": \"chop\"}");
        } else if (body.contains("gt-gift")) {
            content = reply("Oh, for me? Let's see.", "positive", "");
        } else {
            content = reply("Hello.", "neutral", "");
        }
        return CompletableFuture.completedFuture(AiHttpResult.ok(content));
    };

    private static void setUp() {
        McaConversationsConfig.COMMON.aiEnabled.set(true);
        McaConversationsConfig.COMMON.aiAutoConversations.set(false);
        AiConversations.setTransport(FAKE);
    }

    /** A flat field with a few trees, a real MCA villager (adult) and a mock player beside them. */
    private static Entity villager(GameTestHelper h, boolean axe) {
        setUp();
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }
        for (int[] t : new int[][]{{16, 6}, {18, 12}, {14, 17}, {19, 4}}) {
            for (int y = 1; y <= 4; y++) {
                h.setBlock(new BlockPos(t[0], y, t[1]), Blocks.OAK_LOG);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    h.setBlock(new BlockPos(t[0] + dx, 5, t[1] + dz), Blocks.OAK_LEAVES);
                }
            }
        }
        @SuppressWarnings("unchecked")
        EntityType<Entity> type = (EntityType<Entity>) BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("mca", "female_villager"));
        Entity villager = h.spawn(type, new BlockPos(8, 1, 8));
        if (villager instanceof AgeableMob ageable) {
            ageable.setAge(0);
        }
        if (axe) {
            Container inventory = McaHandles.inventory(villager);
            if (inventory == null) {
                h.fail("MCA villager has no inventory");
            }
            inventory.setItem(0, new ItemStack(Items.IRON_AXE));
        }
        return villager;
    }

    private static ServerPlayer player(GameTestHelper h) {
        java.util.Set<java.util.UUID> before = new java.util.HashSet<>();
        h.getLevel().getServer().getPlayerList().getPlayers().forEach(p -> before.add(p.getUUID()));
        ServerPlayer player;
        try {
            player = h.makeMockServerPlayerInLevel();
        } catch (UnsupportedOperationException mcaJoinScreen) {
            // MCA opens its "destiny" screen on join, which a mock connection cannot carry; the player
            // is already in the world by then.
            player = h.getLevel().getServer().getPlayerList().getPlayers().stream()
                    .filter(p -> !before.contains(p.getUUID())).findFirst().orElseThrow();
        }
        // A connection that takes every packet and drops it, so MCA's own messages to the player are harmless.
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(),
                player.connection.getConnection(), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(net.minecraft.network.protocol.Packet<?> packet) {
            }

            @Override
            public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener listener) {
            }
        };
        BlockPos at = h.absolutePos(new BlockPos(10, 1, 8));
        player.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        return player;
    }

    private static int count(Container inventory, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                n += inventory.getItem(i).getCount();
            }
        }
        return n;
    }

    private static boolean asked(String phrase) {
        return REQUESTS.stream().anyMatch(b -> b.contains("You were about to say") && b.contains(phrase));
    }

    // ------------------------------------------------------------------------------------------

    /** "Go chop wood": the model says yes but forgets the action. The villager still goes to work. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void yesToAnOrderStartsTheWork(GameTestHelper h) {
        Entity villager = villager(h, true);
        ServerPlayer player = player(h);
        AiConversations.converse(player, villager, "ve a talar 3 troncos gt-work-fallback");
        h.succeedWhen(() -> {
            h.assertTrue(AiWork.job(villager.getUUID()).isPresent(), "no work job yet");
            h.assertTrue(McaCompat.getCurrentChore(villager).orElse("").equalsIgnoreCase("CHOP"),
                    "MCA chore is " + McaCompat.getCurrentChore(villager).orElse("none"));
        });
    }

    /** The villager really chops: a tree loses its logs, and they end up with the villager or the player. */
    @GameTest(template = TEMPLATE, timeoutTicks = 3600)
    public static void theVillagerReallyChops(GameTestHelper h) {
        Entity villager = villager(h, true);
        Container inventory = McaHandles.inventory(villager);
        // Whatever MCA gave the villager to start with does not count.
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).is(Items.IRON_AXE)) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        ServerPlayer player = player(h);
        long start = h.getLevel().getGameTime();
        AiConversations.converse(player, villager, "ve a talar 3 troncos gt-work-fallback");
        h.succeedWhen(() -> {
            int standing = 0;
            for (int[] t : new int[][]{{16, 6}, {18, 12}, {14, 17}, {19, 4}}) {
                for (int y = 1; y <= 4; y++) {
                    if (h.getBlockState(new BlockPos(t[0], y, t[1])).is(Blocks.OAK_LOG)) {
                        standing++;
                    }
                }
            }
            int gathered = count(inventory, Items.OAK_LOG) + count(player.getInventory(), Items.OAK_LOG);
            h.assertTrue(standing < 16, "no tree has been cut yet (chore "
                    + McaCompat.getCurrentChore(villager).orElse("none") + ")");
            h.assertTrue(gathered >= 1, "a tree was cut but no logs were gathered");
            McaConversations.LOGGER.info("[gametest] villager chopped {} logs in {} ticks", gathered,
                    h.getLevel().getGameTime() - start);
        });
    }

    /** "Sure, I'll chop" without an axe: no job, the line is rewritten to the truth, a lend window opens. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void noAxeMeansAnHonestLineAndALendWindow(GameTestHelper h) {
        Entity villager = villager(h, false);
        ServerPlayer player = player(h);
        REQUESTS.clear();
        AiConversations.converse(player, villager, "ve a talar gt-work-noaxe");
        h.succeedWhen(() -> {
            h.assertTrue(AiWork.job(villager.getUUID()).isEmpty(), "started work without an axe");
            h.assertTrue(asked("Sure, I'll go chop now!"), "the dishonest line was not sent to be rewritten");
            h.assertTrue(player.containerMenu instanceof AiHandoverMenu, "the lend-a-tool window did not open");
            // Lending the axe through the window starts the job.
            AiActions.lend(villager, player, "Test", List.of(new ItemStack(Items.IRON_AXE)));
            h.assertTrue(AiWork.job(villager.getUUID()).isPresent(), "lending the axe did not start the work");
        });
    }

    /** "Thanks for the axe!" when no axe was given: the line is rewritten before anyone hears it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void thanksForAToolNeverGivenIsRewritten(GameTestHelper h) {
        Entity villager = villager(h, false);
        ServerPlayer player = player(h);
        AiConversations.converse(player, villager, "hola gt-fake-thanks");
        h.succeedWhen(() -> h.assertTrue(asked("Thanks for the axe!"),
                "the made-up thanks was not sent to be rewritten"));
    }

    /** "Give me what you gathered": what the villager gathered goes to the player. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void handingOverTheHaul(GameTestHelper h) {
        Entity villager = villager(h, false);
        McaHandles.inventory(villager).setItem(3, new ItemStack(Items.OAK_LOG, 5));
        ServerPlayer player = player(h);
        AiConversations.converse(player, villager, "dame lo que has recogido gt-give-haul");
        h.succeedWhen(() -> h.assertTrue(count(player.getInventory(), Items.OAK_LOG) == 5,
                "player has " + count(player.getInventory(), Items.OAK_LOG) + " logs"));
    }

    /** A refusal runs nothing, even if the model attached the action. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aRefusalRunsNothing(GameTestHelper h) {
        Entity villager = villager(h, true);
        ServerPlayer player = player(h);
        AiConversations.converse(player, villager, "ve a talar gt-refuse");
        h.runAfterDelay(80, () -> {
            if (AiWork.job(villager.getUUID()).isPresent()) {
                h.fail("the villager said no but went to work");
            } else {
                h.succeed();
            }
        });
    }

    /** "Here, I have something for you": the gift window opens for the player to choose. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void offeringAGiftOpensTheGiftWindow(GameTestHelper h) {
        Entity villager = villager(h, false);
        ServerPlayer player = player(h);
        AiConversations.converse(player, villager, "toma, te doy esto gt-gift");
        h.succeedWhen(() -> h.assertTrue(player.containerMenu instanceof AiGiftMenu, "no gift window"));
    }
}
