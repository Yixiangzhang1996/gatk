package org.broadinstitute.hellbender.tools.walkers.rnaseq;

import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.utils.test.ArgumentsBuilder;
import org.broadinstitute.hellbender.utils.test.IntegrationTestSpec;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Arrays;

/**
 *
 * Tests all possible (and valid) cigar strings that might contain any cigar elements. It uses a code that were written to test the ReadClipper walker.
 * For valid cigar sting in length 8 there are few thousands options, with N in every possible option and with more than one N (for example 1M1N1M1N1M1N2M).
 * The cigarElements array is used to provide all the possible cigar element that might be included.
 */
public final class SplitNCigarReadsIntegrationTest extends CommandLineProgramTest {

    @Test (enabled = true)
    public void testSplitsWithOverhangs()  throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "-R " + b37_reference_20_21 + " -I " + largeFileTestDir + "NA12878.RNAseq.bam -O %s ", //--processSecondaryAlignments",
                Arrays.asList(largeFileTestDir + "expected.NA12878.RNAseq.splitNcigarReads.bam"));    //results created using gatk3.46
        spec.executeTest("test splits with overhangs", this);
    }

    @Test (enabled = true)
    public void testSplitsWithOverhangsNotClipping() throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "--doNotFixOverhangs -R " + b37_reference_20_21 + " -I " + largeFileTestDir + "NA12878.RNAseq.bam -O %s --processSecondaryAlignments",
                Arrays.asList(largeFileTestDir + "expected.NA12878.RNAseq.splitNcigarReads.doNotFixOverhangs.bam"));   //results created using gatk3.46
        spec.executeTest("test splits with overhangs not clipping", this);
    }

    @Test (enabled = true)
    public void testSplitsWithOverhangs0Mismatches() throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "--maxMismatchesInOverhang 0 -R " + b37_reference_20_21 + " -I " + largeFileTestDir + "NA12878.RNAseq.bam -O %s --processSecondaryAlignments",
                Arrays.asList(largeFileTestDir + "expected.NA12878.RNAseq.splitNcigarReads.maxMismatchesInOverhang0.bam"));   //results created using gatk3.46
        spec.executeTest("test splits with overhangs 0 mismatches", this);
    }

    @Test (enabled = true)
    public void testSplitsWithOverhangs5BasesInOverhang()  throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "--maxBasesInOverhang 5 -R " + b37_reference_20_21 + " -I " + largeFileTestDir + "NA12878.RNAseq.bam -O %s --processSecondaryAlignments",
                Arrays.asList(largeFileTestDir + "expected.NA12878.RNAseq.splitNcigarReads.maxBasesInOverhang5.bam"));    //results created using gatk3.46
        spec.executeTest("test splits with overhangs 5 bases in overhang", this);
    }

    @Test (enabled = true)
    public void testSplitsFixNDN() throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "-R " + b37_reference_20_21 + " -I " + getTestDataDir() +"/" + "splitNCigarReadsSnippet.bam -O %s -fixNDN --processSecondaryAlignments",
                Arrays.asList(getTestDataDir() +"/" + "expected.splitNCigarReadsSnippet.splitNcigarReads.fixNDN.bam"));
        spec.executeTest("test fix NDN", this);
    }

    @Test (enabled = true) //regression test for https://github.com/broadinstitute/gatk/pull/1853
    public void testSplitsOfUnpairedAndUnmappedReads() throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "-R" + b37_reference_20_21 + " -I " + largeFileTestDir + "K-562.duplicateMarked.chr20.bam -O %s --processSecondaryAlignments",
                Arrays.asList(largeFileTestDir + "expected.K-562.splitNCigarReads.chr20.bam")); //results created using gatk3.5
        spec.executeTest("regression test for unmapped and unpaired reads", this);
    }

    @Test (enabled = true) //regression test for https://github.com/broadinstitute/gatk/pull/1864
    public void testSplitsTargetRegionFunctionality() throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "-R" + b37_reference_20_21 + " -I " + largeFileTestDir + "NA12878.RNAseq.bam -O %s -L 20:2444518-2454410 --processSecondaryAlignments",
                Arrays.asList(largeFileTestDir + "expected.NA12878.RNAseq.splitNcigarReads.subSequenceTest.bam")); //results created using gatk3.5
        spec.executeTest("regression test for unmapped and unpaired reads", this);
    }

    // Note: this test will fail in IntelliJ unless you add "-Dsnappy.disable=true" to your JVM arguments
    @Test //regression test for https://github.com/broadinstitute/gatk/issues/2026
    public void testLargeFileThatForcesSnappyUsage(){
        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addReference(new File(b37_reference_20_21))
                .addInput(new File(largeFileTestDir, "CEUTrio.HiSeq.WGS.b37.NA12878.20.21.bam"))
                .addOutput(createTempFile("largeSplitNCigarReadsTest",".bam"));
        //Just make sure this doesn't fail with NoClassDefFoundError
        runCommandLine(args);
    }

    @Test
    public void testSplitsWithoutSecondaryAlignments()  throws Exception {
        IntegrationTestSpec spec = new IntegrationTestSpec(
                "-R " + b37_reference_20_21 + " -I " + largeFileTestDir + "NA12878.RNAseq.bam -O %s ",
                Arrays.asList(largeFileTestDir + "expected.NA12878.RNAseq.splitNcigarReads.noSecondaryAlignments.bam"));
        spec.executeTest("test splits with overhangs", this);
    }
}