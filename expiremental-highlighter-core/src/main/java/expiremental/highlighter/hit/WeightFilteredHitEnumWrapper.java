package expiremental.highlighter.hit;

import expiremental.highlighter.HitEnum;

/**
 * Filters a HitEnum to hits that have more then a certain weight.
 */
public class WeightFilteredHitEnumWrapper extends AbstractFilteredHitEnumWrapper {
    private final float cutoff;

    public WeightFilteredHitEnumWrapper(HitEnum wrapped, float cutoff) {
        super(wrapped);
        this.cutoff = cutoff;
    }

    @Override
    protected boolean keep() {
        return wrapped().weight() > cutoff;
    }
}
