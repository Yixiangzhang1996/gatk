package org.broadinstitute.hellbender.tools.copynumber;

import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;

import java.io.File;
import java.util.*;

/**
 * Caller that determines which segments of the genome have copy number events. Input should be modelFinal.seg
 * TSV files generated by the {@link ModelSegments} tool.
 *
 * <h3>Introduction</h3>
 *
 * <p>Performing copy number variation calls is a common task in genomics and in cancer research. Hereby, we implement
 * a caller that determines the copy number events based on both copy number and allele fraction data. </p>
 *
 * <p>The input data are provided by {@link ModelSegments}, and they characterize the posterior copy number
 * and allele fraction distribution of each segment. {@link CallModeledSegments} recovers the distributions from this
 * data and samples data points from it for each segment. The number of sampled points is chosen proportional to the
 * length of the segments. <p/>
 *
 * <p>The sampled data is then clustered using scikit-learn by fitting Gaussians to it in (copy_ratio, allele_fraction)
 * space. The Gaussian corresponding to normal segments is identified based on the following criteria:</p>
 * <pre>The allele fraction value of the mean of the normal peak has to be within one standard deviation
 * from the interval (normal_MAF_threshold, 0.500), where normal_MAF_threshold is input by the user using the
 * 'normal-minor-allele-fraction-threshold' flag. </pre>
 * <pre>The copy ratio of the mean of the normal peak needs to be in the range of 'normal copy ratio values',
 * as identified below</pre>
 *
 * <p>The range of 'normal copy ratio values' is identified as the range of copy ratio data arising from copy number 2
 * events. First, {@link CallModeledSegments} looks for clusters in the one dimensional copy ratio data by
 * fitting Gaussians to it. Peaks whose weight is below the threshold determined by the flag
 * are not considered for further processing. The peak with the lowest non-zero copy ratio either comes from copy
 * 'copy-ratio-peak-min-weight' number 1 or copy number 2 events. If this peak has considerable fraction of points
 * close to 0.5, then we say that it comes from copy number 2 events. Otherwise, we consider the peak of second lowest
 * copy ratio to be the copy number 2 peak. (The fraction of points that need to be in the (normal_MAF_threshold, 0.500)
 * region is an input from the user, using the 'min-fraction-of-points-in-normal-allele-fraction-region' flag.)
 * </p>
 *
 * <h3>Usage examples</h3>
 *
 * <pre>
 * gatk CallModeledSegments \
 *   -I somatic_modelFinal.seg \
 *   -O output_dir \
 *   --load-copy-ratio true \
 *   --load-allele-fraction true \
 *   --output-prefix my_somatic_run_001 \
 *   --normal-minor-allele-fraction-threshold 0.475 \
 *   --copy-ratio-peak-min-relative-height 0.04 \
 *   --min-fraction-of-points-in-normal-allele-fraction-region 0.15
 *
 * </pre>
 * @author Marton Kanasz-Nagy &lt;mkanaszn@broadinstitute.org&gt;
 */

