package weka.filters.unsupervised.attribute;

import no.uib.cipr.matrix.Matrix;
import no.uib.cipr.matrix.*;
import weka.core.*;
import weka.filters.SimpleBatchFilter;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * A filter that can be used to process a collection of images for classification or clustering. It applies
 * spherical k-means to image batches extracted from the first batch of images it receives and uses
 * the resulting k-means centroids to process images into feature vectors suitable for classification or
 * clustering.
 * <p>
 * The first attribute in the input data is required to be a string attribute with the file names of images.
 */
public class KMeansImageFilter extends SimpleBatchFilter {

    /**
     * The seed for the random number generator.
     */
    protected int m_seed = 0;

    /**
     * The width and height of each extracted image patch in pixels.
     */
    protected int m_cropSize = 8;

    /**
     * The number of patches to be extracted from each image.
     */
    protected int m_numPatchesPerImage = 1;

    /**
     * The number of clusters aka filters aka dictionary atoms to use.
     */
    protected int m_K = 1000;

    /**
     * The stride for the application of the filters (when feature vectors are computed).
     */
    protected int m_stride = 4;

    /**
     * The height and width of the pool used to reduce the dimensionality of the feature vectors.
     */
    protected int m_poolSize = 2;

    /**
     * The dictionary matrix, with one column per filter (aka atom).
     */
    protected Matrix m_D;

    /**
     * The height and width of the images.
     */
    protected int m_imgSize;

    /**
     * The method used to establish the format of the data generated by this filter, as an Instances object.
     *
     * @param data the input dataset, with a string attribute containing the file names of the images
     * @return an Instances object that provides the "header" information for the data generated by this filter
     * (i.e., an Instances object that does not contain any actual instances but does contain a list of attributes).
     */
    public Instances determineOutputFormat(Instances data) {

        debugPrint("Determining output format.");
        int imgSize = -1;
        for (int i = 0; i < data.numInstances(); i++) {
            String fileName = data.instance(i).stringValue(0);
            BufferedImage img = null;
            try {
                img = ImageIO.read(new File(fileName));
            } catch (Exception ex) {
                System.err.println("Could not load: " + fileName);
            }
            if (img.getWidth() != img.getHeight()) {
                throw new IllegalArgumentException("Image " + fileName + " is not square.");
            }
            if (imgSize == -1) {
                imgSize = img.getWidth();
            } else if (imgSize != img.getWidth()) {
                throw new IllegalArgumentException("Image " + fileName + " has different size.");
            }
        }
        debugPrint("Image size is: " + imgSize);
        if (((imgSize - m_cropSize) % m_stride) != 0) {
            throw new IllegalArgumentException("Image height not compatible with patch size and stride");
        }
        if ((1 + ((imgSize - m_cropSize) / m_stride)) % m_poolSize != 0) {
            throw new IllegalArgumentException("Pool size not compatible with raw features.");
        }

        int nFeatPerFilterAndDimension = (1 + ((imgSize - m_cropSize) / m_stride)) / m_poolSize;
        int numFeatures = nFeatPerFilterAndDimension * nFeatPerFilterAndDimension * m_K;

        ArrayList<Attribute> atts = new ArrayList<>(numFeatures + 1);
        for (int i = 0; i < numFeatures; i++) {
            atts.add(new Attribute("x" + (i + 1)));
        }
        if (data.classIndex() > -1) {
            atts.add((Attribute) data.classAttribute().copy());
        }
        Instances output = new Instances("features", atts, 0);
        output.setClassIndex(output.numAttributes() - 1);
        debugPrint("Finished determining output format with " + output.numAttributes() + " attributes.");
        return output;

    }

