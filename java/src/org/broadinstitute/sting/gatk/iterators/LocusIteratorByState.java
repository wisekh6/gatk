/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.iterators;

import net.sf.samtools.*;
import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.Reads;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;

import java.util.*;

/**
 * Iterator that traverses a SAM File, accumulating information on a per-locus basis
 */
public class LocusIteratorByState extends LocusIterator {

    /**
     * our log, which we want to capture anything from this class
     */
    private static Logger logger = Logger.getLogger(LocusIteratorByState.class);

    // -----------------------------------------------------------------------------------------------------------------
    //
    // member fields
    //
    // -----------------------------------------------------------------------------------------------------------------
    private final PushbackIterator<SAMRecord> it;

    private class SAMRecordState {
        SAMRecord read;
        int readOffset = -1;     // how far are we offset from the start of the read bases?
        int genomeOffset = -1;   // how far are we offset from the alignment start on the genome?

        Cigar cigar = null;
        int cigarOffset = -1;
        CigarElement curElement = null;
        int nCigarElements = 0;

        // how far are we into a single cigarElement
        int cigarElementCounter = -1;

        public SAMRecordState(SAMRecord read) {
            this.read = read;
            cigar = read.getCigar();
            nCigarElements = cigar.numCigarElements();

            //System.out.printf("Creating a SAMRecordState: %s%n", this);
        }

        public SAMRecord getRead() { return read; }

        /**
         * What is our current offset in the read's bases that aligns us with the reference genome?
         *
         * @return
         */
        public int getReadOffset() { return readOffset; }

        /**
         * What is the current offset w.r.t. the alignment state that aligns us to the readOffset?
         *
         * @return
         */
        public int getGenomeOffset() { return genomeOffset; }

        public int getGenomePosition() { return read.getAlignmentStart() + getGenomeOffset(); }

        public GenomeLoc getLocation() {
            return GenomeLocParser.createGenomeLoc(read.getReferenceName(), getGenomePosition());
        }

        public CigarOperator getCurrentCigarOperator() {
            return curElement.getOperator();
        }

        public String toString() { 
            return String.format("%s ro=%d go=%d co=%d cec=%d %s", read.getReadName(), readOffset, genomeOffset, cigarOffset, cigarElementCounter, curElement);
        }

        public CigarOperator stepForwardOnGenome() {
            if ( curElement == null || ++cigarElementCounter > curElement.getLength() ) {
                cigarOffset++;
                if ( cigarOffset < nCigarElements ) {
                    curElement = cigar.getCigarElement(cigarOffset);
                    cigarElementCounter = 0;
                    // next line: guards against cigar elements of length 0; when new cigar element is retrieved,
                    // we reenter in order to re-check cigarElementCounter against curElement's length
                    return stepForwardOnGenome();
                } else {
                    return null;
                }
            }

            boolean done = false;
            switch (curElement.getOperator()) {
                case H : // ignore hard clips
                case P : // ignore pads
                    cigarElementCounter = curElement.getLength();
                    break;
                case S : // soft clip
                case I : // insertion w.r.t. the reference
                    cigarElementCounter = curElement.getLength();
                    readOffset += curElement.getLength();
                    break;
                case N : // reference skip (looks and gets processed just like a "deletion", just different logical meaning)
                case D : // deletion w.r.t. the reference
                    genomeOffset++;
                    done = true;
                    break;
                case M :
                    readOffset++;
                    genomeOffset++;
                    done = true;
                    break;
                default : throw new IllegalStateException("Case statement didn't deal with cigar op: " + curElement.getOperator());
            }

            return done ? curElement.getOperator() : stepForwardOnGenome();
        }
    }

    private LinkedList<SAMRecordState> readStates = new LinkedList<SAMRecordState>();
    //final boolean DEBUG = false;
    //final boolean DEBUG2 = false && DEBUG;
    private Reads readInfo;

    // -----------------------------------------------------------------------------------------------------------------
    //
    // constructors and other basic operations
    //
    // -----------------------------------------------------------------------------------------------------------------
    public LocusIteratorByState(final Iterator<SAMRecord> samIterator, Reads readInformation) {
        this.it = new PushbackIterator<SAMRecord>(samIterator);
        this.readInfo = readInformation;
    }

    public Iterator<AlignmentContext> iterator() {
        return this;
    }

    public void close() {
        //this.it.close();
    }

    public boolean hasNext() {
        boolean r = ! readStates.isEmpty() || it.hasNext();
        //if ( DEBUG ) System.out.printf("hasNext() = %b%n", r);
        return r;
    }

    public void printState() {
        for ( SAMRecordState state : readStates ) {
            logger.debug(String.format("printState():"));
            SAMRecord read = state.getRead();
            int offset = state.getReadOffset();
            logger.debug(String.format("  read: %s(%d)=%s, cigar=%s", read.getReadName(), offset, (char)read.getReadBases()[offset], read.getCigarString()));
        }
    }

    public void clear() {
        logger.debug(String.format(("clear() called")));
        readStates.clear();
    }

    private GenomeLoc getLocation() {
        return readStates.isEmpty() ? null : readStates.getFirst().getLocation();
    }

