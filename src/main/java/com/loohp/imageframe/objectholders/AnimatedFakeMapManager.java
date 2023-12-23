/*
 * This file is part of ImageFrame.
 *
 * Copyright (C) 2023. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2023. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.imageframe.objectholders;

import com.comphenix.protocol.ProtocolLibrary;
import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.api.events.ImageMapUpdatedEvent;
import com.loohp.imageframe.hooks.viaversion.ViaHook;
import com.loohp.imageframe.utils.FakeItemUtils;
import com.loohp.imageframe.utils.MapUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AnimatedFakeMapManager implements Listener, Runnable {

    private static final ItemStack ITEM_EMPTY = new ItemStack(Material.AIR);

    private final Map<ItemFrame, Holder<AnimationData>> itemFrames;
    private final Map<Player, Set<Integer>> knownMapIds;
    private final Map<Player, Set<Integer>> pendingKnownMapIds;

    public AnimatedFakeMapManager() {
        this.itemFrames = new ConcurrentHashMap<>();
        this.knownMapIds = new ConcurrentHashMap<>();
        this.pendingKnownMapIds = new ConcurrentHashMap<>();
        Scheduler.runTaskTimerAsynchronously(ImageFrame.plugin, this, 0, 1);
        Bukkit.getPluginManager().registerEvents(this, ImageFrame.plugin);
        Bukkit.getPluginManager().registerEvents(new ModernEvents(), ImageFrame.plugin);
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                handleEntity(entity);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            knownMapIds.put(player, ConcurrentHashMap.newKeySet());
            pendingKnownMapIds.put(player, ConcurrentHashMap.newKeySet());
        }
    }

    private List<Player> getEntityTrackers(Entity entity) {
        try {
            return Collections.emptyList();
//            return ProtocolLibrary.getProtocolManager().getEntityTrackers(entity);
        } catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    public void run() {
        try {
            Map<ItemFrame, FutureTask<Optional<ItemFrameInfo>>> entityTrackers = new HashMap<>();
            if (ImageFrame.handleAnimatedMapsOnMainThread) {
//                for (Map.Entry<ItemFrame, Holder<AnimationData>> entry : itemFrames.entrySet()) {
//                    ItemFrame itemFrame = entry.getKey();
//                    FutureTask<Optional<ItemFrameInfo>> future = new FutureTask<>(() -> {
//                        if (!itemFrame.isValid()) {
//                            return Optional.empty();
//                        }
//                        return Optional.of(new ItemFrameInfo(getEntityTrackers(itemFrame), itemFrame.getItem()));
//                    });
//                    Scheduler.executeOrScheduleSync(ImageFrame.plugin, future, itemFrame);
//                    Bukkit.getRegionScheduler().run(ImageFrame.plugin,itemFrame.getLocation(),task->entityTrackers.put(itemFrame, future));
//                }

            }
            Map<Player, List<FakeItemUtils.ItemFrameUpdateData>> updateData = new HashMap<>();
            for (Map.Entry<ItemFrame, Holder<AnimationData>> entry : itemFrames.entrySet()) {
                ItemFrame itemFrame = entry.getKey();
                FutureTask<Optional<ItemFrameInfo>> future = entityTrackers.get(itemFrame);
                ItemStack itemStack;
                List<Player> players;
                if (future == null) {
                    if (!itemFrame.isValid()) {
                        itemFrames.remove(itemFrame);
                        continue;
                    }
                    players = getEntityTrackers(itemFrame);
                    itemStack = null;
                } else {
                    List<Player> syncPlayers;
                    ItemStack syncItemStack;
                    try {
                        Optional<ItemFrameInfo> result = future.get(30, TimeUnit.SECONDS);
                        if (!result.isPresent()) {
                            continue;
                        }
                        ItemFrameInfo info = result.get();
                        syncPlayers = info.getTrackedPlayers();
                        syncItemStack = info.getItemStack();
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        e.printStackTrace();
                        syncPlayers = Collections.emptyList();
                        syncItemStack = ITEM_EMPTY;
                    }
                    players = syncPlayers;
                    itemStack = syncItemStack;
                }
                Holder<AnimationData> holder = entry.getValue();
                AnimationData animationData = holder.getValue();
                MapUtils.getItemMapView(null);
                MapView mapView = null;
                if (mapView == null) {
                    holder.setValue(AnimationData.EMPTY);
                    continue;
                }
                if (animationData.isEmpty() || !animationData.getMapView().equals(mapView)) {
                    ImageMap map = ImageFrame.imageMapManager.getFromMapView(mapView);
                    if (map == null || !map.requiresAnimationService()) {
                        if (!animationData.isEmpty()) {
                            holder.setValue(AnimationData.EMPTY);
                        }
                        continue;
                    }
                    holder.setValue(animationData = new AnimationData(map, mapView, map.getMapViews().indexOf(mapView)));
                } else if (!animationData.isEmpty()) {
                    if (!animationData.getImageMap().isValid()) {
                        for (Player player : players) {
                            FakeItemUtils.sendFakeItemChange(player, itemFrame, itemStack);
                        }
                        holder.setValue(AnimationData.EMPTY);
                        continue;
                    }
                }
                ImageMap imageMap = animationData.getImageMap();
                if (!imageMap.requiresAnimationService()) {
                    holder.setValue(AnimationData.EMPTY);
                    continue;
                }
                int index = animationData.getIndex();
                int currentPosition = imageMap.getCurrentPositionInSequence();
                int mapId = imageMap.getAnimationFakeMapId(currentPosition, index);
                if (mapId < 0) {
                    continue;
                }
                Set<Player> requiresSending = new HashSet<>();
                Set<Player> needReset = new HashSet<>();
                Iterator<Player> itr = players.iterator();
                while (itr.hasNext()) {
                    Player player = itr.next();
                    MapMarkerEditManager.MapMarkerEditData edit = ImageFrame.mapMarkerEditManager.getActiveEditing(player);
                    if (edit != null && Objects.equals(edit.getImageMap(), imageMap)) {
                        needReset.add(player);
                        itr.remove();
                        continue;
                    }
                    Set<Integer> knownIds = knownMapIds.get(player);
                    Set<Integer> pendingKnownIds = pendingKnownMapIds.get(player);
                    if (knownIds != null && !knownIds.contains(mapId)) {
                        if (pendingKnownIds != null && !pendingKnownIds.contains(mapId)) {
                            pendingKnownIds.addAll(imageMap.getFakeMapIds());
                            requiresSending.add(player);
                        }
                        itr.remove();
                    }
                }
                if (!requiresSending.isEmpty()) {
                    imageMap.sendAnimationFakeMaps(requiresSending, (p, i, r) -> {
                        Set<Integer> pendingKnownIds = pendingKnownMapIds.get(p);
                        if (pendingKnownIds != null && pendingKnownIds.remove(i) && r) {
                            Set<Integer> knownIds = knownMapIds.get(p);
                            if (knownIds != null) {
                                knownIds.add(i);
                            }
                        }
                    });
                }
                if (!needReset.isEmpty()) {
                    FakeItemUtils.ItemFrameUpdateData itemFrameUpdateData = new FakeItemUtils.ItemFrameUpdateData(itemFrame, itemFrame.getItem(), mapView.getId(), mapView, currentPosition);
                    needReset.forEach(p -> updateData.computeIfAbsent(p, k -> new ArrayList<>()).add(itemFrameUpdateData));
                }
                FakeItemUtils.ItemFrameUpdateData itemFrameUpdateData = new FakeItemUtils.ItemFrameUpdateData(itemFrame, getMapItem(mapId), mapView.getId(), mapView, currentPosition);
                players.forEach(p -> updateData.computeIfAbsent(p, k -> new ArrayList<>()).add(itemFrameUpdateData));
            }
            Map<Player, List<Runnable>> sendingTasks = new HashMap<>();
            for (Map.Entry<Player, List<FakeItemUtils.ItemFrameUpdateData>> entry : updateData.entrySet()) {
                Player player = entry.getKey();
                if (ImageFrame.ifPlayerManager.getIFPlayer(player.getUniqueId()).getPreference(IFPlayerPreference.VIEW_ANIMATED_MAPS, BooleanState.class).getCalculatedValue(() -> ImageFrame.getPreferenceUnsetValue(player, IFPlayerPreference.VIEW_ANIMATED_MAPS).getRawValue(true))) {
                    if (ImageFrame.viaHook && ViaHook.isPlayerLegacy(player)) {
                        if (!ImageFrame.viaDisableSmoothAnimationForLegacyPlayers) {
                            List<FakeItemUtils.ItemFrameUpdateData> list = entry.getValue();
                            for (FakeItemUtils.ItemFrameUpdateData data : list) {
                                sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> MapUtils.sendImageMap(data.getRealMapId(), data.getMapView(), data.getCurrentPosition(), Collections.singleton(player), true));
                            }
                        }
                    } else {
                        sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> FakeItemUtils.sendFakeItemChange(player, entry.getValue()));
                    }
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (ImageFrame.ifPlayerManager.getIFPlayer(player.getUniqueId()).getPreference(IFPlayerPreference.VIEW_ANIMATED_MAPS, BooleanState.class).getCalculatedValue(() -> ImageFrame.getPreferenceUnsetValue(player, IFPlayerPreference.VIEW_ANIMATED_MAPS).getRawValue(true))) {
                    ItemStack mainhand = player.getEquipment().getItemInMainHand();
                    ItemStack offhand = player.getEquipment().getItemInOffHand();
                    MapView mainHandView = MapUtils.getItemMapView(mainhand);
                    MapView offhandView = MapUtils.getItemMapView(offhand);
                    if (mainHandView != null) {
                        ImageMap mainHandMap = ImageFrame.imageMapManager.getFromMapView(mainHandView);
                        if (mainHandMap != null && mainHandMap.requiresAnimationService()) {
                            sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> mainHandMap.send(player));
                        }
                    }
                    if (offhandView != null && !offhandView.equals(mainHandView)) {
                        ImageMap offHandMap = ImageFrame.imageMapManager.getFromMapView(offhandView);
                        if (offHandMap != null && offHandMap.requiresAnimationService()) {
                            sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> offHandMap.send(player));
                        }
                    }
                }
            }
            if (ImageFrame.sendAnimatedMapsOnMainThread) {
                for (Map.Entry<Player, List<Runnable>> entry : sendingTasks.entrySet()) {
                    Scheduler.runTask(ImageFrame.plugin, () -> entry.getValue().forEach(Runnable::run), entry.getKey());
                }
            } else {
                sendingTasks.values().forEach(l -> l.forEach(Runnable::run));
            }
        } catch (Exception e) {

        }
    }

    private ItemStack getMapItem(int mapId) {
        ItemStack itemStack = new ItemStack(Material.FILLED_MAP);
        MapMeta mapMeta = (MapMeta) itemStack.getItemMeta();
        mapMeta.setMapId(mapId);
        itemStack.setItemMeta(mapMeta);
        return itemStack;
    }

    private void handleEntity(Entity entity) {
        if (!(entity instanceof ItemFrame)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) entity;
        if (itemFrames.containsKey(itemFrame)) {
            return;
        }
        MapView mapView = MapUtils.getItemMapView(itemFrame.getItem());
        if (mapView == null) {
            itemFrames.put(itemFrame, Holder.hold(AnimationData.EMPTY));
            return;
        }
        ImageMap map = ImageFrame.imageMapManager.getFromMapView(mapView);
        if (map == null || !map.requiresAnimationService()) {
            itemFrames.put(itemFrame, Holder.hold(AnimationData.EMPTY));
            return;
        }
        itemFrames.put(itemFrame, Holder.hold(new AnimationData(map, mapView, map.getMapViews().indexOf(mapView))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            handleEntity(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        handleEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        handleEntity(event.getRightClicked());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduler.runTaskLater(ImageFrame.plugin, () -> {
            if (player.isOnline()) {
                knownMapIds.put(player, ConcurrentHashMap.newKeySet());
                pendingKnownMapIds.put(player, ConcurrentHashMap.newKeySet());
            }
        }, 20);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        knownMapIds.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof ItemFrame) {
            Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
                ItemFrame itemFrame = (ItemFrame) entity;
                Holder<AnimationData> holder = itemFrames.remove(itemFrame);
                if (holder != null) {
                    itemFrames.put(itemFrame, holder);
                }
            });
        }
    }

    @EventHandler
    public void onImageMapUpdate(ImageMapUpdatedEvent event) {
        ImageMap imageMap = event.getImageMap();
        if (!imageMap.requiresAnimationService()) {
            return;
        }
        Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
            Set<Integer> ids = imageMap.getFakeMapIds();
            for (Set<Integer> knownIds : knownMapIds.values()) {
                knownIds.removeAll(ids);
            }
            for (Set<Integer> pendingKnownIds : pendingKnownMapIds.values()) {
                pendingKnownIds.removeAll(ids);
            }
        });
    }

    public class ModernEvents implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEntityLoad(EntitiesLoadEvent event) {
            for (Entity entity : event.getEntities()) {
                handleEntity(entity);
            }
        }

    }

    public static class AnimationData {

        public static final AnimationData EMPTY = new AnimationData(null, null, -1);

        private final ImageMap imageMap;
        private final MapView mapView;
        private final int index;

        public AnimationData(ImageMap imageMap, MapView mapView, int index) {
            this.imageMap = imageMap;
            this.mapView = mapView;
            this.index = index;
        }

        public boolean isEmpty() {
            return imageMap == null;
        }

        public ImageMap getImageMap() {
            return imageMap;
        }

        public MapView getMapView() {
            return mapView;
        }

        public int getIndex() {
            return index;
        }
    }

    public static class ItemFrameInfo {

        private final List<Player> trackedPlayers;
        private final ItemStack itemStack;

        public ItemFrameInfo(List<Player> trackedPlayers, ItemStack itemStack) {
            this.trackedPlayers = trackedPlayers;
            this.itemStack = itemStack;
        }

        public List<Player> getTrackedPlayers() {
            return trackedPlayers;
        }

        public ItemStack getItemStack() {
            return itemStack;
        }
    }

}
