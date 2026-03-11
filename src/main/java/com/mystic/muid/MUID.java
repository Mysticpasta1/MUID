package com.mystic.muid;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mod("muid")
@Mod.EventBusSubscriber(modid = "muid", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MUID
{
    public MUID() {}

    @SubscribeEvent
    public static void serverLoad(ServerStartingEvent event) {
        event.getServer().getCommands().getDispatcher().register(register());
    }

    private static final Path idsPath = Path.of("ids");
    private static final Path tagsPath = Path.of("tags");

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("muid")
                .requires(cs -> cs.hasPermission(3))
                .then(registerIdsCommand())
                .then(registerTagsCommand())
                .then(registerListCommand())
                .then(registerModIdCountCommand()
                .build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerIdsCommand() {
        var command = Commands.literal("id");
        var pairs = BuiltInRegistries.REGISTRY.holders().collect(Collectors.toMap(Holder.Reference::key, Holder::get));
        pairs.forEach((resourceKey, registry) -> {
            if (!resourceKey.location().getPath().contains("worldgen")) {
                command.then(Commands.literal(resourceKey.location().toString()).executes(ctx -> printIds(ctx, resourceKey.location(), registry)));
            }
        });
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerTagsCommand() {
        var command = Commands.literal("tags");
        var pairs = BuiltInRegistries.REGISTRY.holders().collect(Collectors.toMap(Holder.Reference::key, Holder::get));
        pairs.forEach((resourceKey, registry) -> {
            if (!resourceKey.location().getPath().contains("worldgen")) {
                command.then(Commands.literal(resourceKey.location().toString()).executes(ctx -> printTags(ctx, resourceKey.location(), registry)));
            }
        });
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerListCommand() {
        return Commands.literal("list")
                .then(Commands.argument("registry", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            Stream<String> keys = Stream.of(ForgeRegistries.Keys.class.getDeclaredFields())
                                    .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == ResourceKey.class)
                                    .map(f -> f.getName().toLowerCase());
                            return SharedSuggestionProvider.suggest(Stream.concat(keys, Stream.of("biomes-by-dimension")), builder);
                        })
                        .executes(MUID::runList));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerModIdCountCommand() {
        return Commands.literal("count").executes(MUID::runIdCountsPerMod);
    }

    private static int runList(CommandContext<CommandSourceStack> context) {
        String simpleName = StringArgumentType.getString(context, "registry");
        if ("biomes-by-dimension".equalsIgnoreCase(simpleName)) {
            return runListBiomesByDimension(context);
        } else {
            return runListGeneric(context, simpleName);
        }
    }

    private static <T> int printIds(CommandContext<CommandSourceStack> context, ResourceLocation name, Registry<T> registry) {
        try {
            if (Files.notExists(idsPath)) Files.createDirectory(idsPath);
            var file = idsPath.resolve("%s-%s.txt".formatted(name.getNamespace(), name.getPath())).toFile();
            try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
                printIdsExt(out, registry);
            }
            context.getSource().sendSystemMessage(Component.literal("%s File Written :)".formatted(name)));
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Error during reading/writing: " + e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static <T> void printIdsExt(PrintStream out, Registry<T> registry) {
        var map1 = registry.registryKeySet().stream().map(registry::getHolder)
                .filter(Optional::isPresent).map(Optional::get).reduce(new HashMap<>(), (stringMapMap, holders) -> {
                    stringMapMap.computeIfAbsent(holders.key().location().getNamespace(), a -> new HashSet<>()).add(holders.key().location());
                    return stringMapMap;
                }, (BinaryOperator<Map<String, Set<ResourceLocation>>>) (stringMapMap, stringMapMap2) -> {
                    stringMapMap.putAll(stringMapMap2);
                    return stringMapMap;
                });
        map1.forEach((s, stringSetMap) -> {
            out.println(s);
            stringSetMap.forEach((s1) -> out.println("\t" + s1));
        });
    }

    private static <T> int printTags(CommandContext<CommandSourceStack> context, ResourceLocation name, Registry<T> registry) {
        try {
            if (Files.notExists(tagsPath)) Files.createDirectory(tagsPath);
            var file = tagsPath.resolve("%s-%s.txt".formatted(name.getNamespace(), name.getPath())).toFile();
            try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
                printTagsExt(out, registry);
            }
            context.getSource().sendSystemMessage(Component.literal("Tagged %s File Written :)".formatted(name)));
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Error during reading/writing: " + e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static <T> void printTagsExt(PrintStream out, Registry<T> registry) {
        var map1 = registry.getTagNames().map(registry::getTag)
                .filter(Optional::isPresent).map(Optional::get).reduce(new HashMap<>(), (stringMapMap, holders) -> {
                    stringMapMap.computeIfAbsent(holders.key().location().getNamespace(), a -> new HashMap<>()).put(holders.key().location().getPath(),
                            holders.stream().map(Holder::unwrapKey)
                                    .filter(Optional::isPresent).map(Optional::get).map(ResourceKey::location).collect(Collectors.toSet()));
                    return stringMapMap;
                }, (BinaryOperator<Map<String, Map<String, Set<ResourceLocation>>>>) (stringMapMap, stringMapMap2) -> {
                    stringMapMap.putAll(stringMapMap2);
                    return stringMapMap;
                });
        map1.forEach((s, stringSetMap) -> {
            out.println(s);
            stringSetMap.forEach((s1, resourceLocations) -> {
                out.println("\t" + s1);
                resourceLocations.forEach(location -> out.println("\t\t" + location));
            });
        });
    }

    private static int runListBiomesByDimension(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        String fileName = "MUID_List_biomes_by_dimension.txt";
        try {
            File myObj = new File(fileName);
            if (myObj.createNewFile()) {
                context.getSource().sendSystemMessage(Component.literal("File created: " + myObj.getName()));
            } else {
                context.getSource().sendSystemMessage(Component.literal("File already exists, overwriting."));
            }
            try (PrintStream out = new PrintStream(new FileOutputStream(myObj, false))) {
                Map<ResourceLocation, Set<ResourceLocation>> dimensionToBiomes = new TreeMap<>();
                for (ServerLevel level : server.getAllLevels()) {
                    ResourceLocation dimensionLocation = level.dimension().location();
                    Set<ResourceLocation> biomes = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()
                            .stream()
                            .map(Holder::unwrapKey)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(ResourceKey::location)
                            .collect(Collectors.toCollection(TreeSet::new));
                    dimensionToBiomes.put(dimensionLocation, biomes);
                }
                dimensionToBiomes.forEach((dim, biomes) -> {
                    out.println(dim + " [");
                    biomes.forEach(biome -> out.println("    " + biome));
                    out.println("]");
                    out.println();
                });
            }
            context.getSource().sendSystemMessage(Component.literal("File written: " + fileName));
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Error during file writing: " + e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int runListGeneric(CommandContext<CommandSourceStack> context, String simpleName) {
        ResourceLocation registryRl = null;
        IForgeRegistry<?> foundRegistry = null;
        for (Field keyField : ForgeRegistries.Keys.class.getDeclaredFields()) {
            if (Modifier.isStatic(keyField.getModifiers()) && keyField.getType() == ResourceKey.class) {
                if (keyField.getName().equalsIgnoreCase(simpleName)) {
                    try {
                        registryRl = ((ResourceKey<?>) keyField.get(null)).location();
                    } catch (IllegalAccessException e) { /* ignore */ }
                    break;
                }
            }
        }
        if (registryRl == null) {
            context.getSource().sendFailure(Component.literal("Unknown registry: " + simpleName));
            return 0;
        }
        final ResourceLocation finalRl = registryRl;
        for (Field field : ForgeRegistries.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && IForgeRegistry.class.isAssignableFrom(field.getType())) {
                try {
                    IForgeRegistry<?> registry = (IForgeRegistry<?>) field.get(null);
                    if (registry != null && registry.getRegistryName().equals(finalRl)) {
                        foundRegistry = registry;
                        break;
                    }
                } catch (IllegalAccessException e) { /* ignore */ }
            }
        }
        if (foundRegistry == null) {
            for (Field field : ForgeRegistries.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == Supplier.class) {
                    try {
                        Object supplied = ((Supplier<?>) field.get(null)).get();
                        if (supplied instanceof IForgeRegistry<?> registry) {
                            if (registry.getRegistryName().equals(finalRl)) {
                                foundRegistry = registry;
                                break;
                            }
                        }
                    } catch (Exception e) { /* ignore */ }
                }
            }
        }
        if (foundRegistry != null) {
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
                    foundRegistry.getKeys().forEach(key -> out.println(key.toString()));
                }
                context.getSource().sendSystemMessage(Component.literal("File written: " + fileName));
            } catch (IOException e) {
                context.getSource().sendFailure(Component.literal("Error during file writing: " + e.getMessage()));
            }
        } else {
            context.getSource().sendFailure(Component.literal("Unknown or unhandled registry: " + simpleName));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int runIdCountsPerMod(CommandContext<CommandSourceStack> context) {
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
        }
        return Command.SINGLE_SUCCESS;
    }
}
