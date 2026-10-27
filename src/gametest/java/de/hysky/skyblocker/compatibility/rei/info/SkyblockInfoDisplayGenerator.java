package de.hysky.skyblocker.compatibility.rei.info;

import dev.reisb.SkyblockerInfoFixture;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.common.entry.EntryStack;

/** Same method boundaries as upstream, so the optional compatibility mixin is exercised. */
public final class SkyblockInfoDisplayGenerator extends SkyblockerInfoFixture {
    @Override
    public Optional<List<Info>> getRecipeFor(EntryStack<?> entry) { return super.getRecipeFor(entry); }

    @Override
    public Optional<List<Info>> getUsageFor(EntryStack<?> entry) { return super.getUsageFor(entry); }
}
