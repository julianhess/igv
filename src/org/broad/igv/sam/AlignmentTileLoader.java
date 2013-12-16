/*
 * Copyright (c) 2007-2012 The Broad Institute, Inc.
 * SOFTWARE COPYRIGHT NOTICE
 * This software and its documentation are the copyright of the Broad Institute, Inc. All rights are reserved.
 *
 * This software is supplied without any warranty or guaranteed support whatsoever. The Broad Institute is not responsible for its use, misuse, or functionality.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 */

package org.broad.igv.sam;


import net.sf.samtools.util.CloseableIterator;
import org.apache.log4j.Logger;
import org.broad.igv.Globals;
import org.broad.igv.PreferenceManager;
import org.broad.igv.feature.SpliceJunctionFeature;
import org.broad.igv.sam.reader.AlignmentReader;
import org.broad.igv.sam.reader.ReadGroupFilter;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.ui.util.ProgressMonitor;
import org.broad.igv.util.ObjectCache;
import org.broad.igv.util.RuntimeUtils;
import org.broad.igv.util.collections.LRUCache;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * A wrapper for an AlignmentQueryReader that caches query results
 *
 * @author jrobinso
 */
public class AlignmentTileLoader {

    private static Logger log = Logger.getLogger(AlignmentTileLoader.class);

    private static Set<WeakReference<AlignmentTileLoader>> activeLoaders = Collections.synchronizedSet(new HashSet());

    /**
     * Flag to mark a corrupt index.  Without this attempted reads will continue in an infinite loop
     */
    private boolean corruptIndex = false;

    private AlignmentReader reader;
    private boolean cancel = false;
    private boolean pairedEnd = false;

    static void cancelReaders() {
        for (WeakReference<AlignmentTileLoader> readerRef : activeLoaders) {
            AlignmentTileLoader reader = readerRef.get();
            if (reader != null) {
                reader.cancel = true;
            }
        }
        log.debug("Readers canceled");
        activeLoaders.clear();
    }


    public AlignmentTileLoader(AlignmentReader reader) {
        this.reader = reader;
        activeLoaders.add(new WeakReference<AlignmentTileLoader>(this));
    }

    public void close() throws IOException {
        reader.close();
    }

    public List<String> getSequenceNames() {
        return reader.getSequenceNames();
    }

    public CloseableIterator<Alignment> iterator() {
        return reader.iterator();
    }

    public boolean hasIndex() {
        return reader.hasIndex();
    }