    /**
     * The method that processes the given dataset and outputs the filtered data.
     *
     * @param data the input data to be filtered, with a string attribute containing the file names of the images
     * @return the filtered data, consisting of feature vectors ready for other machine learning algorithms
     */
    public Instances process(Instances data) {
        // We will need a random number generator
        Random rand = new Random(m_seed);

        // Establish number of rows and columns for data matrix X
        int numPatchPixels = m_cropSize * m_cropSize;
        int numPatchValues = numPatchPixels * 3; // Three colour channels
        int numPatches = m_numPatchesPerImage * data.numInstances();

        // Create constant vectors that we will reuse many times to center the values in each patch
        Vector oneOverNumPatchValues = constantVector(1.0 / numPatchValues, numPatchValues);
        Vector allOnesNumPatchValues = constantVector(1.0, numPatchValues);

        // Is this the first batch of data passed through the filter (i.e., the filter bank has not been
        // created yet)?
        if (!isFirstBatchDone()) {
            // Read image patches, normalize patches, and turn them into columns in the matrix X
            Matrix X = new DenseMatrix(numPatchValues, numPatches);
            int colIndex = 0;
            // For each image
            for (int i = 0; i < data.numInstances(); i++) {
                String fileName = data.instance(i).stringValue(0);
                BufferedImage img = null;
                try {
                    img = ImageIO.read(new File(fileName));
                    m_imgSize = img.getWidth();
                    int xmax = 1 + img.getWidth() - m_cropSize;
                    int ymax = 1 + img.getHeight() - m_cropSize;
                    // For the number of patches per image
                    for (int p = 0; p < m_numPatchesPerImage; p++) {
                        // Create a patch
                        BufferedImage patch = img.getSubimage(rand.nextInt(xmax), rand.nextInt(ymax), m_cropSize, m_cropSize);
                        int index = 0;
                        Vector vec = new DenseVector(numPatchValues);
                        // Create a vector of with the r, g and b values for each pixel in the patch
                        for (int j = 0; j < m_cropSize; j++) {
                            for (int k = 0; k < m_cropSize; k++) {
                                int rgb = patch.getRGB(k, j);
                                int r = (rgb >> 16) & 0xFF;
                                int g = (rgb >> 8) & 0xFF;
                                int b = (rgb & 0xFF);
                                vec.set(index, r);
                                vec.set(numPatchPixels + index, g);
                                vec.set(2 * numPatchPixels + index, b);
                                index++;
                            }
                        }

                        // Normalize the values to mean 0 standard deviation ~1
                        Vector centeredVec = vec.add(-vec.dot(oneOverNumPatchValues), allOnesNumPatchValues);
                        double norm = centeredVec.norm(Vector.Norm.Two);
                        Vector normalizedVec = centeredVec.scale(1.0 / Math.sqrt((norm * norm) / vec.size() + 10));

                        // Set a column for X to the vector
                        for (int r = 0; r < normalizedVec.size(); r++) {
                            X.set(r, colIndex, normalizedVec.get(r));
                        }
                        colIndex++;
                    }
                } catch (IOException e) {
                    System.err.println("File " + fileName + " could not be read");
                }
            }

            // Perform whitening
            debugPrint("Calculating mean value for each pixel in X.");
            Vector mean = X.mult(constantVector(1.0 / numPatches, numPatches), new DenseVector(numPatchValues));

            debugPrint("Calculating centered version of X and storing it in S.");
            Matrix S = new DenseMatrix(X);
            S = (new DenseMatrix(mean)).transBmultAdd(-1.0, new DenseMatrix(constantVector(1.0, numPatches)), S);

            debugPrint("Calculating covariance matrix.");
            Matrix cov = (new UpperSPDDenseMatrix(numPatchValues)).rank1(1.0 / numPatches, S);

            debugPrint("Performing eigenvalue decomposition.");
            SymmDenseEVD evd = null;
            try {
                evd = SymmDenseEVD.factorize(cov);
            } catch (NotConvergedException e) {
                e.printStackTrace();
                System.exit(1);
            }
            double[] evals = evd.getEigenvalues();
            Matrix V = evd.getEigenvectors();
            Matrix E = new UpperSymmDenseMatrix(evals.length);
            for (int i = 0; i < evals.length; i++) {
                E.set(i, i, 1.0 / Math.sqrt(evals[i] + 0.1));

            }
            debugPrint("Whitening data.");
            X = V.mult(E, new DenseMatrix(V.numRows(), E.numColumns())).transBmult(V, new UpperSymmDenseMatrix(V.numRows())).
                    mult(X, new DenseMatrix(V.numRows(), X.numColumns()));

            //
            // MY CODE BELOW
            //

            debugPrint("Initializing dictionary.");
            // Initialize centroids
            initialiseCentroids(numPatchValues, rand);

            debugPrint("Running spherical k-means.");
            // Define matrix S
            S = new DenseMatrix(m_K, numPatches);
            int count = 0;
            double oldSumOfSquaredError = Double.POSITIVE_INFINITY;
            boolean maybeEmptyCentroids = true;

            // Iterate K means algorithm
            do {
                // Calculate S matrix
                calculateSMatrix(X, S, numPatches);

                debugPrint("Calculating squared error.");
                // Calculate sum of squared errors
                double sumOfSquaredErrors = calculateSumOfSquaredErrors(X, S);
                debugPrint("SSE at iteration " + count + ": " + sumOfSquaredErrors);

                // Check if sum of squared errors has decreased by a significant enough amount to keep going
                if ((oldSumOfSquaredError - sumOfSquaredErrors) / oldSumOfSquaredError < 1e-12) {
                    break;
                }
                oldSumOfSquaredError = sumOfSquaredErrors;

                // Identify and replace empty patches
                if (maybeEmptyCentroids) {
                    int numEmpty = replaceEmptyPatches(X, S, numPatches, numPatchValues, rand);
                    if (numEmpty == 0)
                        maybeEmptyCentroids = false;

                    debugPrint("Number of empty centroids: " + numEmpty);
                }

                // Optimise dictionary
                optimiseDictionary(X, S, numPatchValues);
                // Limit iterations to 200
            } while (++count < 200);

            // Save all the patches as images
//            savePatches(numPatchValues);
        }

        // Start image processing
        Instances output = getOutputFormat();
        int numPatchesPerDimension = 1 + ((m_imgSize - m_cropSize) / m_stride);
        int numPatchesPerImg = numPatchesPerDimension * numPatchesPerDimension;
        int numPoolsPerDimension = numPatchesPerDimension / m_poolSize;
        int numPoolsPerImg = numPoolsPerDimension * numPoolsPerDimension;

        for (Instance inst : data) {
            String fileName = inst.stringValue(0);

            debugPrint("Calculating image features for " + fileName);

            debugPrint("Extracting patches.");
            // Extract patches
            Matrix P = null;
            try {
                P = extractPatches(fileName, numPatchValues, numPatchesPerImg, numPoolsPerDimension, numPatchPixels, allOnesNumPatchValues, oneOverNumPatchValues);
            } catch (IOException e) {
                System.err.println("File " + fileName + " could not be read");
                System.exit(0);
            }

            debugPrint("Applying feature matrix to patches.");
            // Calculate feature vectors
            Matrix featureMatrix = new DenseMatrix(m_K, numPatchesPerImg);
            m_D.transAmult(P, featureMatrix);

            debugPrint("Pooling features");
            // Pool features
            double[] featureVector = poolFeatures(featureMatrix, output.numAttributes(), numPoolsPerImg);

            // Set class
            featureVector[output.classIndex()] = inst.classValue();

            // Add features for image to output
            output.add(new DenseInstance(inst.weight(), featureVector));
        }

        return output;
    }

