/**
 * 
 */
package org.apache.lucene.store;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergeScheduler;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.Version;
import org.apache.lucene.util._TestUtil;

/**
 * @author Ken McCracken
 *
 */
public class TestCrashCausesCorruptIndex extends LuceneTestCase  {

    File path;
    Version version = org.apache.lucene.util.Version.LUCENE_35;
    Analyzer analyzer = new StandardAnalyzer(version);
    
    /**
     * This test fails.
     * 
     * @throws Exception
     */
    public void testCrashCorruptsIndexing() throws Exception {
        path = _TestUtil.getTempDir("testCrashCorruptsIndexing");
        
        indexAndCrashOnCreateOutputSegments2();

        indexAfterRestart();
    }
    
    /**
     * This test passes.
     * 
     * @throws Exception
     */
    public void testCrashLeavesSearchable() throws Exception {
        path = _TestUtil.getTempDir("testCrashLeavesSearchable");
        
        indexAndCrashOnCreateOutputSegments2();

        searchForFleas(2);

    }
    
    /**
     * index 1 document and commit.
     * prepare for crashing.
     * index 1 more document, and upon commit, creation of segments_2 will crash.
     * 
     * @throws IOException
     */
    private void indexAndCrashOnCreateOutputSegments2() throws IOException {
        Directory realDirectory = null;
        CrashAfterCreateOutput crashAfterCreateOutput = null;
        IndexWriter indexWriter = null;
        try {
            LockFactory lockFactory = new SimpleFSLockFactory();
            realDirectory = FSDirectory.open(path, lockFactory);
            crashAfterCreateOutput = new CrashAfterCreateOutput(realDirectory, lockFactory);
            
            IndexWriterConfig indexWriterConfig = new IndexWriterConfig(version, analyzer);
            MergeScheduler mergeScheduler = new SerialMergeScheduler();
            indexWriterConfig.setMergeScheduler(mergeScheduler);
            indexWriter = new IndexWriter(crashAfterCreateOutput, indexWriterConfig);
            
            Document document = getDocument(0);
            indexWriter.addDocument(document);
            indexWriter.commit();
            
            crashAfterCreateOutput.setCrashAfterCreateOutput("segments_2");
            document = getDocument(1);
            indexWriter.addDocument(document);
            try {
                indexWriter.commit();
            
                fail("code will not get here");
            } catch (CrashingException e) {
                // expected
            }
        } finally {
            try {
                if (null != indexWriter) {
                    indexWriter.close();
                }
            } finally {
                try {
                    if (null != crashAfterCreateOutput) {
                        crashAfterCreateOutput.close();
                    }
                } finally {
                    try {
                        if (null != realDirectory) {
                            realDirectory.close();
                        }
                    } finally {
                        realDirectory = null;
                        crashAfterCreateOutput = null;
                        indexWriter = null;
                    }
                }
            }
        }

    }
    
    /**
     * Attempts to index another 1 document.
     * 
     * @throws IOException
     */
    private void indexAfterRestart() throws IOException {
        Directory realDirectory = null;
        IndexWriter indexWriter = null;
        try {
            LockFactory lockFactory = new SimpleFSLockFactory();
            realDirectory = FSDirectory.open(path, lockFactory);
            IndexWriterConfig indexWriterConfig = new IndexWriterConfig(version, analyzer);
            MergeScheduler mergeScheduler = new SerialMergeScheduler();
            indexWriterConfig.setMergeScheduler(mergeScheduler);
            
            // this line fails because it doesn't know what to do with the created 
            // but empty segments_2 file
            indexWriter = new IndexWriter(realDirectory, indexWriterConfig);
            
            // currently the test fails above.
            // however, to test the fix, the following lines should pass as well.
            Document document = getDocument(2);
            indexWriter.addDocument(document);
            indexWriter.commit();
        } finally {
            try {
                if (null != indexWriter) {
                    indexWriter.close();
                }
            } finally {
                try {
                    if (null != realDirectory) {
                        realDirectory.close();
                    }
                } finally {
                    realDirectory = null;
                    indexWriter = null;
                }
            }
        }
    }
    
