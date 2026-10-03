package dev.otectus.mcaconversations.ai;

import java.util.List;

/** One titled block of structured context in the prompt; empty sections are never rendered. */
public record AiContextSection(String title, List<String> lines) {

    public AiContextSection {
        lines = lines == null ? List.of() : lines.stream().filter(l -> l != null && !l.isBlank()).toList();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