    /**
     * Initialises centroids in m_D by sampling a normal distribution and normalising the vectors.
     *
     * @param numPatchValues number of values in a patch
     * @param rand           random object to use
     */
    private void initialiseCentroids(int numPatchValues, Random rand) {
        // Create random centroids
        m_D = new DenseMatrix(numPatchValues, m_K);
        for (int c = 0; c < m_K; c++) {
            Vector centroid = new DenseVector(numPatchValues);
            // Sample normal distribution
            for (int r = 0; r < numPatchValues; r++) {
                centroid.set(r, rand.nextGaussian());
            }

            // Normalise centroid
            double norm = centroid.norm(Vector.Norm.Two);
            if (norm != 0)
                centroid = centroid.scale(1 / norm);

            // Copy values to matrix
            for (int r = 0; r < numPatchValues; r++) {
                m_D.set(r, c, centroid.get(r));
            }
        }
    }

    /**
     * Assigns each data point to the closest centroid in m_D.
     *
     * @param X          matrix of data points
     * @param S          matrix to store results in
     * @param numPatches number of values in a patch
     */
    private void calculateSMatrix(Matrix X, Matrix S, int numPatches) {
        debugPrint("Calculating initial S matrix.");
        // Calculate S
        S = m_D.transAmult(X, S);

        debugPrint("Setting appropriate values to zero.");
        // Set values to 0 where needed
        for (int c = 0; c < numPatches; c++) {
            // Set first as max
            double max = S.get(0, c);
            int maxIndex = 0;
            for (int r = 1; r < m_K; r++) {
                double test = Math.abs(S.get(r, c));
                // If new max
                if (test > max) {
                    // Set old max to 0
                    S.set(maxIndex, c, 0);

                    // Store new max
                    max = test;
                    maxIndex = r;
                }
                // Set to 0 if not new max
                else {
                    S.set(r, c, 0);
                }
            }
        }
    }

