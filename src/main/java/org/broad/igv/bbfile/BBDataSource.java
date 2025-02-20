/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2007-2015 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.bbfile;

import org.apache.commons.math3.stat.StatUtils;
import org.broad.igv.Globals;
import org.broad.igv.bbfile.codecs.BBCodec;
import org.broad.igv.bbfile.codecs.BBCodecFactory;
import org.broad.igv.data.AbstractDataSource;
import org.broad.igv.data.BasicScore;
import org.broad.igv.data.DataTile;
import org.broad.igv.feature.*;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.track.FeatureSource;
import org.broad.igv.track.TrackType;
import org.broad.igv.track.WindowFunction;
import org.broad.igv.util.collections.FloatArrayList;
import org.broad.igv.util.collections.IntArrayList;
import htsjdk.tribble.Feature;

import java.io.IOException;
import java.util.*;

/**
 * A hybrid source, implements both DataSource and FeatureSource.   Way of the future?
 *
 * @author jrobinso
 * @date Jun 19, 2011
 */
public class BBDataSource extends AbstractDataSource implements FeatureSource {

    final int screenWidth = 1000; // TODO use actual screen width


    Collection<WindowFunction> availableWindowFunctions =
            Arrays.asList(WindowFunction.min, WindowFunction.mean, WindowFunction.max, WindowFunction.none);

    BBFileReader reader;
    private BBZoomLevels levels;

    // Feature visibility window (for bigBed)
    private int featureVisiblityWindow = -1;

    private Map<WindowFunction, List<LocusScore>> wholeGenomeScores;

    // Lookup table to support chromosome aliasing.
    private Map<String, String> chrNameMap = new HashMap();

    private RawDataInterval currentInterval = null;

    private double dataMin = 0;
    private double dataMax = 100;

    BBCodec bedCodec;

    public BBDataSource(BBFileReader reader, Genome genome) throws IOException {
        super(genome);

        this.reader = reader;
        this.levels = reader.getZoomLevels();
        this.wholeGenomeScores = new HashMap<>();

        if (reader.isBigWigFile()) initMinMax();

        // Assume 1000 pixel screen, pick visibility level to be @ highest resolution zoom.
        // NOTE: this is only used by feature tracks (bigbed sources)
        // TODO -- something smarter, like scaling by actual density
        if (levels != null && levels.getZoomHeaderCount() > 0) {
            BBZoomLevelHeader firstLevel = levels.getZoomLevelHeaders().get(0); // Highest res
            featureVisiblityWindow = firstLevel.getReductionLevel() * 2000;
        }

        if (genome != null) {
            Collection<String> chrNames = reader.getChromosomeNames();
            for (String chr : chrNames) {
                String igvChr = genome.getCanonicalChrName(chr);
                if (igvChr != null && !igvChr.equals(chr)) {
                    chrNameMap.put(igvChr, chr);
                }
            }
        }

        if(reader.isBigBedFile()) {
            String autosql = reader.getAutoSql();
            int definedFieldCount = reader.getBBFileHeader().getDefinedFieldCount();
            bedCodec = BBCodecFactory.getCodec(autosql, definedFieldCount);
        }
    }

    @Override
    public int getFeatureWindowSize() {
        return featureVisiblityWindow;
    }

    /**
     * Set the "min" and "max" from 1MB resolutiond data.  Read a maximum of 10,000 points for this
     */
    private void initMinMax() {

        final int oneMB = 1000000;
        final BBZoomLevelHeader zoomLevelHeader = getZoomLevelForScale(oneMB);

        int nValues = 0;
        double[] values = new double[10000];

        if (zoomLevelHeader == null) {
            List<String> chrNames = reader.getChromosomeNames();
            for (String chr : chrNames) {
                BigWigIterator iter = reader.getBigWigIterator(chr, 0, chr, Integer.MAX_VALUE, false);
                while (iter.hasNext()) {
                    WigItem item = iter.next();
                    values[nValues++] = item.getWigValue();
                    if (nValues >= 10000) break;
                }
            }
        } else {

            int z = zoomLevelHeader.getZoomLevel();
            ZoomLevelIterator zlIter = reader.getZoomLevelIterator(z);
            if (zlIter.hasNext()) {
                while (zlIter.hasNext()) {
                    ZoomDataRecord rec = zlIter.next();
                    values[nValues++] = (rec.getMeanVal());
                    if (nValues >= 10000) {
                        break;
                    }
                }
            }
        }

        if (nValues > 0) {
            dataMin = StatUtils.percentile(values, 0, nValues, 10);
            dataMax = StatUtils.percentile(values, 0, nValues, 90);
        } else {
            dataMin = 0;
            dataMax = 100;
        }
    }

