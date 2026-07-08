package ir.permscraft.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Base class for all PermsCraft events fired on the Bukkit event bus.
 *
 * Other plugins can listen to any of these events:
 *
 *   @EventHandler
 *   public void onPermissionAdd(NodeAddEvent e) {
 *       if (e.getTargetType() == TargetType.USER) {
 *           UUID uuid = e.getUserUUID();
 *           String node = e.getNode().getPermission();
 *           // react to permission change
 *       }
 *   }
 *
 * All events are fired AFTER the change is applied (post-event).
 * Cancellable variants are prefixed with Pre (e.g. PreNodeAddEvent).
 */
public abstract class PermsCraftEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    protected PermsCraftEvent() { super(true); } // async = true

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    /** Who/what this event targets. */
    public enum TargetType { USER, GROUP }
}