    /**
     * Replaces all the empty patches in m_D.
     *
     * @param X              matrix of data points
     * @param S              matrix assigning data points to closest centroids
     * @param numPatches     number of patches
     * @param numPatchValues number of values in a patch
     * @param rand           random object to use
     * @return the number of patches that were empty
     */
    private int replaceEmptyPatches(Matrix X, Matrix S, int numPatches, int numPatchValues, Random rand) {
        int numEmpty = 0;
        // For each row in S (i.e. centroid)
        for (int r = 0; r < m_K; r++) {
            boolean empty = true;

            // For each column in S (i.e. patch)
            for (int c = 0; c < numPatches; c++) {
                // If that patch is attributes to that centroid
                if (S.get(r, c) != 0) {
                    // Centroid is not empty
                    empty = false;
                    break;
                }
            }

            // If centroid is empty
            if (empty) {
                numEmpty++;

                Vector centroid = new DenseVector(numPatchValues);
                // Set centroid to a patch
                int c = rand.nextInt(numPatches);
                for (int r2 = 0; r2 < numPatchValues; r2++) {
                    centroid.set(r2, X.get(r2, c));
                }

                // Normalise centroid
                double norm = centroid.norm(Vector.Norm.Two);
                if (norm != 0)
                    centroid = centroid.scale(1 / norm);

                // Copy values to matrix
                for (int r2 = 0; r2 < numPatchValues; r2++) {
                    m_D.set(r2, r, centroid.get(r2));
                }

                // Note: This updated centroid will not be optimised this iteration because centroids have already been assigned
            }
        }

        return numEmpty;
    }

    /**
     * Optimise the dictionary m_D.
     *
     * @param X              matrix of data points
     * @param S              matrix assigning data points to closest centroids
     * @param numPatchValues number of values in a patch
     */
    private void optimiseDictionary(Matrix X, Matrix S, int numPatchValues) {
        debugPrint("Updating dictionary.");
        // Calculate new D
        m_D = X.transBmultAdd(S, m_D);

        debugPrint("Normalising dictionary.");
        // Normalise new D
        for (int c = 0; c < m_K; c++) {
            Vector centroid = new DenseVector(numPatchValues);
            for (int r = 0; r < numPatchValues; r++) {
                centroid.set(r, m_D.get(r, c));
            }

            double norm = centroid.norm(Vector.Norm.Two);
            if (norm != 0)
                centroid = centroid.scale(1 / norm);

            for (int r = 0; r < numPatchValues; r++) {
                m_D.set(r, c, centroid.get(r));
            }
        }
    }