    AlignmentTile loadTile(String chr, int start, int end,
                           SpliceJunctionHelper spliceJunctionHelper,
                           AlignmentDataManager.DownsampleOptions downsampleOptions,
                           Map<String, PEStats> peStats,
                           AlignmentTrack.BisulfiteContext bisulfiteContext,
                           ProgressMonitor monitor) {

        AlignmentTile t = new AlignmentTile(start, end, spliceJunctionHelper, downsampleOptions, bisulfiteContext);


        //assert (tiles.size() > 0);
        if (corruptIndex) {
            return t;
        }

        final PreferenceManager prefMgr = PreferenceManager.getInstance();
        boolean filterFailedReads = prefMgr.getAsBoolean(PreferenceManager.SAM_FILTER_FAILED_READS);
        boolean filterSecondaryAlignments = prefMgr.getAsBoolean(PreferenceManager.SAM_FILTER_SECONDARY_ALIGNMENTS);
        ReadGroupFilter filter = ReadGroupFilter.getFilter();
        boolean showDuplicates = prefMgr.getAsBoolean(PreferenceManager.SAM_SHOW_DUPLICATES);
        int qualityThreshold = prefMgr.getAsInt(PreferenceManager.SAM_QUALITY_THRESHOLD);

        CloseableIterator<Alignment> iter = null;

        //log.debug("Loading : " + start + " - " + end);
        int alignmentCount = 0;
        WeakReference<AlignmentTileLoader> ref = new WeakReference(this);
        try {
            ObjectCache<String, Alignment> mappedMates = new ObjectCache<String, Alignment>(1000);
            ObjectCache<String, Alignment> unmappedMates = new ObjectCache<String, Alignment>(1000);


            activeLoaders.add(ref);
            iter = reader.query(chr, start, end, false);

            while (iter != null && iter.hasNext()) {

                if (cancel) {
                    return t;
                }

                Alignment record = iter.next();

                // Set mate sequence of unmapped mates
                // Put a limit on the total size of this collection.
                String readName = record.getReadName();
                if (record.isPaired()) {
                    pairedEnd = true;
                    if (record.isMapped()) {
                        if (!record.getMate().isMapped()) {
                            // record is mapped, mate is not
                            Alignment mate = unmappedMates.get(readName);
                            if (mate == null) {
                                mappedMates.put(readName, record);
                            } else {
                                record.setMateSequence(mate.getReadSequence());
                                unmappedMates.remove(readName);
                                mappedMates.remove(readName);
                            }

                        }
                    } else if (record.getMate().isMapped()) {
                        // record not mapped, mate is
                        Alignment mappedMate = mappedMates.get(readName);
                        if (mappedMate == null) {
                            unmappedMates.put(readName, record);
                        } else {
                            mappedMate.setMateSequence(record.getReadSequence());
                            unmappedMates.remove(readName);
                            mappedMates.remove(readName);
                        }
                    }
                }


                if (!record.isMapped() || (!showDuplicates && record.isDuplicate()) ||
                        (filterFailedReads && record.isVendorFailedRead()) ||
                        (filterSecondaryAlignments && !record.isPrimary()) ||
                        record.getMappingQuality() < qualityThreshold ||
                        (filter != null && filter.filterAlignment(record))) {
                    continue;
                }

                t.addRecord(record);

                alignmentCount++;
                int interval = Globals.isTesting() ? 100000 : 1000;
                if (alignmentCount % interval == 0) {
                    if (cancel) return null;
                    String msg = "Reads loaded: " + alignmentCount;
                    MessageUtils.setStatusBarMessage(msg);
                    if(monitor != null){
                        monitor.updateStatus(msg);
                    }
                    if (memoryTooLow()) {
                        if(monitor != null) monitor.fireProgressChange(100);
                        cancelReaders();
                        return t;        // <=  TODO need to cancel all readers
                    }
                }

                // Update pe stats
                if (peStats != null && record.isPaired() && record.isProperPair()) {
                    String lb = record.getLibrary();
                    if (lb == null) lb = "null";
                    PEStats stats = peStats.get(lb);
                    if (stats == null) {
                        stats = new PEStats(lb);
                        peStats.put(lb, stats);
                    }
                    stats.update(record);

                }
            }
            // End iteration over alignments

            // Compute peStats
            if (peStats != null) {
                // TODO -- something smarter re the percentiles.  For small samples these will revert to min and max
                double minPercentile = prefMgr.getAsFloat(PreferenceManager.SAM_MIN_INSERT_SIZE_PERCENTILE);
                double maxPercentile = prefMgr.getAsFloat(PreferenceManager.SAM_MAX_INSERT_SIZE_PERCENTILE);
                for (PEStats stats : peStats.values()) {
                    stats.compute(minPercentile, maxPercentile);
                }
            }

            // Clean up any remaining unmapped mate sequences
            for (String mappedMateName : mappedMates.getKeys()) {
                Alignment mappedMate = mappedMates.get(mappedMateName);
                Alignment mate = unmappedMates.get(mappedMate.getReadName());
                if (mate != null) {
                    mappedMate.setMateSequence(mate.getReadSequence());
                }
            }
            t.setLoaded(true);

            return t;

        } catch (java.nio.BufferUnderflowException e) {
            // This almost always indicates a corrupt BAM index, or less frequently a corrupt bam file
            corruptIndex = true;
            MessageUtils.showMessage("<html>Error encountered querying alignments: " + e.toString() +
                    "<br>This is often caused by a corrupt index file.");
            return null;

        } catch (Exception e) {
            log.error("Error loading alignment data", e);
            MessageUtils.showMessage("<html>Error encountered querying alignments: " + e.toString());
            return null;
        } finally {
            // reset cancel flag.  It doesn't matter how we got here,  the read is complete and this flag is reset
            // for the next time
            cancel = false;
            activeLoaders.remove(ref);

            if(monitor != null){
                monitor.fireProgressChange(100);
            }

            if (iter != null) {
                iter.close();
            }
            if (!Globals.isHeadless()) {
                IGV.getInstance().resetStatusMessage();
            }
        }
    }


