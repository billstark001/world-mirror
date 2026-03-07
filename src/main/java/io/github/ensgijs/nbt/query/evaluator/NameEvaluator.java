package io.github.ensgijs.nbt.query.evaluator;

import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.ensgijs.nbt.tag.Tag;

public record NameEvaluator(String key) implements Evaluator {

    public Object eval(Tag<?> tag) {
        if (tag instanceof CompoundTag) {
            return ((CompoundTag) tag).get(key);
        }
        return null;
    }

    @Override
    public String toString() {
        return key;
    }
}