    public double getDataMax() {
        return dataMax;
    }

    public double getDataMin() {
        return dataMin;
    }

    public TrackType getTrackType() {
        return TrackType.OTHER;
    }

    public boolean isLogNormalized() {
        return false;
    }

    public void refreshData(long timestamp) {

    }

    @Override
    public int getLongestFeature(String chr) {
        return 0;
    }


    public Collection<WindowFunction> getAvailableWindowFunctions() {
        return availableWindowFunctions;
    }

    @Override
    protected List<LocusScore> getPrecomputedSummaryScores(String chr, int start, int end, int zoom) {

        if (chr.equals(Globals.CHR_ALL)) {
            return getWholeGenomeScores();
        } else {
            return getZoomSummaryScores(chr, start, end, zoom);
        }
    }


    /**
     * Return the zoom level that most closely matches the given resolution.  Resolution is in BP / Pixel.
     *
     * @param resolution
     * @return
     */
    private BBZoomLevelHeader getZoomLevelForScale(double resolution) {

        if (levels == null) return null;

        final ArrayList<BBZoomLevelHeader> headers = levels.getZoomLevelHeaders();
        for (int i = headers.size() - 1; i >= 0; i--) {
            BBZoomLevelHeader zlHeader = headers.get(i);
            int reductionLevel = zlHeader.getReductionLevel();
            if (reductionLevel < resolution) {
                return zlHeader;
            }
        }
        return headers.get(0);

    }

    private BBZoomLevelHeader getLowestResolutionLevel() {
        final ArrayList<BBZoomLevelHeader> headers = levels.getZoomLevelHeaders();
        return headers.get(headers.size() - 1);
    }

    protected List<LocusScore> getZoomSummaryScores(String chr, int start, int end, int zoom) {

        Chromosome c = genome.getChromosome(chr);
        if (c == null) return null;

        double nBins = Math.pow(2, zoom);

        double scale = c.getLength() / (nBins * 700);

        BBZoomLevelHeader zlHeader = getZoomLevelForScale(scale);
        if (zlHeader == null) return null;

        int bbLevel = zlHeader.getZoomLevel();
        int reductionLevel = zlHeader.getReductionLevel();


        // If we are at the highest precomputed resolution compare to the requested resolution.  If they differ
        // by more than a factor of 2 compute "on the fly"
        String tmp = chrNameMap.get(chr);
        String querySeq = tmp == null ? chr : tmp;

        if (reader.isBigBedFile() || bbLevel > 1 || (bbLevel == 1 && (reductionLevel / scale) < 2)) {
            ArrayList<LocusScore> scores = new ArrayList(1000);
            ZoomLevelIterator zlIter = reader.getZoomLevelIterator(bbLevel, querySeq, start, querySeq, end, false);
            while (zlIter.hasNext()) {
                ZoomDataRecord rec = zlIter.next();

                float v = getValue(rec);
                BasicScore bs = new BasicScore(rec.getChromStart(), rec.getChromEnd(), v);
                scores.add(bs);
            }
            return scores;

        } else {
            // No precomputed scores for this resolution level
            return null;
        }
    }

    private float getValue(ZoomDataRecord rec) {

        float v;
        switch (windowFunction) {
            case min:
                v = rec.getMinVal();
                break;
            case max:
                v = rec.getMaxVal();
                break;
            default:
                v = rec.getMeanVal();

        }
        return v;
    }


    @Override
    protected synchronized DataTile getRawData(String chr, int start, int end) {

        if (chr.equals(Globals.CHR_ALL)) {
            return null;
        }


        if (currentInterval != null && currentInterval.contains(chr, start, end)) {
            return currentInterval.tile;
        }

        // TODO -- fetch data directly in arrays to avoid creation of multiple "WigItem" objects?
        IntArrayList startsList = new IntArrayList(100000);
        IntArrayList endsList = new IntArrayList(100000);
        FloatArrayList valuesList = new FloatArrayList(100000);

        String chrAlias = chrNameMap.containsKey(chr) ? chrNameMap.get(chr) : chr;
        Iterator<WigItem> iter = reader.getBigWigIterator(chrAlias, start, chrAlias, end, false);

        while (iter.hasNext()) {
            WigItem wi = iter.next();
            startsList.add(wi.getStartBase());
            endsList.add(wi.getEndBase());
            valuesList.add(wi.getWigValue());
        }

        DataTile tile = new DataTile(startsList.toArray(), endsList.toArray(), valuesList.toArray(), null);
        currentInterval = new RawDataInterval(chr, start, end, tile);

        return tile;

    }