    // -----------------------------------------------------------------------------------------------------------------
    //
    // next() routine and associated collection operations
    //
    // -----------------------------------------------------------------------------------------------------------------
    public AlignmentContext next() {
        // keep iterating forward until we encounter a reference position that has something "real" hanging over it
        // (i.e. either a real base, or a real base or a deletion if includeReadsWithDeletion is true)
        while(true) {
            ArrayList<PileupElement> pile = new ArrayList<PileupElement>(readStates.size());
            collectPendingReads(readInfo.getMaxReadsAtLocus());

            int size = 0;
            int nDeletions = 0;
            int nMQ0Reads = 0;

            // todo -- performance problem -- should be lazy, really
            for ( SAMRecordState state : readStates ) {
                size++;
                if ( state.getCurrentCigarOperator() != CigarOperator.D && state.getCurrentCigarOperator() != CigarOperator.N ) {
                    PileupElement p = new PileupElement(state.getRead(), state.getReadOffset());
                    pile.add(p);
                } else if ( readInfo.includeReadsWithDeletionAtLoci() && state.getCurrentCigarOperator() != CigarOperator.N ) {
                    pile.add(new PileupElement(state.getRead(), -1));
                    nDeletions++;
                }

                if ( state.getRead().getMappingQuality() == 0 ) {
                    nMQ0Reads++;
                }
            }
            GenomeLoc loc = getLocation();

            updateReadStates(); // critical - must be called after we get the current state offsets and location

            // if we got reads with non-D/N over the current position, we are done
            if ( pile.size() != 0 ) return new AlignmentContext(loc, new ReadBackedPileup(loc, pile, size, nDeletions, nMQ0Reads));
        }
    }

    // old implementation -- uses lists of reads and offsets
//    public AlignmentContext next() {
//        //if (DEBUG) {
//        //    logger.debug("in Next:");
//        //    printState();
//        //}
//
//        ArrayList<SAMRecord> reads = new ArrayList<SAMRecord>(readStates.size());
//        ArrayList<Integer> offsets = new ArrayList<Integer>(readStates.size());
//
//        // keep iterating forward until we encounter a reference position that has something "real" hanging over it
//        // (i.e. either a real base, or a real base or a deletion if includeReadsWithDeletion is true)
//        while(true) {
//            collectPendingReads(readInfo.getMaxReadsAtLocus());
//
//            // todo -- performance problem -- should be lazy, really
//            for ( SAMRecordState state : readStates ) {
//                if ( state.getCurrentCigarOperator() != CigarOperator.D && state.getCurrentCigarOperator() != CigarOperator.N ) {
////                    System.out.println("Location: "+getLocation()+"; Read "+state.getRead().getReadName()+"; offset="+state.getReadOffset());
//                    reads.add(state.getRead());
//                    offsets.add(state.getReadOffset());
//                } else if ( readInfo.includeReadsWithDeletionAtLoci() && state.getCurrentCigarOperator() != CigarOperator.N ) {
//                    reads.add(state.getRead());
//                    offsets.add(-1);
//                }
//            }
//            GenomeLoc loc = getLocation();
//
//            updateReadStates(); // critical - must be called after we get the current state offsets and location
//
//        //if (DEBUG) {
//        //    logger.debug("DONE WITH NEXT, updating read states, current state is:");
//        //    printState();
//        //}
//            // if we got reads with non-D/N over the current position, we are done
//            if ( reads.size() != 0 ) return new AlignmentContext(loc, reads, offsets);
//        }
//    }

    private void collectPendingReads(final int maximumPileupSize) {
        //if (DEBUG) {
        //    logger.debug(String.format("entering collectPendingReads..., hasNext=%b", it.hasNext()));
        //    printState();
        //}

        Boolean warned = false;             // warn them once per locus
        int curSize = readStates.size();    // simple performance improvement -- avoids unnecessary size() operation
        while (it.hasNext()) {
            SAMRecord read = it.next();

//            if (DEBUG) logger.debug(String.format("Considering read %s: %s vs. %s",
//                    read.getReadName(), getLocation(), GenomeLocParser.createGenomeLoc(read)));

            if ( readIsPastCurrentPosition(read) ) {
                // We've collected up enough reads
                it.pushback(read);
                break;
            } else {
                if ( curSize >= maximumPileupSize ) {
                    if (!warned) {
                        warned = true;
                        Utils.warnUser("Unable to add a read, we're over the hanger limit of " + maximumPileupSize + " at location " + getLocation());
                    }
                } else {
                    SAMRecordState state = new SAMRecordState(read);
                    state.stepForwardOnGenome();
                    readStates.add(state);
                    curSize++;
                    //if (DEBUG) logger.debug(String.format("  ... added read %s", read.getReadName()));
                }
            }
        }
    }

    // fast testing of position
    private boolean readIsPastCurrentPosition(SAMRecord read) {
        if ( readStates.isEmpty() )
            return false;
        else {
            SAMRecordState state = readStates.getFirst();
            SAMRecord ourRead = state.getRead();
            return read.getReferenceIndex() > ourRead.getReferenceIndex() || read.getAlignmentStart() > state.getGenomePosition();
        }
    }


    private void updateReadStates() {
        Iterator<SAMRecordState> it = readStates.iterator();
        while ( it.hasNext() ) {
            SAMRecordState state = it.next();
            CigarOperator op = state.stepForwardOnGenome();
            if ( op == null ) { // we've stepped off the end of the object
                //if (DEBUG) logger.debug(String.format("   removing read %s at %d", state.getRead().getReadName(), state.getRead().getAlignmentStart()));
                it.remove();
            }
        }
    }

    public void remove() {
        throw new UnsupportedOperationException("Can not remove records from a SAM file via an iterator!");
    }
}