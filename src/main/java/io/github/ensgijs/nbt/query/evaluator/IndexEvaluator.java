package io.github.ensgijs.nbt.query.evaluator;

import io.github.ensgijs.nbt.tag.*;

public record IndexEvaluator(int index) implements Evaluator {

    public Object eval(Tag<?> tag) {
        if (tag instanceof ListTag) {
            ListTag<?> listTag = (ListTag<?>) tag;
            return index < listTag.size() ? listTag.get(index) : null;
        } else if (tag instanceof ArrayTag) {
            if (index >= ((ArrayTag<?>) tag).length()) return null;

            if (tag instanceof ByteArrayTag) {
                return ((ByteArrayTag) tag).getValue()[index];
            }
            if (tag instanceof IntArrayTag) {
                return ((IntArrayTag) tag).getValue()[index];
            }
            if (tag instanceof LongArrayTag) {
                return ((LongArrayTag) tag).getValue()[index];
            }
        }
        if (tag == null) return null;
        throw new IllegalArgumentException("expected ListTag but was " + tag.getClass().getTypeName());
    }

    @Override
    public String toString() {
        return "[" + index + "]";
    }
}
