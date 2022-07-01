

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field; import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs; import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

public class Test4 {

        /**
         * @param args
         * @throws IOException
         * @throws LockObtainFailedException
         * @throws CorruptIndexException
         */
        public static void main(String[] args) throws Exception {
                Random rand = new Random(0);
                FSDirectory[] dirs = new FSDirectory[10];
                boolean build = false;
                for (int i = 0; i < dirs.length; i++) {
                        dirs[i] = FSDirectory.getDirectory("c:" + File.separator + "temp"
                                        + File.separator + "lucenetest" + File.separator
                                        + Integer.toString(i));
                        if (!IndexReader.indexExists(dirs[i])) {
                                if (!build) {
                                        System.out.println("Building Test Index Start");
                                }
                                build = true;
                                System.out.println("Building Index: " + dirs[i].getFile()
                                                + " Start");
                                IndexWriter writer = new IndexWriter(dirs[i],
                                                new StandardAnalyzer(), true);
                                for (int j = 0; j < 100000; j++) {
                                        Document doc = new Document();
                                        doc.add(new Field("i", Integer.toString(rand.nextInt(100)),
                                                        Store.YES, Index.UN_TOKENIZED));
                                        doc.add(new Field("j",
                                                        Integer.toString(rand.nextInt(1000)), Store.YES,
                                                        Index.UN_TOKENIZED));
                                        writer.addDocument(doc);
                                }
                                writer.optimize();
                                writer.close();
                                writer = null;
                                System.out.println("Building Index: " + dirs[i].getFile()
                                                + " Complete");
                        }
                        IndexReader reader = IndexReader.open(dirs[i]);
                        for (int j = 0; j < 1000; j++) {
                                reader.deleteDocument(rand.nextInt(reader.maxDoc()));
                        }
                        reader.close();
                }
                if (build) {
                        System.out.println("Building Test Index Complete");
                }
                System.out.println("Test Start");
                IndexReader[] readers = new IndexReader[dirs.length];
                for (int i = 0; i < dirs.length; i++) {
                        readers[i] = IndexReader.open(dirs[i]);
                }
                IndexReader reader = new MultiReader(readers);
                TermDocs docs = reader.termDocs();
                for (int i = 0; i < 100; i++) {
                        for (int j = 0; j < 1000; j++) {
                                try {
                                        test(reader, docs, Integer.toString(i), Integer.toString(j));
                                } catch (Exception e) {
                                        System.err.println("maxdoc=" + reader.maxDoc());
                                        System.err.println("Test Failed at i=" + i + " j=" + j);
                                        throw e;
                                }
                        }
                }
                docs.close();
                reader.close();
                System.out.println("Test Complete");
        }

        private static void test(IndexReader reader, TermDocs docs, String i,
                        String j) throws Exception {
                docs.seek(new Term("i", i));
                while (docs.next())
                        ;
                docs.seek(new Term("j", j));
                while (docs.next())
                        ;
                docs.seek(new Term("i", i));
                if (docs.next()) {
                        int doc = docs.doc();
                        try {
                                while (docs.skipTo(doc + 1000)) {
                                        doc = docs.doc();
                                }
                        } catch (Exception e) {
                                System.err.println("doc=" + (doc + 1000) + ": deleted="
                                                + reader.isDeleted(doc + 1000));
                                throw e;
                        }
                }
                docs.seek(new Term("j", j));
                if (docs.next()) {
                        int doc = docs.doc();
                        try {
                                while (docs.skipTo(doc + 1000)) {
                                        doc = docs.doc();
                                }
                        } catch (Exception e) {
                                System.err.println("doc=" + doc);
                                throw e;
                        }
                }
        }

}
