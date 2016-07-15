package org.broadinstitute.hellbender.tools.walkers.rnaseq;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.samtools.util.Tuple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.utils.GenomeLoc;
import org.broadinstitute.hellbender.utils.GenomeLocParser;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.clipping.ReadClipper;
import org.broadinstitute.hellbender.utils.read.*;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The class manages reads and splices and tries to apply overhang clipping when appropriate. Overhangs correspond to
 * places where a read is aligned partially over Intronic splits generated by SplitNCigarReads. The class is designed
 * perform two passes identical passes, where in the first pass every place that the manager will end up changing the
 * mate strand information is recorded to be repaired with setPredictedMateInformation() when the manager will output
 * reads to the underlying writer. Running the activateWriting() switches the tool to emit output to the writer.
 * Important note: although for efficiency the manager does try to send reads to the underlying writer in coordinate
 * sorted order, it does NOT guarantee that it will do so in every case!  So unless there's a good reason not to,
 * methods that instantiate this manager should pass in a writer that does not assume the reads are pre-sorted.
 */
public class OverhangFixingManager {

    private Map<String, Tuple<Integer, String>> mateChangedReads;

    protected static final Logger logger = LogManager.getLogger(OverhangFixingManager.class);
    private static final boolean DEBUG = false;

    // how many reads should we store in memory before flushing the queue?
    private final int MAX_RECORDS_IN_MEMORY;

    // how many mismatches do we tolerate in the overhangs?
    private final int MAX_MISMATCHES_IN_OVERHANG;

    // how many bases do we tolerate in the overhang before deciding not to clip?
    private final int MAX_BASES_IN_OVERHANG;

    // should we not bother fixing overhangs?
    private final boolean doNotFixOverhangs;

    // should we process secondary reads at all
    private final boolean processSecondaryReads;

    // should reads be written to file output or recorded to repair mate information
    private boolean outputToFile;

    // header for the reads
    private final SAMFileHeader header;

    // where we ultimately write out our records
    private final GATKReadWriter writer;

    // fasta reference reader to check overhanging edges in the exome reference sequence
    private final IndexedFastaSequenceFile referenceReader;

    // the genome loc parser
    private final GenomeLocParser genomeLocParser;

    // the read cache
    private static final int initialCapacity = 5000;
    private final PriorityQueue<List<SplitRead>> waitingReadGroups;
    private int waitingReads;

    // the set of current splices to use
    private final Set<Splice> splices = new TreeSet<>(new SpliceComparator());

    protected static final int MAX_SPLICES_TO_KEEP = 1000;


    /**
     *
     * @param header                   header for the reads
     * @param writer                   actual writer
     * @param genomeLocParser          the GenomeLocParser object
     * @param referenceReader          the reference reader
     * @param maxRecordsInMemory       max records to keep in memory
     * @param maxMismatchesInOverhangs max number of mismatches permitted in the overhangs before requiring clipping
     * @param maxBasesInOverhangs      max number of bases permitted in the overhangs before deciding not to clip
     * @param doNotFixOverhangs        if true, don't clip overhangs at all
     * @param processSecondaryReads    if true, allow secondary reads to
     */
    public OverhangFixingManager(final SAMFileHeader header,
                                 final GATKReadWriter writer,
                                 final GenomeLocParser genomeLocParser,
                                 final IndexedFastaSequenceFile referenceReader,
                                 final int maxRecordsInMemory,
                                 final int maxMismatchesInOverhangs,
                                 final int maxBasesInOverhangs,
                                 final boolean doNotFixOverhangs,
                                 final boolean processSecondaryReads) {
        this.header = header;
        this.writer = writer;
        this.genomeLocParser = genomeLocParser;
        this.referenceReader = referenceReader;
        this.MAX_RECORDS_IN_MEMORY = maxRecordsInMemory;
        this.MAX_MISMATCHES_IN_OVERHANG = maxMismatchesInOverhangs;
        this.MAX_BASES_IN_OVERHANG = maxBasesInOverhangs;
        this.doNotFixOverhangs = doNotFixOverhangs;
        this.waitingReadGroups = new PriorityQueue<List<SplitRead>>(initialCapacity, new SplitReadComparator());
        this.outputToFile = false;
        this.mateChangedReads = new HashMap<>();
        this.processSecondaryReads = processSecondaryReads;
    }

    public final int getNReadsInQueue() { return waitingReads; }