    private static synchronized boolean memoryTooLow() {
        if (RuntimeUtils.getAvailableMemoryFraction() < 0.2) {
            LRUCache.clearCaches();
            System.gc();
            if (RuntimeUtils.getAvailableMemoryFraction() < 0.2) {
                String msg = "Memory is low, reading terminating.";
                MessageUtils.showMessage(msg);
                return true;
            }

        }
        return false;
    }


    /**
     * Does this file contain paired end data?  Assume not until proven otherwise.
     */
    public boolean isPairedEnd() {
        return pairedEnd;
    }

    public Set<String> getPlatforms() {
        return reader.getPlatforms();
    }

    /**
     * Caches alignments, coverage, splice junctions, and downsampled intervals
     */

    public static class AlignmentTile {

        private boolean loaded = false;
        private int end;
        private int start;
        private AlignmentCounts counts;
        private List<Alignment> alignments;
        private List<DownsampledInterval> downsampledIntervals;
        private SpliceJunctionHelper spliceJunctionHelper;
        private boolean isPairedEnd;

        private boolean downsample;
        private int samplingWindowSize;
        private int samplingDepth;

        //private SamplingBucket currentSamplingBucket;
        private int currentSamplingWindowStart = -1;
        private int curEffSamplingWindowDepth = 0;

        /**
         * We keep a data structure of alignments which can efficiently
         * look up by string (read name) or index (for random replacement)
         */
        IndexableMap<String, Alignment> imAlignments;

        private static final Random RAND = new Random();

        /**
         * To make sure we downsample pairs together, we keep track of when an alignment
         * is kept or not
         */
        //private Map<String, Boolean> readKeptAfterDownsampling = new HashMap<String, Boolean>();

        private int downsampledCount = 0;
        private int offset = 0;


        AlignmentTile(int start, int end,
                      SpliceJunctionHelper spliceJunctionHelper,
                      AlignmentDataManager.DownsampleOptions downsampleOptions,
                      AlignmentTrack.BisulfiteContext bisulfiteContext) {
            this.start = start;
            this.end = end;
            alignments = new ArrayList(16000);
            downsampledIntervals = new ArrayList();
            long seed = System.currentTimeMillis();
            //System.out.println("seed: " + seed);
            RAND.setSeed(seed);

            // Use a sparse array for large regions  (> 10 mb)
            if ((end - start) > 10000000) {
                this.counts = new SparseAlignmentCounts(start, end, bisulfiteContext);
            } else {
                this.counts = new DenseAlignmentCounts(start, end, bisulfiteContext);
            }

            // Set the max depth, and the max depth of the sampling bucket.
            if (downsampleOptions == null) {
                // Use default settings (from preferences)
                downsampleOptions = new AlignmentDataManager.DownsampleOptions();
            }
            this.downsample = downsampleOptions.isDownsample();
            this.samplingWindowSize = downsampleOptions.getSampleWindowSize();
            this.samplingDepth = Math.max(1, downsampleOptions.getMaxReadCount());

            this.spliceJunctionHelper = spliceJunctionHelper;

            if(this.downsample){
                imAlignments = new IndexableMap<String, Alignment>(8000);
            }
        }

        public int getStart() {
            return start;
        }

        public void setStart(int start) {
            this.start = start;
        }

        int ignoredCount = 0;    // <= just for debugging

