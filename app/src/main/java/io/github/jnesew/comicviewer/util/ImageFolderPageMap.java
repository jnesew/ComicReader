package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolve positions by provider document identity, never by a changing display filename. */
public final class ImageFolderPageMap {
    private final List<PageInfo> previous;
    private final Map<String, Integer> positions = new HashMap<>();
    public ImageFolderPageMap(List<PageInfo> previous, List<PageInfo> current) {
        this.previous = previous;
        for (int page = 0; page < current.size(); page++) positions.put(current.get(page).name, page);
    }
    public int remap(int page) {
        if (page < 0 || page >= previous.size()) return -1;
        return positions.getOrDefault(previous.get(page).name, -1);
    }
}