    /**
     * For testing purposes only
     *
     * @return the list of reads currently in the queue
     */
    public List<List<SplitRead>> getReadsInQueueForTesting() {
        return new ArrayList<List<SplitRead>>(waitingReadGroups);
    }

    /**
     * For testing purposes only
     *
     * @return the list of splices currently in the queue
     */
    public List<Splice> getSplicesForTesting() {
        return new ArrayList<>(splices);
    }

    /**
     * Add a new observed split to the list to use
     *
     * @param contig  the contig
     * @param start   the start of the split, inclusive
     * @param end     the end of the split, inclusive
     */
    public void addSplicePosition(final String contig, final int start, final int end) {
        if ( doNotFixOverhangs ) {
            return;
        }

        // is this a new splice?  if not, we are done
        final Splice splice = new Splice(contig, start, end);
        if ( splices.contains(splice) ) {
            return;
        }

        // initialize it with the reference context
        // we don't want to do this until we know for sure that it's a new splice position
        splice.initialize(referenceReader);

        // clear the set of old split positions seen if we hit a new contig
        final boolean sameContig = splices.isEmpty() || splices.iterator().next().loc.getContig().equals(contig);
        if ( !sameContig ) {
            splices.clear();
        }

        // run this position against the existing reads
        waitingReadGroups.parallelStream().forEach( readGroup -> {
            final int size = readGroup.size();
            for (int i = 0; i < size; i++ ) {
                fixSplit(readGroup.get(i), splice);
            }
        } );

        splices.add(splice);

        if ( splices.size() > MAX_SPLICES_TO_KEEP ) {
            cleanSplices();
        }
    }

    /**
     * Add a family of split reads to the manager
     *
     * @param readGroup the family of reads to add to the manager (assumed be supplementary alignments to eachother)
     */
    public void addReadGroup(final List<GATKRead> readGroup) {
        Utils.nonNull(readGroup, "readGroup added to manager is null, which is not allowed");
        Utils.nonEmpty(readGroup, "readGroup added to manager is empty, which is not allowed");

        // if the new read is on a different contig or we have too many reads, then we need to flush the queue and clear the map
        final boolean tooManyReads = getNReadsInQueue() >= MAX_RECORDS_IN_MEMORY;
        GATKRead topRead = ((getNReadsInQueue()>0)? waitingReadGroups.peek().get(0).read : null);
        GATKRead firstNewGroup = readGroup.get(0);
        final boolean encounteredNewContig = getNReadsInQueue() > 0
                && !topRead.isUnmapped()
                && !firstNewGroup.isUnmapped()
                && !topRead.getContig().equals(firstNewGroup.getContig());

        if ( tooManyReads || encounteredNewContig ) {
            if ( DEBUG ) {
                logger.warn("Flushing queue on " + (tooManyReads ? "too many reads" : ("move to new contig: " + firstNewGroup.getContig() + " from " + topRead.getContig())) + " at " + firstNewGroup.getStart());
            }

            final int targetQueueSize = encounteredNewContig ? 0 : MAX_RECORDS_IN_MEMORY / 2;
            writeReads(targetQueueSize);
        }

        List<SplitRead> newReadGroup = readGroup.stream().map(SplitRead::new).collect(Collectors.toList());

        // Check every stored read for an overhang with the new splice
        for ( final Splice splice : splices) {
            for (int i = 0; i < newReadGroup.size(); i++) {
                fixSplit(newReadGroup.get(i), splice);
            }
        }
        // add the new reads to the queue
        waitingReadGroups.add(newReadGroup);
        waitingReads = waitingReads + newReadGroup.size();

    }

    /**
     * Clean up the list of splices by removing the lowest half of sequential splices
     */
    private void cleanSplices() {
        final int targetQueueSize = splices.size() / 2;
        final Iterator<Splice> iter = splices.iterator();
        for ( int i = 0; i < targetQueueSize; i++ ) {
            iter.next();
            iter.remove();
        }
    }