    /**
     * Run an example search.
     * 
     * @throws IOException
     * @throws ParseException
     */
    private void searchForFleas(final int expectedTotalHits) throws IOException, ParseException {
        Directory realDirectory = null;
        IndexReader indexReader = null;
        IndexSearcher indexSearcher = null;
        try {
            LockFactory lockFactory = new SimpleFSLockFactory();
            realDirectory = FSDirectory.open(path, lockFactory);
            indexReader = IndexReader.open(realDirectory);
            indexSearcher = new IndexSearcher(indexReader);
            
            QueryParser queryParser = new QueryParser(version, TEXT_FIELD, analyzer);
            
            Query query = queryParser.parse("fleas");
            TopDocs topDocs = indexSearcher.search(query, 10);
            assertTrue("topDocs was null", null != topDocs);
            assertTrue("topDocs.totalHits expected "+expectedTotalHits+", got "+topDocs.totalHits, expectedTotalHits == topDocs.totalHits);
        } finally {
            try {
                if (null != indexSearcher) {
                    indexSearcher.close();
                }
            } finally {
                try {
                    if (null != indexReader) {
                        indexReader.close();
                    }
                } finally {
                    try {
                        if (null != realDirectory) {
                            realDirectory.close();
                        }
                    } finally {
                        realDirectory = null;
                        indexReader = null;
                        indexSearcher = null;
                    }
                }
            }
        }
    }

    private static final String TEXT_FIELD = "text";
    
    /**
     * Gets a document with content "my dog has fleas" and id based on docNumber, 
     * for the purposes of indexing.
     * 
     * @param docNumber
     * @return
     */
    private Document getDocument(int docNumber) {
        Document document = new Document();
        
        Fieldable fieldable = new Field(TEXT_FIELD, "my dog has fleas", Store.NO, Index.ANALYZED);
        document.add(fieldable);
        fieldable = new Field("id", "id"+docNumber, Store.YES, Index.NOT_ANALYZED_NO_NORMS);
        document.add(fieldable);
        
        return document;
    }
    
    /**
     * The marker RuntimeException that we use in lieu of an actual machine crash.
     * 
     * @author Ken McCracken
     */
    private static class CrashingException extends RuntimeException {
        /**
         * 
         */
        private static final long serialVersionUID = 1L;

        public CrashingException(String msg) {
            super(msg);
        }
        
    }
    
    /**
     * This test class provides direct access to "simulating" a crash right after 
     * realDirectory.createOutput(..) has been called on a certain specified name.
     * 
     * @author Ken McCracken
     */
    private static class CrashAfterCreateOutput extends Directory {
        
        private Directory realDirectory;
        private String crashAfterCreateOutput;

        public CrashAfterCreateOutput(Directory realDirectory, LockFactory lockFactory) {
            this.realDirectory = realDirectory;
            this.lockFactory = lockFactory;
        }
        
        public void setCrashAfterCreateOutput(String name) {
            this.crashAfterCreateOutput = name;
        }
        
        /**
         * {@inheritDoc}
         */
        @Override
        public void close() throws IOException {
            realDirectory.close();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public IndexOutput createOutput(String name) throws IOException {
            IndexOutput indexOutput = realDirectory.createOutput(name);
            if (null != crashAfterCreateOutput && name.equals(crashAfterCreateOutput)) {
                // CRASH!
                indexOutput.close();
                throw new CrashingException("crashAfterCreateOutput "+crashAfterCreateOutput);
            }
            return indexOutput;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void deleteFile(String name) throws IOException {
            realDirectory.deleteFile(name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean fileExists(String name) throws IOException {
            return realDirectory.fileExists(name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long fileLength(String name) throws IOException {
            return realDirectory.fileLength(name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long fileModified(String name) throws IOException {
            return realDirectory.fileModified(name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String[] listAll() throws IOException {
            return realDirectory.listAll();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public IndexInput openInput(String name) throws IOException {
            return realDirectory.openInput(name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void touchFile(String name) throws IOException {
            realDirectory.touchFile(name);
        }
        
    }
    
}
