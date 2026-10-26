package com.qateralong.pearlloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.qateralong.pearlloader.mixin.ProjectileAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PearlLoader implements ModInitializer {
    public static final String MOD_ID = "pearl_loader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("pearl_loader.json");
    private static final TicketType<ChunkPos> TICKET = TicketType.create(MOD_ID, Comparator.comparingLong(ChunkPos::toLong), 40);
    private static final int REFRESH_TICKS = 20;
    private static final Map<ThrownEnderpearl, Track> TRACKED = new HashMap<>();
    private static Config config = new Config();
    private static Path storeFile;
    private static CompoundTag store = new CompoundTag();
    private static long tickCounter;

    public static class Config {
        public int radius = 2;
    }

    private static class Track {
        UUID owner;
        long chunk = Long.MIN_VALUE;
        long refreshedAt;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            loadConfig();
            storeFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MOD_ID + ".dat");
            loadStore();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(PearlLoader::storeAll);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TRACKED.clear();
            store = new CompoundTag();
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ThrownEnderpearl pearl) {
                onPearlLoad(pearl);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof ThrownEnderpearl pearl) {
                TRACKED.remove(pearl);
            }
        });
        ServerPlayerEvents.JOIN.register(player -> restore(player, player.server));
        ServerPlayerEvents.LEAVE.register(player -> storeOwner(player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(PearlLoader::tick);
    }

    private static void onPearlLoad(ThrownEnderpearl pearl) {
        UUID owner = ((ProjectileAccessor) pearl).pearlLoader$ownerUuid();
        if (owner == null) {
            return;
        }
        Track track = new Track();
        track.owner = owner;
        TRACKED.put(pearl, track);
    }

    private static void tick(MinecraftServer server) {
        tickCounter++;
        Iterator<Map.Entry<ThrownEnderpearl, Track>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ThrownEnderpearl, Track> e = it.next();
            ThrownEnderpearl pearl = e.getKey();
            if (pearl.isRemoved()) {
                it.remove();
                continue;
            }
            if (!(pearl.level() instanceof ServerLevel level)) {
                continue;
            }
            Track track = e.getValue();
            ChunkPos pos = pearl.chunkPosition();
            long key = pos.toLong();
            if (key != track.chunk || tickCounter - track.refreshedAt >= REFRESH_TICKS) {
                level.getChunkSource().addRegionTicket(TICKET, pos, config.radius, pos);
                track.chunk = key;
                track.refreshedAt = tickCounter;
            }
        }
    }

    private static boolean stash(ThrownEnderpearl pearl, UUID owner) {
        CompoundTag data = new CompoundTag();
        pearl.saveWithoutId(data);
        data.putString("id", EntityType.getKey(pearl.getType()).toString());
        CompoundTag entry = new CompoundTag();
        entry.putString("Dim", pearl.level().dimension().location().toString());
        entry.put("Pearl", data);
        String id = owner.toString();
        ListTag list = store.getList(id, Tag.TAG_COMPOUND);
        list.add(entry);
        store.put(id, list);
        pearl.discard();
        TRACKED.remove(pearl);
        return true;
    }

    private static void storeOwner(UUID owner) {
        boolean changed = false;
        for (Map.Entry<ThrownEnderpearl, Track> e : new ArrayList<>(TRACKED.entrySet())) {
            if (owner.equals(e.getValue().owner) && !e.getKey().isRemoved()) {
                changed |= stash(e.getKey(), owner);
            }
        }
        if (changed) {
            saveStore();
        }
    }

    private static void storeAll(MinecraftServer server) {
        boolean changed = false;
        for (Map.Entry<ThrownEnderpearl, Track> e : new ArrayList<>(TRACKED.entrySet())) {
            if (!e.getKey().isRemoved()) {
                changed |= stash(e.getKey(), e.getValue().owner);
            }
        }
        if (changed) {
            saveStore();
        }
    }

    private static void restore(ServerPlayer player, MinecraftServer server) {
        String id = player.getUUID().toString();
        if (!store.contains(id)) {
            return;
        }
        ListTag list = store.getList(id, Tag.TAG_COMPOUND);
        store.remove(id);
        saveStore();
        int restored = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(entry.getString("Dim"));
            ServerLevel level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
            if (level == null) {
                continue;
            }
            Entity pearl = EntityType.create(entry.getCompound("Pearl"), level).orElse(null);
            if (pearl == null) {
                continue;
            }
            ChunkPos pos = pearl.chunkPosition();
            level.getChunkSource().addRegionTicket(TICKET, pos, config.radius, pos);
            level.getChunk(pos.x, pos.z);
            if (level.addFreshEntity(pearl)) {
                restored++;
            }
        }
        LOGGER.info("Restored {} ender pearl(s) for {}", restored, player.getGameProfile().getName());
    }

    private static void loadStore() {
        store = new CompoundTag();
        if (!Files.isRegularFile(storeFile)) {
            return;
        }
        try {
            store = NbtIo.readCompressed(storeFile, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            LOGGER.error("Failed to read {}", storeFile, e);
        }
    }

    private static void saveStore() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            NbtIo.writeCompressed(store, tmp);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", storeFile, e);
        }
    }

    private static void loadConfig() {
        if (Files.isRegularFile(CONFIG_FILE)) {
            try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
                Config loaded = GSON.fromJson(r, Config.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read {}", CONFIG_FILE, e);
            }
        }
        config.radius = Math.max(0, Math.min(config.radius, 8));
        try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
            GSON.toJson(config, w);
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", CONFIG_FILE, e);
        }
    }
}