    /**
     * Try to fix the given read using the given split
     *
     * @param read        the read to fix
     * @param splice      the split (bad region to clip out)
     */
    void fixSplit(final SplitRead read, final Splice splice) {
        // if the read doesn't even overlap the split position then we can just exit
        if ( read.loc == null || !splice.loc.overlapsP(read.loc) ) {
            return;
        }
        // if the read is secondary, do not clip it by overhang clipping at all
        if (!processSecondaryReads && read.read.isSecondaryAlignment()) {
            return;
        }
        final int readReferenceLength = read.read.getEnd() - read.read.getStart() + 1;

        if ( isLeftOverhang(read.loc, splice.loc) ) {
            final int overhang = splice.loc.getStop() - read.read.getStart() + 1;
            if ( overhangingBasesMismatch(read.read.getBases(), read.read.getStart() - read.loc.getStart(), readReferenceLength, splice.reference, splice.reference.length - overhang, overhang) ) {
                final GATKRead clippedRead = ReadClipper.softClipByReadCoordinates(read.read, 0, splice.loc.getStop() - read.loc.getStart());
                read.setRead(clippedRead);
            }
        }
        else if ( isRightOverhang(read.loc, splice.loc) ) {
            final int overhang = read.loc.getStop() - splice.loc.getStart() + 1;
            if ( overhangingBasesMismatch(read.read.getBases(), read.read.getLength() - overhang, readReferenceLength, splice.reference, 0, read.read.getEnd() - splice.loc.getStart() + 1) ) {
                final GATKRead clippedRead = ReadClipper.softClipByReadCoordinates(read.read, read.read.getLength() - overhang, read.read.getLength() - 1);
                read.setRead(clippedRead);
            }
        }
    }

    /**
     * Is this a proper overhang on the left side of the read?
     *
     * @param readLoc    the read's loc
     * @param spliceLoc   the split's loc
     * @return true if it's a left side overhang
     */
    protected static boolean isLeftOverhang(final GenomeLoc readLoc, final GenomeLoc spliceLoc) {
        return readLoc.getStart() <= spliceLoc.getStop() && readLoc.getStart() > spliceLoc.getStart() && readLoc.getStop() > spliceLoc.getStop();
    }

    /**
     * Is this a proper overhang on the right side of the read?
     *
     * @param readLoc    the read's loc
     * @param spliceLoc   the split's loc
     * @return true if it's a right side overhang
     */
    protected static boolean isRightOverhang(final GenomeLoc readLoc, final GenomeLoc spliceLoc) {
        return readLoc.getStop() >= spliceLoc.getStart() && readLoc.getStop() < spliceLoc.getStop() && readLoc.getStart() < spliceLoc.getStart();
    }

    /**
     * Are there too many mismatches to the reference among the overhanging bases?
     *
     * @param read                  the read bases
     * @param readStartIndex        where to start on the read
     * @param readLength            the length of the read according to the reference, used to prevent overclipping
     *                              softclipped output from SplitNCigarReads)
     * @param reference             the reference bases
     * @param referenceStartIndex   where to start on the reference
     * @param spanToTest            how many bases to test
     * @return true if too many overhanging bases mismatch, false otherwise
     */
    protected boolean overhangingBasesMismatch(final byte[] read,
                                               final int readStartIndex,
                                               final int readLength,
                                               final byte[] reference,
                                               final int referenceStartIndex,
                                               final int spanToTest) {
        // don't process too small a span, too large a span, or a span that is most of a read
        if ( spanToTest < 1 || spanToTest > MAX_BASES_IN_OVERHANG || spanToTest > readLength / 2 ) {
            return false;
        }

        int numMismatchesSeen = 0;
        for ( int i = 0; i < spanToTest; i++ ) {
            if ( read[readStartIndex + i] != reference[referenceStartIndex + i] ) {
                if ( ++numMismatchesSeen > MAX_MISMATCHES_IN_OVERHANG ) {
                    return true;
                }
            }
        }

        // we can still mismatch overall if at least half of the bases mismatch

        return numMismatchesSeen >= ((spanToTest+1)/2);
    }

    /**
     * Close out the manager stream by clearing the read cache
     */
    public void close() {
        writeReads(0);
    }

    /**
     * Writes read groups off the top of waitingReads until the total number of reads in the set is less than the
     * target queue size. If outputToFile == false, then the it will instead mark the first item to setMateChanged in
     * mateChagnedReads
     */
    private void writeReads(int targetQueueSize) {
        // write out all of the remaining reads
        while ( getNReadsInQueue() > targetQueueSize ) {
            List<SplitRead> waitingGroup = waitingReadGroups.poll();
            waitingReads = waitingReads - waitingGroup.size();

            // Repair the supplementary groups together and add them into the writer
            if (outputToFile) {
                SplitNCigarReads.repairSuplementaryTags(waitingGroup.stream()
                        .map( r -> r.read )
                        .collect(Collectors.toList()), header);
                for (SplitRead splitRead : waitingGroup) {
                    writer.addRead(splitRead.read);
                }

                // On the first traversal we want to store reads that would be the mate
            } else {
                // Don't mark the readgroup if it is secondary (mate information should always point to the primary alignment)
                if (!waitingGroup.get(0).read.isSecondaryAlignment() && waitingGroup.get(0).hasBeenOverhangClipped()) {
                    waitingGroup.get(0).setMateChanged();
                }
            }
        }
    }

