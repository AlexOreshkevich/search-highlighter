package expiremental.highlighter.lucene.hit.weight;

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;

import expiremental.highlighter.hit.TermWeigher;
import expiremental.highlighter.lucene.WrappedExceptionFromLucene;

/**
 * Weighs terms similarly to {@link DefaultSimilarity}.
 */
public class DefaultSimilarityWeighTermWeigher implements TermWeigher<BytesRef> {
    private final IndexReader reader;
    private final String fieldName;
    private final int numDocs;

    public DefaultSimilarityWeighTermWeigher(IndexReader reader, String fieldName) {
        this.reader = reader;
        this.fieldName = fieldName;
        numDocs = reader.numDocs();
    }

    @Override
    public float weigh(BytesRef term) {
        try {
            int df = reader.docFreq(new Term(fieldName, term));
            return (float) (Math.log(numDocs / (double) (df + 1)) + 1.0);
        } catch (IOException e) {
            throw new WrappedExceptionFromLucene(e);
        }
    }
}