    private List<LocusScore> getWholeGenomeScores() {

        if (genome.getHomeChromosome().equals(Globals.CHR_ALL) && windowFunction != WindowFunction.none) {

            if (wholeGenomeScores.get(windowFunction) == null) {

                double scale = genome.getNominalLength() / screenWidth;

                int maxChromId = reader.getChromosomeNames().size() - 1;
                String firstChr = reader.getChromsomeFromId(0);
                String lastChr = reader.getChromsomeFromId(maxChromId);

                ArrayList<LocusScore> scores = new ArrayList<LocusScore>();
                wholeGenomeScores.put(windowFunction, scores);

                BBZoomLevelHeader lowestResHeader = this.getZoomLevelForScale(scale);
                if (lowestResHeader == null) return null;

                Set<String> wgChrNames = new HashSet<>(genome.getLongChromosomeNames());

                ZoomLevelIterator zlIter = reader.getZoomLevelIterator(
                        lowestResHeader.getZoomLevel(), firstChr, 0, lastChr, Integer.MAX_VALUE, false);

                while (zlIter.hasNext()) {
                    ZoomDataRecord rec = zlIter.next();

                    if (rec == null) {
                        continue;
                    }

                    float value = getValue(rec);
                    if (Float.isNaN(value) || Float.isInfinite(value)) {
                        continue;
                    }

                    String chr = genome.getCanonicalChrName(rec.getChromName());

                    if (wgChrNames.contains(chr)) {

                        int genomeStart = genome.getGenomeCoordinate(chr, rec.getChromStart());
                        int genomeEnd = genome.getGenomeCoordinate(chr, rec.getChromEnd());
                        scores.add(new BasicScore(genomeStart, genomeEnd, value));
                    }
                }

                scores.sort((o1, o2) -> o1.getStart() - o2.getStart());

            }
            return wholeGenomeScores.get(windowFunction);
        } else {
            return null;
        }
    }

    @Override
    public void close() {
        super.dispose();
        if (reader != null) {
            reader.close();
        }
    }

    // Feature interface follows ------------------------------------------------------------------------

    public Iterator getFeatures(String chr, int start, int end) throws IOException {

        String tmp = chrNameMap.get(chr);
        String querySeq = tmp == null ? chr : tmp;
        BigBedIterator bedIterator = reader.getBigBedIterator(querySeq, start, querySeq, end, false);
        return new WrappedIterator(bedIterator);
    }

    public List<LocusScore> getCoverageScores(String chr, int start, int end, int zoom) {
        String tmp = chrNameMap.get(chr);
        String querySeq = tmp == null ? chr : tmp;
        return this.getSummaryScoresForRange(querySeq, start, end, zoom);
    }

    public Class getFeatureClass() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    public class WrappedIterator implements Iterator<Feature> {

        BigBedIterator bedIterator;

        public WrappedIterator(BigBedIterator bedIterator) {
            this.bedIterator = bedIterator;
        }

        public boolean hasNext() {
            return bedIterator.hasNext();  //To change body of implemented methods use File | Settings | File Templates.
        }

        public Feature next() {
            BedData feat = bedIterator.next();
            BasicFeature feature = bedCodec.decode(feat);
            return feature;
        }

        public void remove() {
            //To change body of implemented methods use File | Settings | File Templates.
        }
    }


    //  End FeatureSource interface ----------------------------------------------------------------------

    static class RawDataInterval {
        String chr;
        int start;
        int end;
        DataTile tile;

        RawDataInterval(String chr, int start, int end, DataTile tile) {
            this.chr = chr;
            this.start = start;
            this.end = end;
            this.tile = tile;
        }

        public boolean contains(String chr, int start, int end) {
            return chr.equals(this.chr) && start >= this.start && end <= this.end;
        }
    }


}
