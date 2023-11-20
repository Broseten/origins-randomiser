package quantumxenon.randomiser.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import quantumxenon.randomiser.config.OriginsRandomiserConfig;
import quantumxenon.randomiser.enums.Reason;
import quantumxenon.randomiser.utils.MessageUtils;
import quantumxenon.randomiser.utils.OriginUtils;
import quantumxenon.randomiser.utils.ScoreboardUtils;

import static net.minecraft.world.GameMode.SPECTATOR;
import static quantumxenon.randomiser.enums.Message.*;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin extends PlayerEntity {
    private final ServerPlayerEntity player = ((ServerPlayerEntity) (Object) this);
    private static final OriginsRandomiserConfig config = OriginsRandomiserConfig.getConfig();
    @Unique
    private boolean avoidRandomizationItemOnDeath = false;

    private ServerPlayerEntityMixin(World world, BlockPos pos, float yaw, GameProfile gameProfile) {
        super(world, pos, yaw, gameProfile);
    }


    @Inject(at = @At("TAIL"), method = "onSpawn")
    private void spawn(CallbackInfo info) {
        if (ScoreboardUtils.noScoreboardTag("firstJoin", player)) {
            player.addCommandTag("firstJoin");
            ScoreboardUtils.createObjective("livesUntilRandomise", config.lives.livesBetweenRandomises, player);
            ScoreboardUtils.createObjective("sleepsUntilRandomise", config.other.sleepsBetweenRandomises, player);
            ScoreboardUtils.createObjective("uses", config.command.randomiseCommandUses, player);
            ScoreboardUtils.createObjective("lives", config.lives.startingLives, player);
            if (config.general.randomiseOrigins) {
                OriginUtils.randomOrigin(Reason.FIRST_JOIN, player);
            }
        }
    }

    @Inject(at = @At("TAIL"), method = "tick")
    private void tick(CallbackInfo info) {
        if (ScoreboardUtils.getValue("livesUntilRandomise", player) <= 0) {
            ScoreboardUtils.setValue("livesUntilRandomise", config.lives.livesBetweenRandomises, player);
        }
        if (ScoreboardUtils.getValue("sleepsUntilRandomise", player) <= 0) {
            ScoreboardUtils.setValue("sleepsUntilRandomise", config.other.sleepsBetweenRandomises, player);
        }
        if (config.lives.enableLives && ScoreboardUtils.noScoreboardTag("livesEnabledMessage", player)) {
            player.addCommandTag("livesEnabledMessage");
            player.sendMessage(MessageUtils.getMessage(LIVES_ENABLED, config.lives.startingLives), false);
        }
        if (config.command.limitCommandUses && ScoreboardUtils.noScoreboardTag("limitUsesMessage", player)) {
            player.addCommandTag("limitUsesMessage");
            player.sendMessage(MessageUtils.getMessage(LIMIT_COMMAND_USES, config.command.randomiseCommandUses), false);
        }
        if (config.lives.livesBetweenRandomises > 1 && ScoreboardUtils.noScoreboardTag("livesMessage", player)) {
            player.addCommandTag("livesMessage");
            player.sendMessage(MessageUtils.getMessage(RANDOM_ORIGIN_AFTER_LIVES, config.lives.livesBetweenRandomises), false);
        }
        if (config.other.sleepsBetweenRandomises > 1 && ScoreboardUtils.noScoreboardTag("sleepsMessage", player)) {
            player.addCommandTag("sleepsMessage");
            player.sendMessage(MessageUtils.getMessage(RANDOM_ORIGIN_AFTER_SLEEPS, config.other.sleepsBetweenRandomises), false);
        }
    }

    @Inject(at = @At("HEAD"), method = "onDeath")
    private void beforeDeath(CallbackInfo info) {
        // needs to check before death (before the inventory gets reset)
        avoidRandomizationItemOnDeath = consumeAvoidItem();
    }

    @Inject(at = @At("TAIL"), method = "onDeath")
    private void death(CallbackInfo info) {
        if (config.general.randomiseOrigins) {
            if (config.other.deathRandomisesOrigin) {
                ScoreboardUtils.changeValue("livesUntilRandomise", -1, player);
                if (config.lives.livesBetweenRandomises > 1 && ScoreboardUtils.getValue("livesUntilRandomise", player) > 0) {
                    player.sendMessage(MessageUtils.getMessage(LIVES_UNTIL_NEXT_RANDOMISE, ScoreboardUtils.getValue("livesUntilRandomise", player)), false);
                }
                if (config.lives.enableLives) {
                    ScoreboardUtils.changeValue("lives", -1, player);
                    if (ScoreboardUtils.getValue("lives", player) <= 0) {
                        player.changeGameMode(SPECTATOR);
                        player.sendMessage(MessageUtils.getMessage(OUT_OF_LIVES), false);
                    } else {
                        player.sendMessage(MessageUtils.getMessage(LIVES_REMAINING, ScoreboardUtils.getValue("lives", player)), false);
                    }
                }
                if (ScoreboardUtils.getValue("livesUntilRandomise", player) <= 0) {
                    // I know I could use "livesUntilRandomise"
                    // and increase it in a datapack when drinking e.g. a mundane potion
                    // but this is easier for me and feels a bit better in game
                    if (avoidRandomizationItemOnDeath) {
                        OriginUtils.dropExtraItems(player);
                        player.sendMessage(Text.of("Your origin seems to have hidden behind a dead bush. " +
                                "You stay: " + OriginUtils.getOrigin(player).getName().getString()));
                        avoidRandomizationItemOnDeath = false;
                    } else {
                        OriginUtils.randomOrigin(Reason.DEATH, player);
                    }
                }
            }
        } else {
            player.sendMessage(MessageUtils.getMessage(RANDOMISER_DISABLED), false);
        }
    }

    /**
     * @return Return true if the item was found in the inventory.
     */
    @Unique
    private boolean consumeAvoidItem() {
        String avoidItemName = config.other.avoidDeathRandomizeWith;
        if (avoidItemName != null && !avoidItemName.isEmpty()) {
            Item avoidItem = Registries.ITEM.get(new Identifier(avoidItemName));
            // doRandomise = player.getInventory().main.stream().noneMatch(stack -> stack.isOf(avoidItem));
            // do not randomize when the player has the specific "avoid item" in inventory
            // consume the item on death
            for (ItemStack stack : player.getInventory().main) {
                if (stack.isOf(avoidItem)) {
                    stack.decrement(1);
                    player.getInventory().updateItems();
                    return true;
                }
            }
        }
        return false;
    }

    @Inject(at = @At("TAIL"), method = "wakeUp")
    private void sleep(CallbackInfo info) {
        if (config.general.randomiseOrigins) {
            if (config.other.sleepRandomisesOrigin) {
                ScoreboardUtils.changeValue("sleepsUntilRandomise", -1, player);
                if (config.other.sleepsBetweenRandomises > 1 && ScoreboardUtils.getValue("sleepsUntilRandomise", player) > 0) {
                    player.sendMessage(MessageUtils.getMessage(SLEEPS_UNTIL_NEXT_RANDOMISE, ScoreboardUtils.getValue("sleepsUntilRandomise", player)), false);
                }
                if (ScoreboardUtils.getValue("sleepsUntilRandomise", player) <= 0) {
                    OriginUtils.randomOrigin(Reason.SLEEP, player);
                }
            }
        } else {
            player.sendMessage(MessageUtils.getMessage(RANDOMISER_DISABLED), false);
        }
    }
}