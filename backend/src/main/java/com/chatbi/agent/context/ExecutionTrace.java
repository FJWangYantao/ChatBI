package com.chatbi.agent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExecutionTrace {

    private final List<String> entries = new ArrayList<>();

    public void add(String entry) {
        entries.add(entry);
    }

    public List<String> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