    /**
     * Extracts patches from an image file.
     *
     * @param fileName              file name of image to extract patches from
     * @param numPatchValues        number of values in a patch
     * @param numPatchesPerImg      number of patches that will be extracted from each image
     * @param numPoolsPerDimension  number of pools per dimension
     * @param numPatchPixels        number of pixels in a patch
     * @param allOnesNumPatchValues vector full of ones
     * @param oneOverNumPatchValues vector full of one over the number of patch values
     * @return matrix of extracted patches ordered by pools
     * @throws IOException error reading from image file
     */
    private Matrix extractPatches(String fileName, int numPatchValues, int numPatchesPerImg, int numPoolsPerDimension, int numPatchPixels, Vector allOnesNumPatchValues, Vector oneOverNumPatchValues) throws IOException {
        BufferedImage img = ImageIO.read(new File(fileName));

        Matrix P = new DenseMatrix(numPatchValues, numPatchesPerImg);

        int colIndex = 0;
        // For each pool
        for (int poolX = 0; poolX < numPoolsPerDimension; poolX++) {
            for (int poolY = 0; poolY < numPoolsPerDimension; poolY++) {
                // For each patch in the pool
                for (int localPatchX = 0; localPatchX < m_poolSize; localPatchX++) {
                    for (int localPatchY = 0; localPatchY < m_poolSize; localPatchY++) {
                        int globalPatchX = poolX * m_poolSize + localPatchX;
                        int globalPatchY = poolY * m_poolSize + localPatchY;
                        int pixelX = globalPatchX * m_stride;
                        int pixelY = globalPatchY * m_stride;

                        // Get patch
                        BufferedImage patch = img.getSubimage(pixelX, pixelY, m_cropSize, m_cropSize);

                        // Put patch values in vector
                        int index = 0;
                        Vector patchVec = new DenseVector(numPatchValues);
                        for (int j = 0; j < m_cropSize; j++) {
                            for (int k = 0; k < m_cropSize; k++) {
                                int rgb = patch.getRGB(k, j);
                                int red = (rgb >> 16) & 0xFF;
                                int green = (rgb >> 8) & 0xFF;
                                int blue = (rgb & 0xFF);
                                patchVec.set(index, red);
                                patchVec.set(numPatchPixels + index, green);
                                patchVec.set(2 * numPatchPixels + index, blue);
                                index++;
                            }
                        }

                        // Normalize the values to mean 0 standard deviation ~1
                        Vector centeredVec = patchVec.add(-patchVec.dot(oneOverNumPatchValues), allOnesNumPatchValues);
                        double norm = centeredVec.norm(Vector.Norm.Two);
                        Vector normalizedVec = centeredVec.scale(1.0 / Math.sqrt((norm * norm) / patchVec.size() + 10));

                        // Set a column for patchMatrix to the vector
                        for (int r = 0; r < normalizedVec.size(); r++) {
                            P.set(r, colIndex, normalizedVec.get(r));
                        }
                        colIndex++;
                    }
                }
            }
        }

        return P;
    }

    /**
     * Pool features together.
     *
     * @param unpooledFeatureMatrix matrix of unpooled features
     * @param numFeatures           number of features in result
     * @param numPoolsPerImg        number of pools in image
     * @return array of features
     */
    private double[] poolFeatures(Matrix unpooledFeatureMatrix, int numFeatures, int numPoolsPerImg) {
        // Setup feature vector
        double[] featureVector = new double[numFeatures];

        // Pool vales together
        int colIndex = 0;
        // For each pool
        for (int i = 0; i < numPoolsPerImg; i++) {
            // For each patch in the pool
            for (int j = 0; j < m_poolSize * m_poolSize; j++) {
                // Add the value in each row to the final feature vector
                for (int r = 0; r < m_K; r++) {
                    featureVector[i * m_K + r] += Math.max(0, unpooledFeatureMatrix.get(r, colIndex));
                }

                colIndex++;
            }
        }

        return featureVector;
    }

    /**
     * Print a string if set to output debug info.
     *
     * @param s the string to print
     */
    protected void debugPrint(String s) {
        if (m_Debug) {
            System.err.println(s);
        }
    }

    /**
     * A utility method for calculating the sum of squared errors during k-means iterations
     * based on the X matrix, the current S matrix, and the current dictionary stored in
     * a member variable.
     *
     * @param X the X matrix
     * @param S the S matrix
     * @return the sum of squared errors
     */
    protected double calculateSumOfSquaredErrors(Matrix X, Matrix S) {
        double fNorm = m_D.mult(S, new DenseMatrix(X.numRows(), X.numColumns())).add(-1, X).norm(Matrix.Norm.Frobenius);
        return fNorm * fNorm;
    }

    /**
     * The info shown in the GUI.
     *
     * @return the info describing the filter.
     */
    public String globalInfo() {
        return "This filter performs feature extraction from images using the spherical k-means algorithm.";
    }

    /**
     * The capabilities of this filter.
     *
     * @return the capabilities
     */
    public Capabilities getCapabilities() {
        Capabilities result = super.getCapabilities();
        result.enable(Capabilities.Capability.STRING_ATTRIBUTES);
        result.enableAllClasses();
        result.enable(Capabilities.Capability.NO_CLASS); // Filter doesn't require class to be set
        result.enable(Capabilities.Capability.MISSING_CLASS_VALUES);
        return result;
    }

    /**
     * We need to have access to the full input format so that we can read the images.
     *
     * @return true
     */
    public boolean allowAccessToFullInputFormat() {
        return true;
    }

