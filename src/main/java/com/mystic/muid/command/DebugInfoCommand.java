package com.mystic.muid.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class DebugInfoCommand {

    private DebugInfoCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("muid-debug")
                .requires(cs -> cs.hasPermission(3))
                .then(Commands.literal("mod-id-counts").executes(DebugInfoCommand::runModIdCounts))
                .then(Commands.literal("list")
                        .then(Commands.argument("registry", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    Stream<String> keys = Stream.of(ForgeRegistries.Keys.class.getDeclaredFields())
                                            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == ResourceKey.class)
                                            .map(f -> f.getName().toLowerCase());
                                    return SharedSuggestionProvider.suggest(keys, builder);
                                })
                                .executes(DebugInfoCommand::runList)));
    }

    private static int runList(CommandContext<CommandSourceStack> context) {
        String simpleName = StringArgumentType.getString(context, "registry");

        ResourceKey<?> foundResourceKey = null;

        // Find the ResourceKey by matching the field name.
        for (Field keyField : ForgeRegistries.Keys.class.getDeclaredFields()) {
            if (Modifier.isStatic(keyField.getModifiers()) && keyField.getType() == ResourceKey.class) {
                if (keyField.getName().equalsIgnoreCase(simpleName)) {
                    try {
                        foundResourceKey = (ResourceKey<?>) keyField.get(null);
                    } catch (IllegalAccessException e) {
                        // Should not happen for public static fields
                    }
                    break;
                }
            }
        }

        if (foundResourceKey == null) {
            context.getSource().sendFailure(Component.literal("Unknown registry: " + simpleName));
            return 0;
        }

        @SuppressWarnings("unchecked")
        ResourceKey<Registry<Object>> registryKey = (ResourceKey<Registry<Object>>)(ResourceKey<?>) foundResourceKey;
        Optional<Registry<Object>> registryOpt = context.getSource().registryAccess().registry(registryKey);

        if (registryOpt.isPresent()) {
            try {
                String fileName = "MUID_List_" + simpleName + ".txt";
                File myObj = new File(fileName);
                if (myObj.createNewFile()) {
                    context.getSource().sendSystemMessage(Component.literal("File created: " + myObj.getName()));
                } else {
                    context.getSource().sendSystemMessage(Component.literal("File already exists, overwriting."));
                }

                try (PrintStream out = new PrintStream(new FileOutputStream(myObj, false))) {
                    out.println("Listing " + simpleName + ":");
                    registryOpt.get().keySet().forEach(key -> out.println(key.toString()));
                }

                context.getSource().sendSystemMessage(Component.literal("File written: " + fileName));
                return Command.SINGLE_SUCCESS;

            } catch (IOException e) {
                context.getSource().sendFailure(Component.literal("Error during file writing: " + e.getMessage()));
                e.printStackTrace();
                return 0;
            }
        } else {
            context.getSource().sendFailure(Component.literal("Could not access registry: " + simpleName));
            return 0;
        }
    }

    private static int runModIdCounts(CommandContext<CommandSourceStack> context) {
        try {
            File myObj = new File("MUID_Output.txt");
            if (myObj.createNewFile()) {
                context.getSource().sendSystemMessage(Component.literal("File created: " + myObj.getName()));
            } else {
                context.getSource().sendSystemMessage(Component.literal("File already exists, overwriting."));
            }

            try (PrintStream out = new PrintStream(new FileOutputStream(myObj, false))) {
                ModList.get().getMods().forEach((modContainer -> {
                    out.println(" ");
                    out.println(modContainer.getModId());

                    long biomeCount = context.getSource().registryAccess().registry(ForgeRegistries.Keys.BIOMES)
                            .map(r -> r.keySet().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count())
                            .orElse(0L);
                    if (biomeCount > 0) {
                        out.println("Number of Biome IDs Registered: " + biomeCount);
                    }

                    long blockCount = ForgeRegistries.BLOCKS.getKeys().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count();
                    if (blockCount > 0) {
                        out.println("Number of Block IDs Registered: " + blockCount);
                    }

                    long itemCount = ForgeRegistries.ITEMS.getKeys().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count();
                    if (itemCount > 0) {
                        out.println("Number of Item IDs Registered: " + itemCount);
                    }

                    long potionCount = ForgeRegistries.POTIONS.getKeys().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count();
                    if (potionCount > 0) {
                        out.println("Number of Potion IDs Registered: " + potionCount);
                    }

                    long enchantmentCount = ForgeRegistries.ENCHANTMENTS.getKeys().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count();
                    if (enchantmentCount > 0) {
                        out.println("Number of Enchantment IDs Registered: " + enchantmentCount);
                    }

                    long blockEntityTypeCount = ForgeRegistries.BLOCK_ENTITY_TYPES.getKeys().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count();
                    if (blockEntityTypeCount > 0) {
                        out.println("Number of Tile Entity IDs Registered: " + blockEntityTypeCount);
                    }

                    long entityTypeCount = ForgeRegistries.ENTITY_TYPES.getKeys().stream().filter(loc -> loc.getNamespace().equals(modContainer.getModId())).count();
                    if (entityTypeCount > 0) {
                        out.println("Number of Entity IDs Registered: " + entityTypeCount);
                    }
                }));
            }

            context.getSource().sendSystemMessage(Component.literal("Mod Debug File Written :)"));

        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Error during reading/writing: " + e.getMessage()));
            e.printStackTrace();
        }
        return Command.SINGLE_SUCCESS;
    }
}