@CommandLineProgramProperties(
        summary = "Calls the segments with copy number events",
        oneLineSummary = "Calls the segments with copy number events",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
@BetaFeature
public final class CallModeledSegments extends CommandLineProgram {
    // Arugments given by the user
    private static final String SEGMENT_CALLER_PYTHON_SCRIPT = "modeled_segments_caller_cli.py";
    public static final String OUTPUT_PREFIX_LONG_NAME = "output-prefix";
    public static final String OUTPUT_IMAGE_SUFFIX_LONG_NAME = "output-image-suffix";
    public static final String OUTPUT_CALLS_SUFFIX_LONG_NAME = "output-calls-suffix";
    public static final String LOAD_COPY_RATIO_LONG_NAME = "load-copy-ratio";
    public static final String LOAD_ALLELE_FRACTION_LONG_NAME = "load-allele-fraction";
    public static final String WEIGHT_RATIO_MAX = "weight-ratio-max";
    public static final String LOG_LONG_NAME = "log";
    public static final String INTERACTIVE_RUN_LONG_NAME = "interactive";
    private static final String INTERACTIVE_OUTPUT_CALLS_IMAGE_SUFFIX = "interactive-output-calls-image-suffix";
    private static final String INTERACTIVE_OUTPUT_SUMMARY_PLOT_SUFFIX = "interactive-output-summary-plot-suffix";
    private static final String INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX = "interactive-output-allele-fraction-plot-suffix";
    private static final String INTERACTIVE_OUTPUT_COPY_RATIO_PLOT_SUFFIX = "interactive-output-copy-ratio-suffix";
    private static final String INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX = "interactive-output-copy-ratio-clustering-suffix";
    public static final String NORMAL_MINOR_ALLELE_FRACTION_THRESHOLD = "normal-minor-allele-fraction-threshold";
    public static final String COPY_RATIO_PEAK_MIN_RELATIVE_HEIGHT = "copy-ratio-peak-min-relative-height";
    public static final String COPY_RATIO_KERNEL_DENSITY_BANDWIDTH = "copy-ratio-kernel-density-bandwidth";
    public static final String MIN_WEIGHT_FIRST_CR_PEAK_CR_DATA_ONLY = "min-weight-first-cr-peak-cr-data-only";
    public static final String MIN_FRACTION_OF_POINTS_IN_NORMAL_ALLELE_FRACTION_REGION = "min-fraction-of-points-in-normal-allele-fraction-region";

    // Adiditional arguments and variables
    public static final String OUTPUT_IMAGE_SUFFIX_DEFAULT_VALUE = ".png";
    public static final String OUTPUT_CALLS_SUFFIX_DEFAULT_VALUE = ".called.seg";
    public static final String INTERACTIVE_OUTPUT_CLASSIFICATION_IMAGE_SUFFIX_DEFAULT_VALUE = "_classification.png";
    public static final String INTERACTIVE_OUTPUT_SUMMARY_PLOT_SUFFIX_DEFAULT_VALUE = "_summary_plot.png";
    public static final String INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX_DEFAULT_VALUE = "_allele_fraction_CN1_and_CN2_candidate_intervals.png";
    public static final String INTERACTIVE_OUTPUT_COPY_RATIO_PLOT_SUFFIX_DEFAULT_VALUE = "_copy_ratio_fit.png";
    public static final String INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX_DEFAULT_VALUE = "_copy_ratio_clusters.png";
    public static final String RESPONSIBILITY_THRESHOLD_NORMAL_SEGMENTS = "responsibility-threshold-normal";
    public static final String MAX_PHRED_SCORE_NORMAL = "max-phred-score-normal";
    public static final String N_INFERENCE_ITERATIONS = "n-inference-iterations";
    public static final String INFERENCE_TOTAL_GRAD_NORM_CONSTRAINT = "inference-total-grad-norm-constraint";
    public static final String N_EXTRA_GAUSSIANS_MIXTURE_MODEL = "n-extra-gaussians-mixture-model";
    public static final String MAX_N_PEAKS_IN_COPY_RATIO = "max-n-peaks-in-copy-ratio";

    @Argument(
            doc = "Input .seg file, as generated by ModelSegments. " +
                    "It should contain data characterizing the copy ratio and allele fraction posterior distributions.",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME,
            minElements = 1
    )
    private List<File> inputFile = new ArrayList<>();

    @Argument(
            doc = "Output directory for the called copy-ratio segments file, the plots and the log file.",
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME
    )
    private File outputDir;

    @Argument(
            doc = "Prefix of output files.",
            fullName = OUTPUT_PREFIX_LONG_NAME,
            optional = false
    )
    private String outputPrefix="";

    @Argument(
            doc = "Suffix of output image file showing the normal and not normal segments.",
            fullName = OUTPUT_IMAGE_SUFFIX_LONG_NAME,
            optional = true
    )
    private String outputImageSuffix=OUTPUT_IMAGE_SUFFIX_DEFAULT_VALUE;

    @Argument(
            doc = "Suffix of output calls file.",
            fullName = OUTPUT_CALLS_SUFFIX_LONG_NAME,
            optional = true
    )
    private String outputCallsSuffix=OUTPUT_CALLS_SUFFIX_DEFAULT_VALUE;

    @Argument(
            doc = "Whether progress should be logged.",
            fullName = LOG_LONG_NAME,
            optional = true
    )
    private String doLogging="true";

    @Argument(
            doc = "Whether auxiliary plots (for debugging) should be saved.",
            fullName = INTERACTIVE_RUN_LONG_NAME,
            optional = true
    )
    private String interactiveRun="true";

    @Argument(
            doc = "Whether copy ratio data should be loaded.",
            fullName = LOAD_COPY_RATIO_LONG_NAME,
            optional = true
    )
    private String loadCopyRatio="true";

    @Argument(
            doc = "Whether allele fraction data should be loaded.",
            fullName = LOAD_ALLELE_FRACTION_LONG_NAME,
            optional = true
    )
    private String loadAlleleFraction="true";

    @Argument(
            doc = "Upon loading the data from file, we set an upper cut-off to the segment weights, "
                    + "given by weight_ratio_max * mean(weights). This is required as ModeledSegments might "
                    + "over-estimate these weights.",
            fullName = WEIGHT_RATIO_MAX,
            optional = true
    )
    private double weightRatioMax=10.;

    @Argument(
            doc = "If the allele fraction value of a peak fitted to the data is above this threshold and its copy "
                    + "ratio value is within the appropriate region, then the peak is considered normal.",
            fullName = NORMAL_MINOR_ALLELE_FRACTION_THRESHOLD,
            optional = true
    )
    private double normalMinorAlleleFractionThreshold=0.475;

    @Argument(
            doc = "During the copy ratio clustering, peaks with weights smaller than this ratio are not "
                    + "taken into account.",
            fullName = COPY_RATIO_PEAK_MIN_RELATIVE_HEIGHT,
            optional = true
    )
    private double copyRatioPeakMinRelativeHeight=0.03;

    @Argument(
            doc = "During the copy ratio clustering, we smoothen the data using a Gaussian kernel of "
                    + "this bandwidth.",
            fullName = COPY_RATIO_KERNEL_DENSITY_BANDWIDTH,
            optional = true
    )
    private double copyRatioKernelDensityBandwidth=0.;

    @Argument(
            doc = "If only copy ratio data is taken into account, and we find more than one cluster in the "
                    + "data, then the first peak is considered normal if its relative weight compared to the second"
                    + "peak is above this threshold (or if the weight of the second peak is smaller than 5%.",
            fullName = MIN_WEIGHT_FIRST_CR_PEAK_CR_DATA_ONLY,
            optional = true
    )
    private double minWeightFirstCrPeakCrDataOnly=0.35;

    @Argument(
            doc = "As a first step of finding the normal segments, the clusters of data in copy ratio space are " +
                    "identified. An interval containing such a cluster can be considered normal only if at least " +
                    "this fraction of data points are above the normalMinorAlleleFractionThreshold.",
            fullName = MIN_FRACTION_OF_POINTS_IN_NORMAL_ALLELE_FRACTION_REGION,
            optional = true
    )
    private double minFractionOfPointsInNormalAlleleFractionRegion=0.15;

    @Argument(
            doc = "Segments are considered normal if the responsibility in the Gaussian mixture model of them being "
                + "normal exceeds this threshold (set to 0.5 by default).",
            fullName = RESPONSIBILITY_THRESHOLD_NORMAL_SEGMENTS,
            optional = true
    )
    private double responsibilityThresholdNormal=0.5;

    @Argument(
            doc = "Upper cut-off for the PHRED scores on normal samples, to make sure that they are not off the "
                    + "scale on the plots.",
            fullName = MAX_PHRED_SCORE_NORMAL,
            optional = true
    )
    private double maxPhredScoreNormal=100.;

    @Argument(
            doc = "Maximum number of iterations of the automatic differentiation variational inference "
                    + "fitting a Gaussian mixture model to the segments in order to cluster them.",
            fullName = N_INFERENCE_ITERATIONS,
            optional = true
    )
    private int nInferenceIterations=30000;

    @Argument(
            doc = "In order to ensure convergence, this constant limits the value of the gradient in automatic"
                    + "differentiation variational inference, fitting a Gaussian mixture model to the segments.",
            fullName = INFERENCE_TOTAL_GRAD_NORM_CONSTRAINT,
            optional = true
    )
    private double inferenceTotalGradNormConstraint=50;

    @Argument(
            doc = "The number of Gaussians needed to classify segments is estimated using k-means clustering. "
                    + "This parameters lets us add a few extra Gaussians that might cover some of the noise or "
                    + "additional subcluster structure present in the data.",
            fullName = N_EXTRA_GAUSSIANS_MIXTURE_MODEL,
            optional = true
    )
    private int nExtraGaussiansMixtureModel=6;

    @Argument(
            doc = "In order to discover the different copy number states, the clusters in the copy ratio data "
                    + "is determined. This constant limits the maximum number of peaks that can be found in the data.",
            fullName = MAX_N_PEAKS_IN_COPY_RATIO,
            optional = true
    )
    private int maxNPeaksInCopyRatio=10 ;

    // @Override
    // protected void onStartup() {
    //     PythonScriptExecutor.checkPythonEnvironmentForPackage("modeled_segments_caller");
    // }

    @Override
    protected Object doWork() {
        Utils.validateArg(0.0 <= normalMinorAlleleFractionThreshold && normalMinorAlleleFractionThreshold <= 0.5,
                "Minor allele fraction threshold for normal peaks has to be between 0 and 0.5.");
        Utils.validateArg(0.0 <= copyRatioPeakMinRelativeHeight && copyRatioPeakMinRelativeHeight <= 1.0 ,
                "Weight threshold for copy ratio peaks considered to be normal needs to be between 0 and 1.");
        Utils.validateArg(0.0 <= minFractionOfPointsInNormalAlleleFractionRegion &&
                        minFractionOfPointsInNormalAlleleFractionRegion <= 1.0,
                "The fraction of points in the range of allele fractions is always between 0 and 1.");
        Utils.validateArg(0.0 <= responsibilityThresholdNormal &&
                        responsibilityThresholdNormal <= 1.0,
                "Responsibility threshold of a segment being normal needs to be between 0 and 1.");
        Utils.validateArg(0.0 < weightRatioMax,
                "The upper limit of the weight ratio needs to be positive");
        Utils.validateArg(0.0 < maxPhredScoreNormal,
                "The upper limit of the PHRED score of the probability that the segments are normal needs to be positive");
        Utils.validateArg(0 < nInferenceIterations,
                "The number of iterations of the inference algorithm needs to be positive. (Optimally around 30,000.)");
        Utils.validateArg(0.0 < inferenceTotalGradNormConstraint,
                "The upper limit on the norm of the gradient during the inference algorithm needs to be positive.");
        Utils.validateArg(0 <= nExtraGaussiansMixtureModel,
                "The number of extra Gaussians allowed for fitting (on top of the initial estimate) needs to be 0 or positive.");
        Utils.validateArg(0 < maxNPeaksInCopyRatio,
                "The upper limit on the number of clusters in the copy ratio data needs to be positive.");

        final File inFile = inputFile.get(0);
        logger.info(String.format("Retrieving copy ratio and allele fraction data from %s...", inFile));
        final boolean loadCR = Boolean.parseBoolean(loadCopyRatio);
        final boolean loadAF = Boolean.parseBoolean(loadAlleleFraction);
        final boolean interactive = Boolean.parseBoolean(interactiveRun);
        if (interactive && outputPrefix.equals("")) {
                // In case no name prefix is given to the images, we specify it using the input file's path
                String strArray0[] = inFile.getAbsolutePath().split("/");
                if (strArray0.length == 0) {
                    outputPrefix = "msc";
                } else {
                    String strArray1[] = strArray0[strArray0.length - 1].split(".");
                    if (strArray1.length == 0) {
                        outputPrefix = "msc";
                    }
                    else {
                        outputPrefix = strArray1[0];
                    }
                }
        }

        // Call python inference code
        final boolean pythonReturnCode = executeSegmentCaller(inFile, loadCR, loadAF, interactive);

        if (!pythonReturnCode) {
            throw new UserException("Python return code was non-zero.");
        }

        logger.info("Copy number calling task complete.");

        return "SUCCESS";
    }

    private boolean executeSegmentCaller(final File inFile, boolean loadCR, boolean loadAF, boolean interactive) {
        final PythonScriptExecutor executor = new PythonScriptExecutor(true);
        final String outputDirArg = Utils.nonEmpty(outputDir.getAbsolutePath()).endsWith(File.separator)
                ? outputDir.getAbsolutePath()
                : outputDir.getAbsolutePath() + File.separator;

        final String script;
        script = SEGMENT_CALLER_PYTHON_SCRIPT;

        final List<String> arguments = new ArrayList<>(Arrays.asList(
                "--" + StandardArgumentDefinitions.INPUT_LONG_NAME + "=" + inFile.getAbsolutePath(),
                "--" + StandardArgumentDefinitions.OUTPUT_LONG_NAME + "=" + outputDirArg,
                "--" + OUTPUT_PREFIX_LONG_NAME.replace('-','_') + "=" + String.valueOf(outputPrefix),
                "--" + OUTPUT_IMAGE_SUFFIX_LONG_NAME.replace('-','_') + "=" + String.valueOf(outputImageSuffix),
                "--" + OUTPUT_CALLS_SUFFIX_LONG_NAME.replace('-','_') + "=" + String.valueOf(outputCallsSuffix),
                "--" + LOAD_COPY_RATIO_LONG_NAME.replace('-','_') + "=" + String.valueOf(loadCR),
                "--" + LOAD_ALLELE_FRACTION_LONG_NAME.replace('-','_') + "=" + String.valueOf(loadAF),
                "--" + WEIGHT_RATIO_MAX.replace('-','_') + "=" + String.valueOf(weightRatioMax),
                "--" + LOG_LONG_NAME.replace('-','_') + "=" + String.valueOf(doLogging),
                "--" + INTERACTIVE_RUN_LONG_NAME.replace('-','_') + "=" + String.valueOf(interactive),
                "--" + INTERACTIVE_OUTPUT_CALLS_IMAGE_SUFFIX.replace('-','_') + "=" + INTERACTIVE_OUTPUT_CLASSIFICATION_IMAGE_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_SUMMARY_PLOT_SUFFIX.replace('-','_') + "=" + INTERACTIVE_OUTPUT_SUMMARY_PLOT_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX.replace('-','_') + "=" + INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_COPY_RATIO_PLOT_SUFFIX.replace('-','_') + "=" + INTERACTIVE_OUTPUT_COPY_RATIO_PLOT_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX.replace('-','_') + "=" + INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX_DEFAULT_VALUE,
                "--" + NORMAL_MINOR_ALLELE_FRACTION_THRESHOLD.replace('-','_') + "=" + String.valueOf(normalMinorAlleleFractionThreshold),
                "--" + COPY_RATIO_PEAK_MIN_RELATIVE_HEIGHT.replace('-','_') + "=" + String.valueOf(copyRatioPeakMinRelativeHeight),
                "--" + COPY_RATIO_KERNEL_DENSITY_BANDWIDTH.replace('-', '_') + "=" + String.valueOf(copyRatioKernelDensityBandwidth),
                "--" + MIN_WEIGHT_FIRST_CR_PEAK_CR_DATA_ONLY.replace('-','_') + "=" + String.valueOf(minWeightFirstCrPeakCrDataOnly),
                "--" + MIN_FRACTION_OF_POINTS_IN_NORMAL_ALLELE_FRACTION_REGION.replace('-','_') + "=" + String.valueOf(minFractionOfPointsInNormalAlleleFractionRegion),
                "--" + RESPONSIBILITY_THRESHOLD_NORMAL_SEGMENTS.replace('-','_') + "=" + String.valueOf(responsibilityThresholdNormal),
                "--" + MAX_PHRED_SCORE_NORMAL.replace('-','_') + "=" + String.valueOf(maxPhredScoreNormal),
                "--" + N_INFERENCE_ITERATIONS.replace('-','_') + "=" + String.valueOf(nInferenceIterations),
                "--" + INFERENCE_TOTAL_GRAD_NORM_CONSTRAINT.replace('-','_') + "=" + String.valueOf(inferenceTotalGradNormConstraint),
                "--" + MAX_N_PEAKS_IN_COPY_RATIO.replace('-','_') + "=" + String.valueOf(maxNPeaksInCopyRatio)));

        return executor.executeScript(
                new Resource(script, CallModeledSegments.class),
                null,
                arguments);
    }
}