    /**
     * Saves all of the patches as images to the folder features.
     *
     * @param numPatchValues number of patches
     */
    private void savePatches(int numPatchValues) {
        File folder = new File("features");
        if (!folder.exists()) {
            folder.mkdir();

            for (int i = 0; i < m_K; i++) {
                DenseVector v = new DenseVector(numPatchValues);
                for (int j = 0; j < numPatchValues; j++) {
                    v.set(j, m_D.get(j, i));
                }
                try {
                    saveVector(v, true, "features/feature" + (i + 1) + ".png");
                } catch (IOException e) {
                    System.err.println("Failed to save feature" + (i + 1));
                }
            }
        }
    }

    /**
     * Saves a vector as an image, assuming the image is square and has 3 colour channels given consecutively.
     *
     * @param v        the vector to plot
     * @param rescale  whether to rescale the data to the 0-255 range before plotting it
     * @param fileName filename to save image as
     */
    protected void saveVector(Vector v, boolean rescale, String fileName) throws IOException {
        int dim = (int) Math.round(Math.sqrt(v.size() / 3));
        int width = 10 * (int) Math.round(Math.sqrt(v.size() / 3));
        int height = 10 * (int) Math.round(Math.sqrt(v.size() / 3));
        int[] pixels = new int[width * height];
        int xSize = width / dim;
        int ySize = height / dim;
        int x = 0;
        int y = -ySize;
        double[] min = new double[3];
        double[] max = new double[3];
        if (rescale) { // Find minimum and maximum "intensity" value for each channel
            for (int i = 0; i < v.size() / 3; i++) {
                if (i % dim == 0) {
                    x = 0;
                    y += ySize;
                }
                double r = v.get(i);
                double g = v.get(i + v.size() / 3);
                double b = v.get(i + 2 * v.size() / 3);
                if (min[0] > r) {
                    min[0] = r;
                }
                if (min[1] > g) {
                    min[1] = g;
                }
                if (min[2] > b) {
                    min[2] = b;
                }
                if (max[0] < r) {
                    max[0] = r;
                }
                if (max[1] < g) {
                    max[1] = g;
                }
                if (max[2] < b) {
                    max[2] = b;
                }
                x += xSize;
            }
            x = 0;
            y = -ySize;
        } else {
            for (int i = 0; i < 3; i++) {
                max[i] = 255.0;
            }
        }
        for (int i = 0; i < v.size() / 3; i++) {
            if (i % dim == 0) {
                x = 0;
                y += ySize;
            }
            int r = (int) (255.0 * ((v.get(i) - min[0]) / (max[0] - min[0])));
            int g = (int) (255.0 * ((v.get(i + v.size() / 3) - min[1]) / (max[1] - min[1])));
            int b = (int) (255.0 * ((v.get(i + 2 * v.size() / 3) - min[2]) / (max[2] - min[2])));

            int rgb = (r << 16) + (g << 8) + (b);
            for (int yoff = y; yoff < y + ySize; yoff++) {
                for (int xoff = x; xoff < x + xSize; xoff++) {
                    pixels[yoff * width + xoff] = rgb;
                }
            }

            x += xSize;
        }

        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, width, height, pixels, 0, width);
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1);
        ImageIO.write(bi, ext, new File(fileName));
    }

    /**
     * Plots a vector as an image, assuming the image is square and has 3 colour channels given consecutively.
     * This method can potentially be used for debugging purposes.
     *
     * @param v       the vector to plot
     * @param rescale whether to rescale the data to the 0-255 range before plotting it
     */
    protected void plotVector(Vector v, boolean rescale) {
        class MyPanel extends JPanel {
            protected void paintComponent(Graphics gr) {
                super.paintComponent(gr);
                int dim = (int) Math.round(Math.sqrt(v.size() / 3));
                int xSize = getWidth() / dim;
                int ySize = getHeight() / dim;
                int x = 0;
                int y = -ySize;
                double[] min = new double[3];
                double[] max = new double[3];
                if (rescale) { // Find minimum and maximum "intensity" value for each channel
                    for (int i = 0; i < v.size() / 3; i++) {
                        if (i % dim == 0) {
                            x = 0;
                            y += ySize;
                        }
                        double r = v.get(i);
                        double g = v.get(i + v.size() / 3);
                        double b = v.get(i + 2 * v.size() / 3);
                        if (min[0] > r) {
                            min[0] = r;
                        }
                        if (min[1] > g) {
                            min[1] = g;
                        }
                        if (min[2] > b) {
                            min[2] = b;
                        }
                        if (max[0] < r) {
                            max[0] = r;
                        }
                        if (max[1] < g) {
                            max[1] = g;
                        }
                        if (max[2] < b) {
                            max[2] = b;
                        }
                        x += xSize;
                    }
                    x = 0;
                    y = -ySize;
                } else {
                    for (int i = 0; i < 3; i++) {
                        max[i] = 255.0;
                    }
                }
                for (int i = 0; i < v.size() / 3; i++) {
                    if (i % dim == 0) {
                        x = 0;
                        y += ySize;
                    }
                    int r = (int) (255.0 * ((v.get(i) - min[0]) / (max[0] - min[0])));
                    int g = (int) (255.0 * ((v.get(i + v.size() / 3) - min[1]) / (max[1] - min[1])));
                    int b = (int) (255.0 * ((v.get(i + 2 * v.size() / 3) - min[2]) / (max[2] - min[2])));
                    gr.setColor(new Color(r, g, b));
                    gr.fillRect(x, y, xSize, ySize);
                    x += xSize;
                }
            }
        }
        MyPanel mainPanel = new MyPanel();
        JDialog d = new JDialog();
        d.setModal(true);
        d.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        d.add(mainPanel);
        d.setSize(10 * (int) Math.round(Math.sqrt(v.size())), 2 + 10 * (int) Math.round(Math.sqrt(v.size())));
        d.setResizable(false);
        d.setVisible(true);
    }

    /**
     * Returns a constant DenseVector with the given value in each slot.
     *
     * @param value  the constant to use
     * @param length the length of the vector
     * @return DenseVector
     */
    protected DenseVector constantVector(double value, int length) {
        double[] v = new double[length];
        Arrays.fill(v, value);
        return new DenseVector(v);
    }

    @OptionMetadata(
            displayName = "Seed for random number generation",
            description = "The seed value used by the random number generator.",
            displayOrder = 1,
            commandLineParamName = "S",
            commandLineParamSynopsis = "-S")
    public int getSeed() {
        return m_seed;
    }

    public void setSeed(int seed) {
        this.m_seed = seed;
    }

    @OptionMetadata(
            displayName = "Patch size to use (value X means X x X patches will be used)",
            description = "The patch size to use (value X means X x X patches will be used).",
            displayOrder = 2,
            commandLineParamName = "size",
            commandLineParamSynopsis = "-size")
    public int getCropSize() {
        return m_cropSize;
    }

    public void setCropSize(int cropSize) {
        this.m_cropSize = cropSize;
    }

    @OptionMetadata(
            displayName = "Number of patches per image",
            description = "The number of patches to be extracted per image.",
            displayOrder = 3,
            commandLineParamName = "numPatches",
            commandLineParamSynopsis = "-numPatches")
    public int getNumPatchesPerImage() {
        return m_numPatchesPerImage;
    }

    public void setNumPatchesPerImage(int numPatchesPerImage) {
        this.m_numPatchesPerImage = numPatchesPerImage;
    }

    @OptionMetadata(
            displayName = "Number of clusters",
            description = "The number of clusters/filters/dictionary atoms to learn.",
            displayOrder = 4,
            commandLineParamName = "K",
            commandLineParamSynopsis = "-K")
    public int getK() {
        return m_K;
    }

    public void setK(int K) {
        this.m_K = K;
    }

    @OptionMetadata(
            displayName = "Stride",
            description = "The stride to use when filters are applied to an image (both directions).",
            displayOrder = 5,
            commandLineParamName = "stride",
            commandLineParamSynopsis = "-stride")
    public int getStride() {
        return m_stride;
    }

    public void setStride(int stride) {
        this.m_stride = stride;
    }

    @OptionMetadata(
            displayName = "Pool size",
            description = "The size of the pool to use when creating features (both directions).",
            displayOrder = 6,
            commandLineParamName = "pool",
            commandLineParamSynopsis = "-pool")
    public int getPoolSize() {
        return m_poolSize;
    }

    public void setPoolSize(int pool) {
        this.m_poolSize = pool;
    }

    /**
     * The main method used for running this filter from the command-line interface.
     *
     * @param options the command-line options
     */
    public static void main(String[] options) {
        runFilter(new KMeansImageFilter(), options);
    }
}
