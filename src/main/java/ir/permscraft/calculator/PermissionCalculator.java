package ir.permscraft.calculator;

import ir.permscraft.PermsCraft;
import ir.permscraft.inheritance.InheritanceGraph;
import ir.permscraft.inject.PCPermissible;
import ir.permscraft.inject.PermissibleInjector;
import ir.permscraft.models.User;
import ir.permscraft.utils.WildcardUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Explains exactly why a player does or does not have a permission.
 *
 * FIX (Bug #2 — /pc check could disagree with real player behavior):
 * the previous implementation re-derived grant/deny from scratch using its
 * own first-match-wins loop over User#getPermissions(), a
 * ConcurrentHashMap-backed Set with no defined iteration order. That made
 * results non-deterministic whenever a user had both a grant and a deny
 * matching the same permission, ignored wildcard specificity rules, never
 * considered context-bound permissions, and never accounted for OP bypass —
 * so its answer could differ from what the player actually experiences.
 *
 * This version always asks the SAME authoritative source the player's real
 * permission check goes through:
 *   - Online player → PCPermissible#hasPermission() — the exact live code
 *     path Bukkit calls, including OP bypass and resolved context.
 *   - Offline player → InheritanceGraph#resolveUserWithSource() — the same
 *     weighted-inheritance resolution used to build the online player's
 *     permission map (context is not available for an offline player, since
 *     context calculators need a live Player; this is noted in the output).
 *
 * The step-by-step trace is built FROM that authoritative source (using its
 * real per-node "supplied by" attribution) instead of being computed
 * independently, so the trace and the final verdict can never disagree.
 */
public class PermissionCalculator {

    private final PermsCraft plugin;

    public PermissionCalculator(PermsCraft plugin) { this.plugin = plugin; }

    public CalculationResult calculate(User user, String permission) {
        String lc = permission.toLowerCase();
        CalculationResult result = new CalculationResult(permission);

        Player online = (Bukkit.getServer() != null) ? Bukkit.getPlayer(user.getUuid()) : null;
        if (online != null && online.isOnline()) {
            calculateOnline(online, user, lc, result);
        } else {
            calculateOffline(user, lc, result);
        }
        return result;
    }

    // ── Online: defer to the exact live permission-check code path ─────────────

    private void calculateOnline(Player player, User user, String lc, CalculationResult result) {
        PCPermissible pc = PermissibleInjector.get(player);
        boolean isOp = player.isOp()
                && plugin.getConfig().getBoolean("settings.op-grants-all", true);

        // Build the trace from the same InheritanceGraph resolution that fed
        // this player's live permission map, so the explanation lines up with
        // the authoritative result below — even when OP bypass changes the
        // final verdict.
        addTraceSteps(plugin.getInheritanceGraph().resolveUserWithSource(user), lc, result);

        boolean granted = (pc != null) ? pc.hasPermission(lc) : player.hasPermission(lc);

        String reason;
        if (isOp && result.steps.stream().noneMatch(s -> s.node().equalsIgnoreCase(lc) || s.node().contains("*"))) {
            reason = "Player is OP (settings.op-grants-all) — no matching node found otherwise";
        } else if (isOp) {
            reason = granted
                    ? "Resolved by inheritance graph; player is also OP"
                    : "Explicit deny wins even though player is OP";
        } else {
            reason = granted ? "Resolved by inheritance graph (live, online)"
                              : "No matching grant found (live, online)";
        }
        result.setFinalResult(granted, Source.LIVE, reason);
    }

    // ── Offline: best-effort resolution without a live Player/context ──────────

    private void calculateOffline(User user, String lc, CalculationResult result) {
        Map<String, InheritanceGraph.ResolvedPermission> resolved;

        if (plugin.getInheritanceGraph() != null) {
            resolved = plugin.getInheritanceGraph().resolveUserWithSource(user);
        } else {
            resolved = resolveDirectly(user);
        }

        addTraceSteps(resolved, lc, result);

        result.addStep(Source.NONE, "\u2014", false,
                "Player is offline: context-bound permissions (world, gamemode, etc.) "
                + "could not be evaluated and are excluded from this check.");

        if (resolved.containsKey(lc)) {
            var rp = resolved.get(lc);
            result.setFinalResult(rp.value(), sourceOf(rp.source()),
                    (rp.value() ? "Granted" : "Denied") + " by " + rp.source());
            return;
        }

        String bestNode = null;
        int bestSpecificity = -1;
        InheritanceGraph.ResolvedPermission bestRp = null;
        for (var e : resolved.entrySet()) {
            String node = e.getKey();
            if (!node.contains("*")) continue;
            if (!WildcardUtil.matches(node, lc)) continue;
            int specificity = node.indexOf('*');
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                bestNode = node;
                bestRp = e.getValue();
            }
        }
        if (bestNode != null) {
            result.setFinalResult(bestRp.value(), sourceOf(bestRp.source()),
                    (bestRp.value() ? "Granted" : "Denied") + " by wildcard from " + bestRp.source());
            return;
        }

        result.setFinalResult(false, Source.NONE, "No permission node found");
    }

    private Map<String, InheritanceGraph.ResolvedPermission> resolveDirectly(User user) {
        Map<String, InheritanceGraph.ResolvedPermission> out = new LinkedHashMap<>();

        if (plugin.getGroupManager() != null) {
            List<String> groupNames = new ArrayList<>(user.getGroups());
            groupNames.sort((a, b) -> {
                var ga = plugin.getGroupManager().getGroup(a);
                var gb = plugin.getGroupManager().getGroup(b);
                return Integer.compare(
                        ga != null ? ga.getWeight() : 0,
                        gb != null ? gb.getWeight() : 0);
            });
            for (String groupName : groupNames) {
                resolveGroupDirectly(groupName, out, new HashSet<>());
            }
        }

        if (plugin.getTimedPermissionManager() != null) {
            var timed = plugin.getTimedPermissionManager().getActivePermissions(user.getUuid().toString());
            if (timed != null) {
                for (String perm : timed) {
                    boolean value = !perm.startsWith("-");
                    String node = value ? perm.toLowerCase() : perm.substring(1).toLowerCase();
                    out.put(node, new InheritanceGraph.ResolvedPermission(value, "timed"));
                }
            }
        }

        for (String perm : user.getPermissions()) {
            boolean value = !perm.startsWith("-");
            String node = value ? perm.toLowerCase() : perm.substring(1).toLowerCase();
            out.put(node, new InheritanceGraph.ResolvedPermission(value, "personal"));
        }

        return out;
    }

    private void resolveGroupDirectly(String groupName,
                                      Map<String, InheritanceGraph.ResolvedPermission> out,
                                      Set<String> visited) {
        if (visited.contains(groupName)) return;
        visited.add(groupName);
        if (plugin.getGroupManager() == null) return;
        var group = plugin.getGroupManager().getGroup(groupName);
        if (group == null) return;

        // Resolve parents first (lower priority)
        List<String> parents = new ArrayList<>(group.getInheritedGroups());
        parents.sort((a, b) -> {
            var ga = plugin.getGroupManager().getGroup(a);
            var gb = plugin.getGroupManager().getGroup(b);
            return Integer.compare(
                    ga != null ? ga.getWeight() : 0,
                    gb != null ? gb.getWeight() : 0);
        });
        for (String parent : parents) {
            resolveGroupDirectly(parent, out, visited);
        }

        // Apply this group's own permissions (overrides parents)
        for (String perm : group.getPermissions()) {
            boolean value = !perm.startsWith("-");
            String node = value ? perm.toLowerCase() : perm.substring(1).toLowerCase();
            out.put(node, new InheritanceGraph.ResolvedPermission(value, "group:" + groupName));
        }
    }

    // ── Shared trace builder ─────────────────────────────────────────────────

    private void addTraceSteps(Map<String, InheritanceGraph.ResolvedPermission> resolved,
                                String lc, CalculationResult result) {
        for (var e : resolved.entrySet()) {
            String node = e.getKey();
            if (!WildcardUtil.matches(node, lc) && !node.equalsIgnoreCase(lc)) continue;
            var rp = e.getValue();
            result.addStep(sourceOf(rp.source()), node, rp.value(),
                    "Supplied by " + rp.source());
        }
    }

    private static Source sourceOf(String source) {
        if (source == null) return Source.NONE;
        if (source.startsWith("personal")) return Source.PERSONAL;
        if (source.startsWith("timed"))    return Source.TIMED;
        return Source.GROUP;
    }

    // ── Result model ──────────────────────────────────────────────────────────

    public enum Source { PERSONAL, TIMED, GROUP, INHERITED, LIVE, NONE }

    public static class CalculationResult {
        public final String permission;
        public final List<Step> steps = new ArrayList<>();
        public boolean finalResult = false;
        public Source finalSource  = Source.NONE;
        public String finalReason  = "No permission node found";

        public CalculationResult(String permission) { this.permission = permission; }

        void addStep(Source source, String node, boolean value, String reason) {
            steps.add(new Step(source, node, value, reason));
        }

        void setFinalResult(boolean result, Source source, String reason) {
            this.finalResult = result;
            this.finalSource = source;
            this.finalReason = reason;
        }

        public record Step(Source source, String node, boolean value, String reason) {}
    }
}