        /**
         * Add an alignment record to this tile.  This record is not necessarily retained after down-sampling.
         *
         * @param alignment
         */
        public void addRecord(Alignment alignment) {

            counts.incCounts(alignment);
            isPairedEnd |= alignment.isPaired();

            if (spliceJunctionHelper != null) {
                spliceJunctionHelper.addAlignment(alignment);
            }

            if (downsample) {
                //TODO
                downsampledIntervals = new ArrayList<DownsampledInterval>();
                final int alignmentStart = alignment.getAlignmentStart();
                int currentSamplingBucketEnd = currentSamplingWindowStart + samplingWindowSize;
                if (currentSamplingWindowStart < 0 || alignmentStart >= currentSamplingBucketEnd) {
                    curEffSamplingWindowDepth = 0;
                    downsampledCount = 0;
                    currentSamplingWindowStart = alignmentStart;
                    offset = imAlignments.size();
                }

                String readName = alignment.getReadName();
                //There are 3 possibilities: mate-kept, mate-rejected, mate-unknown (haven't seen, or non-paired reads)
                //If we kept or rejected the mate, we do the same for this one
                boolean hasRead = imAlignments.containsKey(readName);
                if(hasRead){
                    List<Alignment> mateAlignments = imAlignments.get(readName);
                    boolean haveMate = false;
                    if(mateAlignments != null){
                        for(Alignment al: mateAlignments){
                            ReadMate mate = al.getMate();
                            haveMate |= mate.getChr().equals(alignment.getChr()) && mate.getStart() == alignment.getStart();
                        }
                    }
                    if(haveMate){
                        //We keep the alignment if it's mate is kept
                        imAlignments.append(readName, alignment);
                    }else{
                        //pass
                    }
                }else{
                    if (curEffSamplingWindowDepth < samplingDepth) {
                        imAlignments.append(readName, alignment);
                        curEffSamplingWindowDepth++;
                    } else {
                        double samplingProb = ((double) samplingDepth) / (samplingDepth + downsampledCount + 1);
                        if (RAND.nextDouble() < samplingProb) {
                            int rndInt = (int) (RAND.nextDouble() * (samplingDepth - 1));
                            int idx = offset + rndInt;
                            // Replace random record with this one
                            imAlignments.replace(idx, readName, alignment);
                        }else{
                            //Mark that record was not kept
                            imAlignments.markNull(readName);
                        }
                        downsampledCount++;
                    }
                }

            } else {
                alignments.add(alignment);
            }

            alignment.finish();
        }

//        private void emptyBucket() {
//            if (currentSamplingBucket == null) {
//                return;
//            }
//            //List<Alignment> sampledRecords = sampleCurrentBucket();
//            for (Alignment alignment : currentSamplingBucket.getAlignments()) {
//                alignments.add(alignment);
//
//            }
//
//            if (currentSamplingBucket.isSampled()) {
//                DownsampledInterval interval = new DownsampledInterval(currentSamplingBucket.start,
//                        currentSamplingBucket.end, currentSamplingBucket.downsampledCount);
//                downsampledIntervals.add(interval);
//            }
//
//            currentSamplingBucket = null;
//
//        }

//        /**
//         * Add readName to the map of kept readNames, or delete
//         * it out of that map if the status is already marked (since we only ever see 2, after the 2nd we don't care)
//         * @param readName
//         * @param status
//         */
//        private void markReadKept(String readName, boolean status) {
//            if(!isPairedEnd){
//                return;
//            }
//            if(readKeptAfterDownsampling.containsKey(readName)){
//                readKeptAfterDownsampling.remove(readName);
//            }else{
//                readKeptAfterDownsampling.put(readName, status);
//            }
//        }

        public List<Alignment> getAlignments() {
            return alignments;
        }

        public List<DownsampledInterval> getDownsampledIntervals() {
            return downsampledIntervals;
        }

        public boolean isLoaded() {
            return loaded;
        }

        public void setLoaded(boolean loaded) {
            this.loaded = loaded;

            if (loaded) {
                //emptyBucket();
                //If we downsampled,  we need to sort
                if(downsample){
                    this.alignments = imAlignments.getAllValues();

                    Comparator<Alignment> alignmentSorter = new Comparator<Alignment>() {
                        public int compare(Alignment alignment, Alignment alignment1) {
                            return alignment.getStart() - alignment1.getStart();
                        }
                    };
                    Collections.sort(this.alignments, alignmentSorter);
                }
                finalizeSpliceJunctions();
                counts.finish();
            }
        }

        public AlignmentCounts getCounts() {
            return counts;
        }


        private void finalizeSpliceJunctions() {
            if (spliceJunctionHelper != null) {
                spliceJunctionHelper.finish();
            }
        }