    // Sets the tool to start writing its output to the file writer
    public void activateWriting() {
        close();
        splices.clear();
        outputToFile = true;
    }

    // class to represent the reads with their soft-clip-included GenomeLocs
    public final class SplitRead {

        // Relevant information to determine if the read has been clipped by the manager
        private final Cigar oldCigar;
        private final int oldStart;

        public GATKRead read;
        public GenomeLoc loc;

        public SplitRead(final GATKRead read) {
            oldCigar = read.getCigar();
            oldStart = read.getStart();
            setRead(read);
        }

        public void setRead(final GATKRead read) {
            if ( ! read.isEmpty() ) {
                this.read = read;
                if ( ! read.isUnmapped() ) {
                    loc = genomeLocParser.createGenomeLoc(read.getContig(), ReadUtils.getSoftStart(read), ReadUtils.getSoftEnd(read));
                }
            }
        }

        // Returns true if either of the required mate information fields have been changed by the clipper
        public boolean hasBeenOverhangClipped() {
            return (!oldCigar.equals(read.getCigar())) || (oldStart != read.getStart());
        }

        // Adds the relevant information for repairing the mate to setMateChanged keyed on a string composed of the start position
        public void setMateChanged() {
            if (!read.isUnmapped()) {
                mateChangedReads.put(read.getName() + (read.isFirstOfPair()? 0: 1) + oldStart,
                        new Tuple<>(read.getStart(),read.getCigar().toString()));
            }
        }
    }

    /**
     * Will edit the mate MC tag and mate start position field for given read if that read has been recorded being edited
     * by the OverhangFixingManager before. Returns true if the read was edited by the tool, false otherwise.
     *
     * @param read the read to be edited
     */
    public boolean setPredictedMateInformation(GATKRead read) {
        if (!outputToFile) {
            return false;
        }
        if (!read.isEmpty() && read.isPaired()) {
            String keystring = read.getName() + (read.isFirstOfPair()? 1: 0) + read.getMateStart();
            if (mateChangedReads.containsKey(keystring)) {
                Tuple<Integer, String> value = mateChangedReads.get(keystring);

                // update the start position so it is accurate
                read.setMatePosition(read.getMateContig(), value.a);

                // if the MC tag is present, update it too
                if (read.hasAttribute("MC")) {read.setAttribute("MC", value.b);}
                return true;
            }
        }
        return false;
    }

    // class to represent the comparator for the split reads
    private final class SplitReadComparator implements Comparator<List<SplitRead>>, Serializable {

        private static final long serialVersionUID = 7956407034441782842L;
        private final ReadCoordinateComparator readComparator;

        public SplitReadComparator() {
            readComparator = new ReadCoordinateComparator(header);
        }

        public int compare(final List<SplitRead> readgroup1, final List<SplitRead> readgroup2) {
            return readComparator.compare(readgroup1.get(0).read, readgroup2.get(0).read);
        }
    }


    // class to represent the split positions
    protected final class Splice {

        public final GenomeLoc loc;
        public byte[] reference;

        public Splice(final String contig, final int start, final int end) {
            loc = genomeLocParser.createGenomeLoc(contig, start, end);
        }

        public void initialize(final IndexedFastaSequenceFile referenceReader) {
            reference = referenceReader.getSubsequenceAt(loc.getContig(), loc.getStart(), loc.getStop()).getBases();
        }

        @Override
        public boolean equals(final Object other) {
            return other != null && (other instanceof Splice) && this.loc.equals(((Splice)other).loc);
        }

        @Override
        public int hashCode() {
            return loc.hashCode();
        }
    }

    // class to represent the comparator for the split reads
    private final class SpliceComparator implements Comparator<Splice>, Serializable {
        private static final long serialVersionUID = -7783679773557594065L;

        public int compare(final Splice position1, final Splice position2) {
            return position1.loc.compareTo(position2.loc);
        }
    }
}
