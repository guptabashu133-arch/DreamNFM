package org.mydreamduels.dreamnfm.action;

import org.bukkit.entity.Player;
import org.mydreamduels.dreamnfm.DreamNFM;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ordered actions + a per-player click cooldown (stops double clicks firing twice). */
public final class ActionList {

    private final List<Action> actions = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private volatile long cooldownMs = 500;

    public List<Action> actions() {
        return actions;
    }

    public long cooldownMs() {
        return cooldownMs;
    }

    public void setCooldownMs(long ms) {
        this.cooldownMs = Math.max(0, ms);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public List<String> serialize() {
        List<String> out = new ArrayList<>();
        for (Action a : actions) out.add(a.serialize());
        return out;
    }

    public void load(List<String> raw, java.util.logging.Logger log, String owner) {
        actions.clear();
        for (String s : raw) {
            try {
                actions.add(Action.parse(s));
            } catch (IllegalArgumentException e) {
                log.warning(owner + ": skipped action '" + s + "' - " + e.getMessage());
            }
        }
    }

    /** True if the click was accepted (not on cooldown). Player's thread. */
    public boolean click(DreamNFM plugin, Player p) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return false;
        lastClick.put(p.getUniqueId(), now);
        for (Action a : actions) a.run(plugin, p);
        return true;
    }

    public void forget(UUID id) {
        lastClick.remove(id);
    }
}