        public List<SpliceJunctionFeature> getSpliceJunctionFeatures() {
            if(spliceJunctionHelper == null) return null;
            return spliceJunctionHelper.getFilteredJunctions();
        }

        public SpliceJunctionHelper getSpliceJunctionHelper() {
            return spliceJunctionHelper;
        }

        /**
         * Map-like structure designed to be accessible both by key, and by numeric index
         * Multiple values are stored for each key, and a list is returned
         * If the key for a value is set as null, nothing can be added
         *
         * Intended to support downsampling, where if a read name is added and then removed
         * we don't want to add the read pair
         * @param <K>
         * @param <V>
         */
        private class IndexableMap<K, V>{
            private HashMap<K, List<V>> map;
            private List<K> list;

            IndexableMap(int size){
                this.map = new HashMap<K, List<V>>(size);
                this.list = new ArrayList<K>(size);
            }

            public List<V> get(K key){
                return map.get(key);
            }

//
//            public V get(int index){
//                checkSize(index);
//                return map.get(list.get(index));
//            }

            /**
             * Append a value for the specified key, unless
             * the current value is null. If the current value is
             * null, it's a no-op.
             * @param key
             * @param value
             * @return Whether the element was added
             */
            public boolean append(K key, V value){
                if(!map.containsKey(key)){
                    addNewValueToMap(key, value);
                    return list.add(key);
                }else{
                    List<V> curList = map.get(key);
                    if(curList == null) return false;
                    return curList.add(value);
                }
            }

            public void markNull(K key){
                map.put(key, null);
            }

            private void addNewValueToMap(K key, V value){
                List<V> curList = new ArrayList<V>(1);
                curList.add(value);
                map.put(key, curList);
            }

            /**
             * Place the specified {@code key} and {@code value} in the map,
             * at index {@code index}.
             *
             * In the unlikely event that {@code key} is already
             * at {@code index}, {@code value} will be appended
             * @param index
             * @param key
             * @param value
             * @return Whether the replacement actually happened
             */
            public void replace(int index, K key, V value){
                checkSize(index);
                K oldKey = list.get(index);
                if(!oldKey.equals(key)){
                    //Remove the old key from map, and make sure nothing else gets put there
                    markNull(oldKey);
                    addNewValueToMap(key, value);
                    list.set(index, key);
                }else{
                    append(key, value);
                }
            }

            public int size(){
                return list.size();
            }

            private void checkSize(int index){
                if(index >= size()){
                    throw new IllegalArgumentException("index " + index + " greater than current size" + size());
                }
            }

            public List<V> getAllValues() {
                List<V> allValues = new ArrayList<V>(2*size());
                for(K k: list){
                    allValues.addAll(map.get(k));
                }
                return allValues;
            }

            public boolean containsKey(K key) {
                return map.containsKey(key);
            }
        }


        private class SamplingBucket {
            int start;
            int end;
            int downsampledCount = 0;
            //List<Alignment> alignments;
            IndexableMap<String, Alignment> imAlignments;

            private SamplingBucket(int start, int end) {
                this.start = start;
                this.end = end;
                alignments = new ArrayList<Alignment>(samplingDepth);
                //imAlignments = new IndexableMap<String, Alignment>(samplingDepth);
            }


            public void add(Alignment alignment) {
                // If the current bucket is < max depth we keep it.  Otherwise,  keep with probability == samplingProb
                if (imAlignments.size() < samplingDepth) {
                    //alignments.add(alignment);
                    //imAlignments.append(alignment.getReadName(), alignment);
                } else {
                    double samplingProb = ((double) samplingDepth) / (samplingDepth + downsampledCount + 1);
                    if (RAND.nextDouble() < samplingProb) {
                        int idx = (int) (RAND.nextDouble() * (imAlignments.size() - 1));
                        // Replace random record with this one
                        //Alignment oldVal = alignments.set(idx, alignment);
                        imAlignments.replace(idx, alignment.getReadName(), alignment);
                    }
                    downsampledCount++;

                }

            }

            public boolean isSampled() {
                return downsampledCount > 0;
            }

            public int getDownsampledCount() {
                return downsampledCount;
            }

            public List<Alignment> getAlignments() {
                return alignments;
                //return imAlignments.getAllValues();
            }
        }
    }


}


