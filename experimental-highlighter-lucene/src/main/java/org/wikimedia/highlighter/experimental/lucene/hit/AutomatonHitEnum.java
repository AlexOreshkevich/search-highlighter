package org.wikimedia.highlighter.experimental.lucene.hit;

import org.apache.lucene.util.automaton.OffsetReturningRunAutomaton;
import org.apache.lucene.util.automaton.XAutomaton;
import org.wikimedia.search.highlighter.experimental.HitEnum;
import org.wikimedia.search.highlighter.experimental.hit.HitWeigher;
import org.wikimedia.search.highlighter.experimental.hit.weight.ConstantHitWeigher;

/**
 * HitEnum implementation that slides a Lucene automaton across the source,
 * matching whatever matches. Does not support overlapping matches.
 */
public class AutomatonHitEnum implements HitEnum {
    public static Factory factory(XAutomaton automaton) {
        return new Factory(automaton);
    }

    public static class Factory {
        private OffsetReturningRunAutomaton run;

        private Factory(XAutomaton automaton) {
            run = new OffsetReturningRunAutomaton(automaton, false);
        }

        /**
         * Build the HitEnum so all hits have equal weight.
         */
        public AutomatonHitEnum build(String source) {
            return build(source, ConstantHitWeigher.ONE, ConstantHitWeigher.ONE);
        }

        public AutomatonHitEnum build(String source, HitWeigher queryWeigher,
                HitWeigher corpusWeigher) {
            return new AutomatonHitEnum(run, source, queryWeigher, corpusWeigher);
        }
    }

    private final OffsetReturningRunAutomaton runAutomaton;
    private final String source;
    private final HitWeigher queryWeigher;
    private final HitWeigher corpusWeigher;
    private final int length;
    private int start;
    private int end;
    private float queryWeight;
    private float corpusWeight;
    private int position = -1;

    public AutomatonHitEnum(OffsetReturningRunAutomaton runAutomaton, String source,
            HitWeigher queryWeigher, HitWeigher corpusWeigher) {
        this.runAutomaton = runAutomaton;
        this.source = source;
        this.length = source.length();
        this.queryWeigher = queryWeigher;
        this.corpusWeigher = corpusWeigher;
    }

    @Override
    public boolean next() {
        // Start looking where the last hit stopped
        start = end;

        // Look until there aren't any more characters
        while (start < length) {
            end = runAutomaton.run(source, start, length);
            if (end >= 0) {
                // Found a match!
                position++;
                queryWeight = queryWeigher.weight(position, start, end);
                corpusWeight = corpusWeigher.weight(position, start, end);
                return true;
            }
            // No match, push start and keep checking
            start++;
        }

        // No matches at all, set end to length so we never check again
        end = length;
        return false;
    }

    @Override
    public int position() {
        return position;
    }

    @Override
    public int startOffset() {
        return start;
    }

    @Override
    public int endOffset() {
        return end;
    }

    @Override
    public float queryWeight() {
        return queryWeight;
    }

    @Override
    public float corpusWeight() {
        return corpusWeight;
    }

    @Override
    public int source() {
        // We punt here and hope someone will override this behavior
        // because we really can't trace the hit to a useful source.
        return 0;
    }

    @Override
    public String toString() {
        return runAutomaton.toString();
    }
}
