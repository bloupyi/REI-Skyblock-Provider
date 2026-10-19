package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.planning.CraftPlanner;
import dev.reisb.planning.PlanningService;
import dev.reisb.planning.Requirements;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class PlanningScreen extends Screen {
    public enum View { TREE, SHOPPING, REQUIREMENTS }
    private record TreeRow(CraftPlanner.Node node, int depth, String path) {}
    private record Paint(int y, String id, List<Component> tooltip, String[] columns, int color) {}
    private final Screen parent;
    private final Set<String> collapsed = new HashSet<>();
    private final List<Paint> paints = new ArrayList<>();
    private String target;
    private long quantity = 1;
    private View view;
    private boolean useSacks = true;
    private boolean useStock = true;
    private boolean showGoals;
    private int page;
    private int pages = 1;
    private int left;
    private int panelWidth;
    private int rowCount;
    private EditBox quantityInput;
    private Component error;
    private long observedRevision;
    private long repositoryRevision;
    private int ticks;
    private CraftPlanner planner;
    private CraftPlanner.Result result;

    public PlanningScreen(Screen parent, String target, View view) {
        super(tr("title"));
        this.parent = parent;
        this.target = target;
        this.view = view;
    }

    public static Component tr(String key, Object... arguments) { return Component.translatable("planning.rei_skyblock_provider." + key, arguments); }
    public View view() { return view; }
    public CraftPlanner.Result result() { return result; }

    @Override
    protected void init() {
        clearWidgets();
        paints.clear();
        quantityInput = null;
        panelWidth = Math.min(900, width - 16);
        left = (width - panelWidth) / 2;
        rowCount = Math.max(1, (height - 150) / 24);
        observedRevision = PlanningService.revision();
        repositoryRevision = ReiSkyblockProvider.revision();
        planner = new CraftPlanner(ReiSkyblockProvider.repository().source().recipes());
        for (var tab : View.values()) {
            var button = button(left + tab.ordinal() * panelWidth / 3, 30, panelWidth / 3 - 2, tr(tab.name().toLowerCase(java.util.Locale.ROOT)), () -> {
                view = tab; page = 0; error = null; rebuildWidgets();
            });
            button.active = tab != view && (tab == View.SHOPPING || !target.isEmpty());
        }
        var goals = new LinkedHashMap<String, BigDecimal>();
        if (view == View.SHOPPING) goals.putAll(PlanningService.account().goals);
        else if (!target.isEmpty()) goals.put(target, BigDecimal.valueOf(quantity));
        result = planner.plan(goals, useStock ? PlanningService.stock(useSacks) : CraftPlanner.Stock.EMPTY,
                PlanningService.account().choices, PlanningService.account().stopped);
        if (view == View.TREE) buildTree();
        else if (view == View.SHOPPING) buildShopping();
        else buildRequirements();
        button(left, height - 25, 65, tr("back"), this::onClose);
        button(left + 69, height - 25, 100, tr(useStock ? "stock_on" : "stock_off"), () -> { useStock = !useStock; rebuildWidgets(); });
        var previous = button(left + panelWidth - 100, height - 25, 27, Component.literal("<"), () -> { page--; rebuildWidgets(); });
        previous.active = page > 0;
        var next = button(left + panelWidth - 27, height - 25, 27, Component.literal(">"), () -> { page++; rebuildWidgets(); });
        next.active = page + 1 < pages;
    }

    private void buildTree() {
        quantityInput = new EditBox(font, left, 57, 65, 20, tr("quantity"));
        quantityInput.setMaxLength(7);
        quantityInput.setValue(Long.toString(quantity));
        addRenderableWidget(quantityInput);
        button(left + 69, 57, 65, tr("apply"), () -> { if (readQuantity()) rebuildWidgets(); });
        button(left + 138, 57, panelWidth - 138, tr("add_goal"), () -> {
            if (readQuantity()) {
                try { PlanningService.addGoal(target, quantity); view = View.SHOPPING; showGoals = true; page = 0; rebuildWidgets(); }
                catch (IllegalArgumentException failure) { error = tr("invalid_goal"); }
            }
        });
        var rows = new ArrayList<TreeRow>();
        for (int i = 0; i < result.roots().size(); i++) flatten(result.roots().get(i), 0, Integer.toString(i), rows);
        setPages(rows.size());
        for (int i = page * rowCount; i < Math.min(rows.size(), (page + 1) * rowCount); i++) {
            var row = rows.get(i);
            var node = row.node;
            int y = 94 + (i % rowCount) * 24;
            int indent = Math.min(36, row.depth * 8);
            if (!node.children().isEmpty()) button(left, y, 18, Component.literal(collapsed.contains(row.path) ? "+" : "-"), () -> {
                if (!collapsed.remove(row.path)) collapsed.add(row.path);
                rebuildWidgets();
            });
            var detail = nodeTooltip(node);
            button(left + 40 + indent, y, Math.max(24, panelWidth - 212 - indent),
                    Component.literal(number(node.needed()) + " × " + name(node.id())), () -> recipes(node.id())).setTooltip(Tooltip.create(join(detail)));
            var methods = planner.methods(node.id());
            int selected = PlanningService.account().choices.getOrDefault(node.id(), node.recipe() == null ? -1 : 0);
            var method = button(left + panelWidth - 168, y, 44, Component.literal(methods.isEmpty() ? "-" : (selected < 0 ? "1" : selected + 1) + "/" + methods.size()), () -> {
                PlanningService.choose(node.id(), (selected + 1) % methods.size()); rebuildWidgets();
            });
            method.active = !methods.isEmpty();
            method.setTooltip(Tooltip.create(tr("method", node.recipe() == null ? tr("source").getString() : node.recipe().kind().name())));
            button(left + panelWidth - 122, y, 42, tr(PlanningService.account().stopped.contains(node.id()) ? "expand" : "buy"), () -> {
                PlanningService.toggleStop(node.id()); rebuildWidgets();
            });
            var reserve = button(left + panelWidth - 78, y, 36, Component.literal(PlanningService.account().excluded.contains(node.id()) ? "R*" : "R"), () -> {
                PlanningService.toggleReserve(node.id()); rebuildWidgets();
            });
            reserve.setTooltip(Tooltip.create(tr("reserve")));
            button(left + panelWidth - 40, y, 40, tr("req_short"), () -> minecraft.gui.setScreen(new PlanningScreen(this, node.id(), View.REQUIREMENTS)));
            paints.add(new Paint(y, node.id(), detail, null, 0xffdedede));
        }
    }

    private void buildShopping() {
        button(left, 57, 95, tr(showGoals ? "materials" : "goals"), () -> { showGoals = !showGoals; page = 0; rebuildWidgets(); });
        button(left + 99, 57, panelWidth - 164, tr(useSacks ? "sacks_on" : "sacks_off"), () -> { useSacks = !useSacks; rebuildWidgets(); });
        button(left + panelWidth - 61, 57, 61, tr("refresh"), this::rebuildWidgets);
        if (showGoals) {
            var goals = new ArrayList<>(PlanningService.account().goals.entrySet());
            setPages(goals.size());
            for (int i = page * rowCount; i < Math.min(goals.size(), (page + 1) * rowCount); i++) {
                var goal = goals.get(i);
                int y = 94 + i % rowCount * 24;
                button(left + 20, y, panelWidth - 86, Component.literal(number(goal.getValue()) + " × " + name(goal.getKey())), () -> {
                    target = goal.getKey(); quantity = goal.getValue().longValueExact(); view = View.TREE; page = 0; rebuildWidgets();
                });
                button(left + panelWidth - 62, y, 62, tr("remove"), () -> { PlanningService.removeGoal(goal.getKey()); rebuildWidgets(); });
                paints.add(new Paint(y, goal.getKey(), List.of(), null, 0xffdedede));
            }
        } else {
            var materials = result.materials();
            setPages(materials.size());
            for (int i = page * rowCount; i < Math.min(materials.size(), (page + 1) * rowCount); i++) {
                var material = materials.get(i);
                int y = 94 + i % rowCount * 24;
                var observation = PlanningService.sackObservations().get(material.id());
                long observedAge = observation == null ? Long.MAX_VALUE : System.currentTimeMillis() - observation.time();
                boolean known = observation != null && observedAge >= -60_000 && observedAge <= 86_400_000;
                var tooltip = new ArrayList<Component>();
                tooltip.add(Component.literal(name(material.id())));
                tooltip.add(tr("totals", number(material.needed()), number(material.inventory()), known && useSacks ? number(material.sacks()) : "?", number(material.missing())));
                if (observation != null) tooltip.add(tr("observed", number(observation.amount()), age(observation.time())));
                else tooltip.add(tr("sack_unknown"));
                tooltip.add(tr("reserve"));
                button(left + 20, y, Math.max(40, panelWidth * 35 / 100 - 24), Component.literal(name(material.id())), () -> recipes(material.id())).setTooltip(Tooltip.create(join(tooltip)));
                var reserve = button(left + panelWidth - 34, y, 34, Component.literal(PlanningService.account().excluded.contains(material.id()) ? "R*" : "R"), () -> {
                    PlanningService.toggleReserve(material.id()); rebuildWidgets();
                });
                reserve.setTooltip(Tooltip.create(tr("reserve")));
                paints.add(new Paint(y, material.id(), tooltip, new String[]{number(material.needed()), number(material.inventory()),
                        useSacks && known ? number(material.sacks()) : "?", number(material.missing())}, material.missing().signum() == 0 ? 0xff8ce39f : 0xffffcf81));
            }
        }
    }

    private void buildRequirements() {
        var item = ReiSkyblockProvider.repository().source().items().get(target);
        var methods = planner.methods(target);
        int selected = PlanningService.account().choices.getOrDefault(target, 0);
        var selectedRecipes = methods.isEmpty() ? List.<dev.reisb.data.SkyblockRecipe>of() : List.of(methods.get(Math.max(0, Math.min(selected, methods.size() - 1))));
        var conditions = Requirements.read(item, selectedRecipes);
        setPages(conditions.size());
        for (int i = page * rowCount; i < Math.min(conditions.size(), (page + 1) * rowCount); i++) {
            var condition = conditions.get(i);
            var state = condition.status(PlanningService.progress(), System.currentTimeMillis());
            int color = switch (state) { case MET -> 0xff8ce39f; case UNMET -> 0xffff8e8e; case UNKNOWN -> 0xffffcf81; };
            var detail = new ArrayList<Component>();
            detail.add(tr("scope_" + condition.scope()));
            detail.add(Component.literal(condition.label()));
            detail.add(Component.literal(condition.source()));
            var progress = PlanningService.progress().get(condition.key());
            if (progress != null) detail.add(tr("observed", number(progress.amount()), age(progress.time())));
            else detail.add(tr("progress_unknown"));
            paints.add(new Paint(94 + i % rowCount * 24, "", detail,
                    new String[]{tr("scope_" + condition.scope()).getString() + ": " + condition.label(), tr("status_" + state.name().toLowerCase(java.util.Locale.ROOT)).getString()}, color));
        }
        button(left, 57, panelWidth, Component.literal(name(target)), () -> recipes(target));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left - 4, 5, left + panelWidth + 4, height - 31, 0xd9121b26);
        graphics.centeredText(font, tr("title"), width / 2, 12, 0xfff4e9d0);
        if (view == View.SHOPPING && !showGoals) {
            String[] headers = {tr("needed").getString(), tr("inventory").getString(), tr("sacks").getString(), tr("missing").getString()};
            for (int i = 0; i < 4; i++) graphics.text(font, fit(headers[i], columnWidth() - 2), columnX(i), 82, 0xffaebdcc);
        }
        if (paints.isEmpty()) graphics.centeredText(font, tr(view == View.REQUIREMENTS ? "no_requirements" : "empty"), width / 2, 104, 0xffaebdcc);
        for (var paint : paints) {
            if (!paint.id.isEmpty()) {
                var stack = ReiSkyblockProvider.repository().stacks().get(paint.id);
                if (stack != null) graphics.fakeItem(stack, left + (view == View.TREE ? 22 : 1), paint.y + 2);
            }
            if (paint.columns != null) {
                if (view == View.REQUIREMENTS) {
                    graphics.fill(left, paint.y, left + panelWidth, paint.y + 22, 0xff1d2a39);
                    graphics.text(font, fit(paint.columns[0], panelWidth - 83), left + 5, paint.y + 6, 0xffdedede);
                    graphics.text(font, fit(paint.columns[1], 75), left + panelWidth - 78, paint.y + 6, paint.color);
                } else for (int i = 0; i < paint.columns.length; i++) graphics.text(font, fit(paint.columns[i], columnWidth() - 2), columnX(i), paint.y + 6, paint.color);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (var paint : paints) if (!paint.tooltip.isEmpty() && paint.columns != null && mouseX >= (view == View.REQUIREMENTS ? left : columnX(0))
                && mouseX <= left + panelWidth - (view == View.REQUIREMENTS ? 0 : 36) && mouseY >= paint.y && mouseY < paint.y + 22) {
            graphics.setTooltipForNextFrame(font, paint.tooltip.stream().flatMap(line -> font.split(line, Math.min(300, width - 24)).stream()).toList(), mouseX, mouseY);
        }
        Component status = error != null ? error : !PlanningService.persistenceHealthy() ? tr("cache_disabled")
                : Component.literal("API: ").append(tr("api_" + dev.reisb.planning.ProfileApiSync.status())).append(Component.literal(" | "))
                        .append(tr("cache", PlanningService.profileName().isEmpty() ? "?" : PlanningService.profileName(), cacheAge(), PlanningService.sackObservations().size(), PlanningService.progress().size()));
        graphics.text(font, fit(status.getString(), panelWidth), left, height - 46, error != null ? 0xffff8e8e : 0xffaebdcc);
        graphics.text(font, (page + 1) + "/" + pages, left + panelWidth - 68, height - 19, 0xffdedede);
        if (mouseX >= left && mouseX <= left + panelWidth && mouseY >= height - 48 && mouseY <= height - 35)
            graphics.setTooltipForNextFrame(font, font.split(status, Math.min(300, width - 24)), mouseX, mouseY);
        if (!result.warnings().isEmpty()) graphics.text(font, tr("warnings", result.warnings().size()), left, height - 60, 0xffffcf81);
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0 && (quantityInput == null || !quantityInput.isFocused())
                && (observedRevision != PlanningService.revision() || repositoryRevision != ReiSkyblockProvider.revision())) rebuildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    private void flatten(CraftPlanner.Node node, int depth, String path, List<TreeRow> rows) {
        rows.add(new TreeRow(node, depth, path));
        if (!collapsed.contains(path)) for (int i = 0; i < node.children().size(); i++) flatten(node.children().get(i), depth + 1, path + "." + i, rows);
    }

    private List<Component> nodeTooltip(CraftPlanner.Node node) {
        var lines = new ArrayList<Component>();
        lines.add(Component.literal(name(node.id())));
        lines.add(tr("totals", number(node.needed()), number(node.inventory()), number(node.sacks()), number(node.needed().subtract(node.inventory()).subtract(node.sacks()).subtract(node.reused()))));
        lines.add(tr("reason_" + node.reason()));
        if (node.recipe() != null) lines.add(tr("batches", number(node.batches()), number(node.recipe().output().amount()), number(node.surplus())));
        if (node.reused().signum() > 0) lines.add(tr("reused", number(node.reused())));
        var item = ReiSkyblockProvider.repository().source().items().get(node.id());
        for (var requirement : Requirements.read(item, node.recipe() == null ? List.of() : List.of(node.recipe()))) {
            var state = requirement.status(PlanningService.progress(), System.currentTimeMillis());
            lines.add(Component.literal(tr("status_" + state.name().toLowerCase(java.util.Locale.ROOT)).getString() + " | " + tr("scope_" + requirement.scope()).getString() + ": " + requirement.label()));
        }
        return lines;
    }

    private boolean readQuantity() {
        try {
            long value = Long.parseLong(quantityInput.getValue());
            if (value < 1 || value > 1_000_000) throw new IllegalArgumentException();
            quantity = value; error = null; return true;
        } catch (IllegalArgumentException failure) { error = tr("invalid_goal"); return false; }
    }

    private void recipes(String id) {
        var stack = ReiSkyblockProvider.repository().stacks().get(id);
        if (stack != null) me.shedaniel.rei.api.client.view.ViewSearchBuilder.builder().addRecipesFor(me.shedaniel.rei.api.common.util.EntryStacks.of(stack)).open();
    }

    private Button button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(label, button -> action.run()).bounds(x, y, width, 20).build());
    }
    private void setPages(int size) { pages = Math.max(1, (size + rowCount - 1) / rowCount); page = Math.max(0, Math.min(page, pages - 1)); }
    private int columnWidth() { return Math.max(20, (panelWidth * 65 / 100 - 38) / 4); }
    private int columnX(int index) { return left + panelWidth * 35 / 100 + index * columnWidth(); }
    private String fit(String text, int width) { return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "..."; }
    private static Component join(List<Component> lines) { var text = Component.empty(); for (var line : lines) { if (!text.getString().isEmpty()) text.append("\n"); text.append(line); } return text; }
    private static String name(String id) { if (id.equals("SKYBLOCK_COIN")) return tr("coins").getString(); var item = ReiSkyblockProvider.repository().source().items().get(id); return item == null ? id : dev.reisb.planning.ObservedMenus.clean(item.name()); }
    private static String number(BigDecimal value) { return dev.reisb.rei.DisplayText.quantity(value); }
    private static String age(long time) { long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000); return minutes < 60 ? minutes + " min" : minutes / 60 + " h"; }
    private static String cacheAge() { return PlanningService.sackObservations().values().stream().mapToLong(value -> value.time()).min().stream().mapToObj(PlanningScreen::age).findFirst().orElse("?"); }
}
